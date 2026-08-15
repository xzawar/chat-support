package com.codexce.supportchat.data.api

import com.codexce.supportchat.data.DATABASE_URL
import com.codexce.supportchat.data.model.ConversationStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.AggregateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Date
import java.security.SecureRandom

/**
 * Thrown for every refused or failed operation.
 *
 * The codes are the same strings the old Express backend returned, because the screens and the
 * offline outbox both branch on them. `status` keeps the old HTTP meaning purely so that
 * "permanent refusal" (4xx) can still be told apart from "try again later" (0).
 */
class ApiException(
    val code: String,
    val status: Int,
    override val message: String,
) : IOException(message) {

    /** The tenant's plan does not include this feature, or the subscription lapsed. */
    val featureLocked: Boolean
        get() = code == "feature_not_in_plan" || code == "subscription_inactive"

    /** Signed in, but this account owns no tenant yet. Bootstrap is required. */
    val notProvisioned: Boolean
        get() = code == "tenant_not_provisioned"

    /** Rules refused the write: this account is not the owner of that tenant. */
    val ownerOnly: Boolean
        get() = code == "owner_only" || code == "permission_denied"

    /** The account already has a website; only one is allowed. */
    val websiteLimitReached: Boolean
        get() = code == "website_limit_reached"

    val unauthenticated: Boolean
        get() = status == 401
}

/**
 * The whole backend. Firebase Auth + Firestore + Realtime Database, nothing else.
 *
 * There is no server and no Cloud Function in this path, so there are no custom claims either:
 * the tenant is resolved once per session by asking Firestore which tenant document has
 * `ownerUid == auth.uid`, and every rule then re-derives ownership from that same field. A stale
 * client-side value cannot grant anything, which is what used to be the "role demotion token
 * delay" worry and is now structurally impossible.
 *
 * The function names and signatures are exactly the ones the ViewModels and screens already call,
 * so nothing above this file had to change. Every call suspends on Dispatchers.IO; none of them
 * touch the main thread.
 */
object SupportApi {

    private const val MESSAGE_MAX_CHARS = 4000
    private const val RETENTION_MILLIS = 24L * 60 * 60 * 1000
    private const val LINK_CODE_TTL_MILLIS = 10L * 60 * 1000
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val rtdb by lazy { FirebaseDatabase.getInstance(DATABASE_URL).reference }

    // Resolved tenant, remembered per uid so the inbox does not re-query on every call. It is a
    // cache of a fact the rules re-check anyway, never a permission.
    @Volatile
    private var cachedTenantId: String? = null

    @Volatile
    private var cachedForUid: String? = null

    @Volatile
    private var mirroredTenantId: String? = null

    // --------------------------------------------------------------- identity

    private fun requireUid(): String = auth.currentUser?.uid
        ?: throw ApiException("unauthenticated", 401, "You are signed out.")

    /**
     * The tenant this account owns, or null if it owns none yet.
     *
     * Cached for the lifetime of the process against the uid it was resolved for, so switching
     * accounts can never inherit the previous tenant.
     */
    suspend fun resolveTenantId(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext null
        val cached = cachedTenantId
        if (!forceRefresh && cached != null && cachedForUid == uid) return@withContext cached

        val found = firebase {
            db.collection("tenants")
                .whereEqualTo("ownerUid", uid)
                .limit(1)
                .get()
                .await()
                .documents
                .firstOrNull()
                ?.id
        }
        cachedForUid = uid
        cachedTenantId = found
        found
    }

    private suspend fun requireTenantId(): String = resolveTenantId()
        ?: throw ApiException(
            "tenant_not_provisioned",
            404,
            "This account is not set up with a workspace yet.",
        )

    /** Called on sign-out so nothing of the previous account survives in memory. */
    fun forgetSession() {
        cachedTenantId = null
        cachedForUid = null
        mirroredTenantId = null
    }

    /**
     * RTDB rules cannot read Firestore, so ownership is mirrored to chats/{tenantId}/ownerUid.
     *
     * The node is create-once and only rewritable by the uid already in it, so this is safe to
     * call repeatedly: it reads first and writes only when the value is actually missing.
     */
    private suspend fun ensureOwnerMirror(tenantId: String) {
        if (mirroredTenantId == tenantId) return
        val uid = requireUid()
        val node = rtdb.child("chats").child(tenantId).child("ownerUid")
        val current = firebase { node.get().await().getValue(String::class.java) }
        if (current != uid) firebase { node.setValue(uid).await() }
        mirroredTenantId = tenantId
    }

    // -------------------------------------------------------------- bootstrap

    /**
     * First-run workspace creation, replacing POST /v1/bootstrap.
     *
     * tenants/{tenantId} and owners/{uid} are written in one Firestore transaction. The
     * owners/{uid} document is the lock: rules allow it to be created only when it does not
     * already exist, so one Firebase account can own exactly one tenant, for ever. Calling this
     * again is a no-op that returns the existing tenant, which keeps it safe to run on every
     * sign-in exactly as the endpoint was.
     */
    suspend fun bootstrap(tenantName: String?): JSONObject = withContext(Dispatchers.IO) {
        val user = auth.currentUser
            ?: throw ApiException("unauthenticated", 401, "You are signed out.")
        val uid = user.uid
        val company = tenantName?.trim()?.takeIf { it.isNotEmpty() } ?: "My workspace"

        val ownerLock = db.collection("owners").document(uid)
        val newTenantRef = db.collection("tenants").document("tnt_" + randomHex(12))

        val tenantId = firebase {
            db.runTransaction { tx ->
                val existing = tx.get(ownerLock)
                if (existing.exists()) {
                    // Already provisioned. Same answer the endpoint's "already_initialized" gave.
                    return@runTransaction existing.getString("tenantId") ?: ""
                }

                tx.set(
                    newTenantRef,
                    mapOf(
                        "tenantId" to newTenantRef.id,
                        "ownerUid" to uid,
                        "companyName" to company,
                        "ownerName" to (user.displayName ?: ""),
                        "email" to (user.email ?: ""),
                        "phone" to "",
                        "logoUrl" to null,
                        "website" to null,
                        "plan" to "free",
                        "active" to true,
                        "status" to "active",
                        "features" to listOf("chat"),
                        "websiteCount" to 0,
                        "subscriptionExpiresAt" to null,
                        "createdAt" to System.currentTimeMillis(),
                    ),
                )
                tx.set(ownerLock, mapOf("tenantId" to newTenantRef.id))
                newTenantRef.id
            }.await()
        }

        if (tenantId.isNullOrBlank()) {
            throw ApiException("bootstrap_failed", 500, "Could not create your workspace.")
        }

        cachedForUid = uid
        cachedTenantId = tenantId

        // The visitor-readable mirror and the RTDB ownership mirror. Both are derived data, so
        // they are written after the transaction rather than inside it.
        writePublicTenant(tenantId, "active", listOf("chat"), company, null)
        ensureOwnerMirror(tenantId)

        JSONObject().put("tenantId", tenantId).put("name", company)
    }

    // ----------------------------------------------------------------- tenant

    /** Replaces GET /v1/tenants/me. */
    suspend fun tenantsMe(): TenantMe = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val snapshot = firebase { db.collection("tenants").document(tenantId).get().await() }
        if (!snapshot.exists()) {
            forgetSession()
            throw ApiException(
                "tenant_not_provisioned",
                404,
                "This account is not set up with a workspace yet.",
            )
        }

        // Chat has to work, so the mirror is refreshed on the same trip the inbox already makes.
        ensureOwnerMirror(tenantId)

        val planId = snapshot.getString("plan") ?: "free"
        val plan = firebase { db.collection("plans").document(planId).get().await() }
        val status = snapshot.getString("status") ?: "active"
        val expiresAt = snapshot.getLong("subscriptionExpiresAt")
        val active = status == "active" &&
            (expiresAt == null || expiresAt <= 0L || expiresAt > System.currentTimeMillis())

        TenantMe.from(
            JSONObject()
                .put("tenantId", tenantId)
                .put("name", snapshot.getString("companyName") ?: "")
                .put("role", "owner")
                .put("isOwner", true)
                .put(
                    "plan",
                    JSONObject()
                        .put("id", planId)
                        .put("name", plan.getString("name") ?: planId)
                        .put("tier", (plan.getLong("tier") ?: 0L).toInt())
                        .put("priceCents", plan.getLong("priceCents") ?: 0L)
                        .put("currency", plan.getString("currency") ?: "USD"),
                )
                .put("status", status)
                .put("features", JSONArray(stringList(snapshot.get("features"))))
                .put("currentPeriodEnd", expiresAt ?: JSONObject.NULL)
                .put("subscriptionActive", active)
                // The owner profile fields, read from the same snapshot already in hand.
                .put("companyName", snapshot.getString("companyName") ?: "")
                .put("ownerName", snapshot.getString("ownerName") ?: "")
                .put("website", snapshot.getString("website") ?: "")
                .put("phone", snapshot.getString("phone") ?: ""),
        )
    }

    /**
     * Writes the three owner-editable profile fields.
     *
     * A partial update, not a set(): the rules require the merged document to keep tenantId,
     * ownerUid, status, features and websiteCount intact, and a set() would drop all of them.
     *
     * The cache is updated from here rather than by the caller so every screen reading
     * TenantSession redraws immediately. Waiting for the next tenantsMe() round trip was what
     * made the Settings header keep showing the old name after a save.
     */
    suspend fun updateTenantProfile(
        ownerName: String,
        companyName: String,
        phone: String,
    ) = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        firebase {
            db.collection("tenants").document(tenantId).update(
                mapOf(
                    "ownerName" to ownerName,
                    "companyName" to companyName,
                    "phone" to phone,
                ),
            ).await()
        }
        com.codexce.supportchat.data.TenantSession.applyProfile(ownerName, companyName, phone)
    }

    // ---------------------------------------------------------------- billing

    /** Replaces GET /v1/billing/plans. */
    suspend fun plans(): PlanCatalog = withContext(Dispatchers.IO) {
        val tenantId = resolveTenantId()

        /*
         * These Firestore reads have no dependency on one another once the tenant id is known.
         * Parallelising them reduces the time the Subscription screen spends loading without ever
         * moving work to Main: the parent is already Dispatchers.IO and both children inherit it.
         */
        val (tenant, docs) = coroutineScope {
            val tenantDeferred = async {
                tenantId?.let { firebase { db.collection("tenants").document(it).get().await() } }
            }
            val plansDeferred = async {
                firebase {
                    db.collection("plans").orderBy("tier", Query.Direction.ASCENDING).get()
                        .await().documents
                }
            }
            tenantDeferred.await() to plansDeferred.await()
        }

        val array = JSONArray()
        docs.forEach { doc ->
            array.put(
                JSONObject()
                    .put("id", doc.id)
                    .put("name", doc.getString("name") ?: doc.id)
                    .put("tier", (doc.getLong("tier") ?: 0L).toInt())
                    .put("priceCents", doc.getLong("priceCents") ?: 0L)
                    .put("currency", doc.getString("currency") ?: "USD")
                    .put("features", JSONArray(stringList(doc.get("features"))))
                    .put("description", doc.getString("description") ?: JSONObject.NULL),
            )
        }

        PlanCatalog.from(
            JSONObject()
                .put("plans", array)
                .put("currentPlan", tenant?.getString("plan") ?: JSONObject.NULL)
                .put("status", tenant?.getString("status") ?: JSONObject.NULL),
        )
    }

    /**
     * Replaces POST /v1/billing/apply-coupon: a 100%-off code activates the plan outright.
     *
     * The coupon and the tenant are written in the same transaction, and rules only allow
     * `used` to move false -> true, so a code cannot be redeemed twice even from two devices.
     */
    suspend fun applyCoupon(planId: String, code: String): CouponResult = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) {
            throw ApiException("missing_field", 400, "Enter a coupon code.")
        }

        val couponRef = db.collection("coupons").document(normalized)
        val tenantRef = db.collection("tenants").document(tenantId)
        val planRef = db.collection("plans").document(planId)
        val plan = firebase { planRef.get().await() }
        if (!plan.exists()) throw ApiException("plans_missing", 404, "That plan no longer exists.")

        val features = stringList(plan.get("features")).ifEmpty { listOf("chat") }
        val periodEnd = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000

        firebase {
            db.runTransaction { tx ->
                val coupon = tx.get(couponRef)
                if (!coupon.exists()) {
                    throw FirebaseFirestoreException(
                        "coupon_not_found",
                        FirebaseFirestoreException.Code.NOT_FOUND,
                    )
                }
                if (coupon.getBoolean("used") == true) {
                    throw FirebaseFirestoreException(
                        "coupon_used",
                        FirebaseFirestoreException.Code.ALREADY_EXISTS,
                    )
                }

                tx.set(couponRef, mapOf("planId" to (coupon.getString("planId") ?: planId), "used" to true))
                tx.update(
                    tenantRef,
                    mapOf(
                        "plan" to planId,
                        "status" to "active",
                        "features" to features,
                        "subscriptionExpiresAt" to periodEnd,
                    ),
                )
                null
            }.await()
        }

        val tenant = firebase { tenantRef.get().await() }
        writePublicTenant(
            tenantId,
            "active",
            features,
            tenant.getString("companyName") ?: "",
            tenant.getString("logoUrl"),
        )

        CouponResult.from(
            JSONObject()
                .put("activated", true)
                .put("planId", planId)
                .put("percentOff", 100)
                .put("originalPriceCents", plan.getLong("priceCents") ?: 0L)
                .put("discountedPriceCents", 0L)
                .put("currency", plan.getString("currency") ?: "USD")
                .put("currentPeriodEnd", periodEnd)
                .put("message", "Coupon applied. Your plan is active."),
        )
    }

    /**
     * Replaces POST /v1/billing/checkout.
     *
     * TODO: real payment processing needs a server-side webhook to be trustworthy (a client can
     * always lie about having paid). Until one exists, every plan switch is confirmed immediately
     * and nothing is ever charged — which is exactly what the sandbox gateway did before.
     */
    suspend fun checkout(planId: String, couponCode: String? = null): CheckoutResult =
        withContext(Dispatchers.IO) {
            if (!couponCode.isNullOrBlank()) {
                val coupon = applyCoupon(planId, couponCode)
                return@withContext CheckoutResult.from(
                    JSONObject()
                        .put("paymentId", "pay_" + randomHex(8))
                        .put("provider", "sandbox")
                        .put("status", "approved")
                        .put("activated", coupon.activated)
                        .put("amountCents", 0L)
                        .put("currency", coupon.currency)
                        .put("planId", planId),
                )
            }

            val tenantId = requireTenantId()
            val plan = firebase { db.collection("plans").document(planId).get().await() }
            if (!plan.exists()) throw ApiException("plans_missing", 404, "That plan no longer exists.")

            val features = stringList(plan.get("features")).ifEmpty { listOf("chat") }
            val periodEnd = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
            firebase {
                db.collection("tenants").document(tenantId).update(
                    mapOf(
                        "plan" to planId,
                        "status" to "active",
                        "features" to features,
                        "subscriptionExpiresAt" to periodEnd,
                    ),
                ).await()
            }

            val tenant = firebase { db.collection("tenants").document(tenantId).get().await() }
            writePublicTenant(
                tenantId,
                "active",
                features,
                tenant.getString("companyName") ?: "",
                tenant.getString("logoUrl"),
            )

            CheckoutResult.from(
                JSONObject()
                    .put("paymentId", "pay_" + randomHex(8))
                    .put("provider", "sandbox")
                    .put("status", "approved")
                    .put("activated", true)
                    .put("amountCents", 0L)
                    .put("currency", plan.getString("currency") ?: "USD")
                    .put("planId", planId),
            )
        }

    // --------------------------------------------------------------- websites

    suspend fun websites(): List<Website> = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        firebase {
            db.collection("tenants").document(tenantId).collection("websites").get().await()
        }.documents.map { doc ->
            Website.from(
                JSONObject()
                    .put("id", doc.id)
                    .put("domain", doc.getString("domain") ?: "")
                    .put("active", doc.getBoolean("active") ?: true)
                    .put("keyFingerprint", doc.getString("keyFingerprint") ?: JSONObject.NULL)
                    .put("createdAt", doc.getLong("createdAt") ?: JSONObject.NULL),
            )
        }
    }

    /**
     * One website per owner, enforced structurally.
     *
     * The tenant's websiteCount is read and incremented inside the same transaction as the
     * website document, so two simultaneous taps cannot both commit — the loser's transaction
     * sees a changed counter and retries into the refusal below. Rules re-check the same
     * condition, so a hand-rolled client cannot skip it either.
     */
    suspend fun createWebsite(domain: String): WebsiteSecret = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val host = normalizeDomain(domain)
        if (host.isEmpty()) throw ApiException("missing_field", 400, "Enter your website domain.")

        val tenantRef = db.collection("tenants").document(tenantId)
        val websiteRef = tenantRef.collection("websites").document("web_" + randomHex(10))
        val apiKey = generateApiKey()
        val keyRef = db.collection("apiKeys").document(apiKey)
        val now = System.currentTimeMillis()
        // The plugin never sees the raw key. It types this short code instead and trades it
        // for the key over the REST API, so the code is written in the same transaction: a
        // website can never exist with no way to link it.
        val linkCode = generateLinkCode()
        val linkCodeRef = db.collection("linkCodes").document(linkCode)
        val linkCodeExpiresAt = now + LINK_CODE_TTL_MILLIS

        firebase {
            db.runTransaction { tx ->
                val tenant = tx.get(tenantRef)
                val count = tenant.getLong("websiteCount") ?: 0L
                if (count >= 1L) {
                    throw FirebaseFirestoreException(
                        "website_limit_reached",
                        FirebaseFirestoreException.Code.ALREADY_EXISTS,
                    )
                }

                tx.set(
                    websiteRef,
                    mapOf(
                        "domain" to host,
                        "active" to true,
                        "createdAt" to now,
                        // The owner keeps the raw key so it can be shown again and so rotation
                        // knows which apiKeys document to delete.
                        "apiKey" to apiKey,
                        "keyFingerprint" to fingerprint(apiKey),
                    ),
                )
                tx.set(
                    keyRef,
                    mapOf(
                        "tenantId" to tenantId,
                        "websiteId" to websiteRef.id,
                        "domain" to host,
                        "active" to true,
                        "createdAt" to now,
                    ),
                )
                tx.set(
                    linkCodeRef,
                    linkCodeDoc(apiKey, tenantId, websiteRef.id, host, now, linkCodeExpiresAt),
                )
                tx.update(tenantRef, mapOf("websiteCount" to count + 1))
                null
            }.await()
        }

        WebsiteSecret.from(
            JSONObject()
                .put("id", websiteRef.id)
                .put("domain", host)
                .put("apiKey", apiKey)
                .put("linkCode", formatLinkCode(linkCode))
                .put("linkCodeExpiresAt", linkCodeExpiresAt)
                .put(
                    "warning",
                    "Anyone with this key can open chats for your site. Keep it to your own pages.",
                ),
        )
    }

    /**
     * Rotation is one transaction: the old key document is deleted and the new one created
     * together, so the old key stops resolving the instant the new one exists. There is no window
     * in which both work.
     */
    suspend fun rotateWebsiteKey(websiteId: String): WebsiteSecret = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val websiteRef = db.collection("tenants").document(tenantId)
            .collection("websites").document(websiteId)
        val newKey = generateApiKey()
        val newKeyRef = db.collection("apiKeys").document(newKey)
        val now = System.currentTimeMillis()
        val linkCode = generateLinkCode()
        val linkCodeRef = db.collection("linkCodes").document(linkCode)
        val linkCodeExpiresAt = now + LINK_CODE_TTL_MILLIS

        val domain = firebase {
            db.runTransaction { tx ->
                val website = tx.get(websiteRef)
                if (!website.exists()) {
                    throw FirebaseFirestoreException(
                        "website_not_found",
                        FirebaseFirestoreException.Code.NOT_FOUND,
                    )
                }
                val host = website.getString("domain") ?: ""
                val oldKey = website.getString("apiKey")

                if (!oldKey.isNullOrBlank()) {
                    tx.delete(db.collection("apiKeys").document(oldKey))
                }
                tx.set(
                    newKeyRef,
                    mapOf(
                        "tenantId" to tenantId,
                        "websiteId" to websiteId,
                        "domain" to host,
                        "active" to true,
                        "createdAt" to now,
                    ),
                )
                tx.set(
                    linkCodeRef,
                    linkCodeDoc(newKey, tenantId, websiteId, host, now, linkCodeExpiresAt),
                )
                tx.update(
                    websiteRef,
                    mapOf(
                        "apiKey" to newKey,
                        "keyFingerprint" to fingerprint(newKey),
                        "rotatedAt" to now,
                    ),
                )
                host
            }.await()
        }

        WebsiteSecret.from(
            JSONObject()
                .put("id", websiteId)
                .put("domain", domain ?: "")
                .put("apiKey", newKey)
                .put("linkCode", formatLinkCode(linkCode))
                .put("linkCodeExpiresAt", linkCodeExpiresAt)
                .put("warning", "The previous key stopped working the moment this one was issued."),
        )
    }

    /** Deleting the website frees the one-website slot and kills its key in the same transaction. */
    suspend fun deleteWebsite(websiteId: String) = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val tenantRef = db.collection("tenants").document(tenantId)
        val websiteRef = tenantRef.collection("websites").document(websiteId)

        firebase {
            db.runTransaction { tx ->
                val website = tx.get(websiteRef)
                if (!website.exists()) return@runTransaction null
                val tenant = tx.get(tenantRef)
                val count = tenant.getLong("websiteCount") ?: 0L
                val oldKey = website.getString("apiKey")

                if (!oldKey.isNullOrBlank()) {
                    tx.delete(db.collection("apiKeys").document(oldKey))
                }
                tx.delete(websiteRef)
                tx.update(tenantRef, mapOf("websiteCount" to maxOf(0L, count - 1)))
                null
            }.await()
        }
        Unit
    }

    // ------------------------------------------------------------------ chat

    /**
     * The owner's reply. One multi-path update writes the message, the conversation preview, the
     * visitor's unread counter and the new expiry together, so a thread can never show a preview
     * that does not match its last message.
     *
     * clientMessageId is the RTDB child key, so replaying a queued send overwrites instead of
     * duplicating. That is what makes the offline outbox safe.
     */
    suspend fun sendMessage(conversationId: String, clientMessageId: String, text: String) =
        withContext(Dispatchers.IO) {
            val tenantId = requireTenantId()
            ensureOwnerMirror(tenantId)

            val body = text.trim()
            if (body.isEmpty()) throw ApiException("missing_field", 400, "Type a message first.")
            if (body.length > MESSAGE_MAX_CHARS) {
                throw ApiException("field_too_long", 400, "That message is too long to send.")
            }
            if (!clientMessageId.matches(Regex("^[A-Za-z0-9_-]+$"))) {
                throw ApiException("invalid_client_message_id", 400, "Could not queue that message.")
            }

            val chat = rtdb.child("chats").child(tenantId)
            val conversation = firebase {
                chat.child("conversations").child(conversationId).get().await()
            }
            if (!conversation.exists()) {
                throw ApiException("conversation_not_found", 404, "That chat no longer exists.")
            }
            if (conversation.child("status").getValue(String::class.java) ==
                ConversationStatus.CLOSED
            ) {
                throw ApiException("conversation_closed", 409, "This chat is closed.")
            }

            val keepChat = conversation.child("keepChat").getValue(Boolean::class.java) ?: false
            val unreadForVisitor =
                conversation.child("unreadForVisitor").getValue(Long::class.java) ?: 0L
            val now = System.currentTimeMillis()

            val updates = HashMap<String, Any?>(8)
            updates["messages/$conversationId/$clientMessageId"] = mapOf(
                // "agent" is the wire value for "written from the app", read by the deployed
                // widget and by existing history. Authorship, not a role.
                "sender" to "agent",
                "text" to body,
                "createdAt" to ServerValue.TIMESTAMP,
            )
            updates["conversations/$conversationId/lastMessage"] = mapOf(
                "text" to body,
                "sender" to "agent",
                "at" to ServerValue.TIMESTAMP,
            )
            updates["conversations/$conversationId/unreadForVisitor"] = unreadForVisitor + 1
            if (!keepChat) {
                updates["conversations/$conversationId/expiresAt"] = now + RETENTION_MILLIS
            }

            firebase { chat.updateChildren(updates).await() }
            Unit
        }

    /**
     * Replaces PATCH /v1/conversations/{id}. The accepted keys are the same ones the route
     * accepted, and anything else is ignored rather than written.
     *
     * Setting status to open also stamps startedAt and assigns the caller, in the same update,
     * which is what made Start Chat atomic server-side.
     */
    suspend fun patchConversation(conversationId: String, updates: JSONObject) =
        withContext(Dispatchers.IO) {
            val tenantId = requireTenantId()
            ensureOwnerMirror(tenantId)
            val uid = requireUid()

            val chat = rtdb.child("chats").child(tenantId)
            val conversation = firebase {
                chat.child("conversations").child(conversationId).get().await()
            }
            if (!conversation.exists()) {
                throw ApiException("conversation_not_found", 404, "That chat no longer exists.")
            }

            val patch = HashMap<String, Any?>(6)
            val prefix = "conversations/$conversationId/"

            if (updates.has("status")) {
                val status = updates.optString("status")
                if (status !in
                    listOf(
                        ConversationStatus.PENDING,
                        ConversationStatus.OPEN,
                        ConversationStatus.CLOSED,
                    )
                ) {
                    throw ApiException("invalid_status", 400, "Unknown chat status.")
                }
                patch[prefix + "status"] = status
                if (status == ConversationStatus.OPEN) {
                    if ((conversation.child("startedAt").getValue(Long::class.java) ?: 0L) <= 0L) {
                        patch[prefix + "startedAt"] = ServerValue.TIMESTAMP
                    }
                    patch[prefix + "assignedAgentUid"] = uid
                }
            }
            if (updates.has("keepChat")) {
                val keep = updates.optBoolean("keepChat")
                patch[prefix + "keepChat"] = keep
                // Keeping a chat lifts its expiry; releasing it starts the clock again from now.
                patch[prefix + "expiresAt"] =
                    if (keep) 0L else System.currentTimeMillis() + RETENTION_MILLIS
            }
            if (updates.has("unread")) {
                patch[prefix + "unread"] = maxOf(0L, updates.optLong("unread"))
            }

            if (patch.isEmpty()) throw ApiException("empty_update", 400, "Nothing to update.")
            firebase { chat.updateChildren(patch).await() }
            Unit
        }

    suspend fun setConversationStatus(conversationId: String, status: String) {
        patchConversation(conversationId, JSONObject().put("status", status))
    }

    suspend fun setKeepChat(conversationId: String, keep: Boolean) {
        patchConversation(conversationId, JSONObject().put("keepChat", keep))
    }

    suspend fun clearUnread(conversationId: String) {
        patchConversation(conversationId, JSONObject().put("unread", 0))
    }

    /** The conversation and its whole thread go in one update, so neither can be orphaned. */
    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        ensureOwnerMirror(tenantId)
        firebase {
            rtdb.child("chats").child(tenantId).updateChildren(
                mapOf(
                    "conversations/$conversationId" to null,
                    "messages/$conversationId" to null,
                ),
            ).await()
        }
        Unit
    }

    // ----------------------------------------------------------------- leads

    suspend fun leads(cursor: String? = null, limit: Int = 100): LeadPage =
        withContext(Dispatchers.IO) {
            val tenantId = requireTenantId()
            var query: Query = db.collection("tenants").document(tenantId).collection("leads")
                .orderBy("lastSeenAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
            cursor?.toLongOrNull()?.let { query = query.startAfter(it) }

            val docs = firebase { query.get().await() }.documents
            val array = JSONArray()
            docs.forEach { doc ->
                array.put(
                    JSONObject()
                        .put("id", doc.id)
                        .put("email", doc.getString("email") ?: "")
                        .put("name", doc.getString("name") ?: JSONObject.NULL)
                        .put("websiteId", doc.getString("websiteId") ?: JSONObject.NULL)
                        .put("websiteDomain", doc.getString("websiteDomain") ?: "Unknown site")
                        .put("source", doc.getString("source") ?: "manual")
                        .put("emailVerified", doc.getBoolean("emailVerified") ?: false)
                        .put("marketingConsent", doc.getBoolean("marketingConsent") ?: false)
                        .put("firstSeenAt", doc.getLong("firstSeenAt") ?: JSONObject.NULL)
                        .put("lastSeenAt", doc.getLong("lastSeenAt") ?: JSONObject.NULL)
                        .put("conversationCount", (doc.getLong("conversationCount") ?: 0L).toInt()),
                )
            }
            val last = docs.lastOrNull()?.getLong("lastSeenAt")

            LeadPage.from(
                JSONObject()
                    .put("leads", array)
                    .put("nextCursor", last?.toString() ?: JSONObject.NULL)
                    .put("hasMore", docs.size >= limit),
            )
        }

    suspend fun leadGroups(): List<LeadGroup> = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val docs = firebase {
            db.collection("tenants").document(tenantId).collection("leads")
                .orderBy("lastSeenAt", Query.Direction.DESCENDING)
                .limit(1000)
                .get()
                .await()
        }.documents

        docs.groupBy { it.getString("websiteId") ?: "" }
            .map { (websiteId, rows) ->
                LeadGroup.from(
                    JSONObject()
                        .put("websiteId", websiteId.ifBlank { JSONObject.NULL })
                        .put(
                            "websiteDomain",
                            rows.firstNotNullOfOrNull { it.getString("websiteDomain") }
                                ?: "Unknown site",
                        )
                        .put("leadCount", rows.size),
                )
            }
            .sortedByDescending { it.leadCount }
    }

    // ---------------------------------------------------------------- emails

    suspend fun emailStats(): EmailStats = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val tenantRef = db.collection("tenants").document(tenantId)
        // A server-side count, so a tenant with thousands of leads still costs one small read
        // instead of downloading the collection.
        val registered = firebase {
            tenantRef.collection("leads").count().get(AggregateSource.SERVER).await().count
        }
        val tenant = firebase { tenantRef.get().await() }

        EmailStats.from(
            JSONObject()
                .put("totalRegistered", (registered ?: 0L).toInt())
                .put("emailsSent", (tenant.getLong("emailsSent") ?: 0L).toInt())
                .put("emailsFailed", (tenant.getLong("emailsFailed") ?: 0L).toInt())
                .put("emailsClicked", (tenant.getLong("emailsClicked") ?: 0L).toInt()),
        )
    }

    suspend fun emailTemplates(): List<EmailTemplate> = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        firebase {
            db.collection("tenants").document(tenantId).collection("emailTemplates")
                .orderBy(FieldPath.documentId())
                .get()
                .await()
        }.documents.map(::templateFrom)
    }

    suspend fun createEmailTemplate(name: String, subject: String, body: String): EmailTemplate =
        withContext(Dispatchers.IO) {
            val tenantId = requireTenantId()
            val ref = db.collection("tenants").document(tenantId)
                .collection("emailTemplates").document("tpl_" + randomHex(8))
            firebase {
                ref.set(
                    mapOf(
                        "name" to name.trim(),
                        "subject" to subject.trim(),
                        "body" to body,
                        "seeded" to false,
                        "updatedAt" to System.currentTimeMillis(),
                    ),
                ).await()
            }
            EmailTemplate.from(firebase { ref.get().await() }.let(::templateJson))
        }

    suspend fun updateEmailTemplate(
        id: String,
        name: String,
        subject: String,
        body: String,
    ): EmailTemplate = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        val ref = db.collection("tenants").document(tenantId)
            .collection("emailTemplates").document(id)
        firebase {
            ref.set(
                mapOf(
                    "name" to name.trim(),
                    "subject" to subject.trim(),
                    "body" to body,
                    "updatedAt" to System.currentTimeMillis(),
                ),
                SetOptions.merge(),
            ).await()
        }
        EmailTemplate.from(firebase { ref.get().await() }.let(::templateJson))
    }

    suspend fun deleteEmailTemplate(id: String) = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        firebase {
            db.collection("tenants").document(tenantId)
                .collection("emailTemplates").document(id).delete().await()
        }
        Unit
    }

    // --------------------------------------------------------------- devices

    suspend fun registerDevice(deviceId: String, token: String) = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        firebase {
            db.collection("tenants").document(tenantId).collection("devices").document(deviceId)
                .set(
                    mapOf(
                        "token" to token,
                        "platform" to "android",
                        "updatedAt" to System.currentTimeMillis(),
                    ),
                )
                .await()
        }
        Unit
    }

    suspend fun unregisterDevice(deviceId: String) = withContext(Dispatchers.IO) {
        val tenantId = requireTenantId()
        firebase {
            db.collection("tenants").document(tenantId).collection("devices")
                .document(deviceId).delete().await()
        }
        Unit
    }

    // --------------------------------------------------------------- helpers

    /** The visitor-readable projection of the tenant. Never contains owner contact details. */
    private suspend fun writePublicTenant(
        tenantId: String,
        status: String,
        features: List<String>,
        companyName: String,
        logoUrl: String?,
    ) {
        firebase {
            db.collection("publicTenants").document(tenantId).set(
                mapOf(
                    "status" to status,
                    "features" to features,
                    "companyName" to companyName,
                    "logoUrl" to logoUrl,
                ),
            ).await()
        }
    }

    private fun templateJson(doc: com.google.firebase.firestore.DocumentSnapshot): JSONObject =
        JSONObject()
            .put("id", doc.id)
            .put("name", doc.getString("name") ?: "Untitled")
            .put("subject", doc.getString("subject") ?: "")
            .put("body", doc.getString("body") ?: "")
            .put("seeded", doc.getBoolean("seeded") ?: false)
            .put("updatedAt", doc.getLong("updatedAt") ?: JSONObject.NULL)

    private fun templateFrom(doc: com.google.firebase.firestore.DocumentSnapshot): EmailTemplate =
        EmailTemplate.from(templateJson(doc))

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun normalizeDomain(input: String): String = input.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
        .lowercase()

    private val secureRandom = SecureRandom()

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    /**
     * The key is the apiKeys document id, so it has to stay plaintext: a rule cannot hash an
     * incoming value to look a document up by it. 24 random bytes is 192 bits of entropy, which
     * is not guessable, but it is stored rather than hashed — see README-FIREBASE-ONLY.md.
     */
    private fun generateApiKey(): String = "sk_live_" + randomHex(24)

    private fun fingerprint(apiKey: String): String = "•••• " + apiKey.takeLast(6)

    /**
     * Eight characters from a 32 symbol alphabet with no 0/O or 1/I, so it survives being read
     * off a phone and typed into a browser. That is about 40 bits, which is only safe because
     * the code dies after ten minutes, cannot be listed, and burns on first use.
     */
    private fun generateLinkCode(): String = (1..8)
        .map { CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)] }
        .joinToString("")

    /** WBX7KQ2M -> WBX7-KQ2M for display only. The document id stays unhyphenated. */
    private fun formatLinkCode(code: String): String =
        if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code

    // Timestamps, not millis: the rule compares request.time against expiresAt, and only a
    // real timestamp can be compared that way.
    private fun linkCodeDoc(
        apiKey: String,
        tenantId: String,
        websiteId: String,
        domain: String,
        createdAt: Long,
        expiresAt: Long,
    ): Map<String, Any?> = mapOf(
        "apiKey" to apiKey,
        "tenantId" to tenantId,
        "websiteId" to websiteId,
        "domain" to domain,
        "createdAt" to Date(createdAt),
        "expiresAt" to Date(expiresAt),
        "used" to false,
    )

    /**
     * Turns Firebase failures into the ApiExceptions the rest of the app already understands.
     *
     * The status matters: the outbox replays anything it reads as transient (0) and drops
     * anything it reads as a permanent refusal (4xx), so "you are offline" must never be
     * classified as "this message will never be accepted".
     */
    private inline fun <T> firebase(block: () -> T): T = try {
        block()
    } catch (api: ApiException) {
        throw api
    } catch (error: FirebaseFirestoreException) {
        throw when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                ApiException("permission_denied", 403, "You are not allowed to do that.")

            FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                ApiException("unauthenticated", 401, "Your session expired. Sign in again.")

            FirebaseFirestoreException.Code.NOT_FOUND -> when (error.message) {
                "coupon_not_found" -> ApiException("invalid_coupon", 404, "That coupon is not valid.")
                "website_not_found" -> ApiException("website_not_found", 404, "That website is gone.")
                else -> ApiException("not_found", 404, "Not found.")
            }

            FirebaseFirestoreException.Code.ALREADY_EXISTS -> when (error.message) {
                "coupon_used" ->
                    ApiException("coupon_used", 409, "That coupon has already been used.")

                "website_limit_reached" -> ApiException(
                    "website_limit_reached",
                    409,
                    "Your account already has a website. Remove it before adding another.",
                )

                else -> ApiException("conflict", 409, "That conflicts with something already saved.")
            }

            // Offline, deadline exceeded, transport failures: retryable, never permanent.
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
            ->
                ApiException("network_error", 0, "Could not reach the database. Check your connection.")

            else -> ApiException("request_failed", 0, error.localizedMessage ?: "Request failed.")
        }
    } catch (error: com.google.firebase.database.DatabaseException) {
        throw ApiException("request_failed", 0, error.localizedMessage ?: "Request failed.")
    } catch (error: Throwable) {
        val text = error.localizedMessage ?: "Request failed."
        // The Realtime Database reports a refused write as a plain exception carrying this text.
        throw if (text.contains("Permission denied", ignoreCase = true)) {
            ApiException("permission_denied", 403, "You are not allowed to do that.")
        } else {
            ApiException("request_failed", 0, text)
        }
    }
}
