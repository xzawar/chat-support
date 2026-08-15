package com.codexce.supportchat.data.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain Kotlin mirrors of the backend JSON payloads.
 *
 * Every model is parsed defensively: a missing key never throws, it falls back
 * to a sane default. The backend is the source of truth, the app only renders
 * what it is given.
 */

fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return if (value.isBlank()) null else value
}

fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key, 0L)
}

fun JSONArray.toStringList(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val value = optString(i, "")
        if (value.isNotBlank()) out.add(value)
    }
    return out
}

fun JSONObject.stringListOrEmpty(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return array.toStringList()
}

fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        optJSONObject(i)?.let(out::add)
    }
    return out
}

/** Price helper shared by the plan cards. */
fun formatPrice(priceCents: Long, currency: String): String {
    if (priceCents <= 0L) return "Free"
    val whole = priceCents / 100
    val fraction = priceCents % 100
    val symbol = when (currency.uppercase()) {
        "USD" -> "$"
        "EUR" -> "\u20ac"
        "GBP" -> "\u00a3"
        "PKR" -> "Rs "
        else -> currency.uppercase() + " "
    }
    return if (fraction == 0L) symbol + whole else symbol + whole + "." + fraction.toString().padStart(2, '0')
}

data class PlanRef(
    val id: String,
    val name: String,
    val tier: Int,
    val priceCents: Long,
    val currency: String
) {
    val priceLabel: String get() = formatPrice(priceCents, currency)

    companion object {
        fun from(json: JSONObject?): PlanRef {
            if (json == null) return PlanRef("", "Unknown plan", 0, 0L, "USD")
            return PlanRef(
                id = json.optString("id", ""),
                name = json.optString("name", json.optString("id", "Plan")),
                tier = json.optInt("tier", 0),
                priceCents = json.optLong("priceCents", 0L),
                currency = json.optString("currency", "USD")
            )
        }
    }
}

/** GET /v1/tenants/me */
data class TenantMe(
    val tenantId: String,
    val name: String,
    val role: String,
    val isOwner: Boolean,
    val plan: PlanRef,
    val status: String,
    val features: List<String>,
    val currentPeriodEnd: Long?,
    val subscriptionActive: Boolean,
    /*
     * The owner-facing profile fields. These already existed on the tenant document
     * and were simply never carried through to the app, which is why the account
     * page could only ever show a role and an email.
     */
    val companyName: String = "",
    val ownerName: String = "",
    val website: String = "",
    val phone: String = ""
) {
    /**
     * Exact-name match only, and blanks never match. Mirrors the backend helper: there is no
     * wildcard, so a stray empty string in features[] unlocks nothing here either.
     */
    fun hasFeature(feature: String): Boolean {
        val wanted = feature.trim()
        if (wanted.isEmpty()) return false
        return subscriptionActive && features.any { it.trim() == wanted }
    }

    val statusLabel: String
        get() = when (status) {
            "active" -> "Active"
            "trialing" -> "Trial"
            "past_due" -> "Past due"
            "canceled" -> "Canceled"
            "" -> "Unknown"
            else -> status.replaceFirstChar { it.uppercase() }
        }

    companion object {
        fun from(json: JSONObject): TenantMe = TenantMe(
            tenantId = json.optString("tenantId", ""),
            name = json.optString("name", ""),
            // One role exists. The backend sends it as a constant and older builds of the API
            // may omit it, so the default is the only value it can be.
            role = json.optString("role", "owner").ifBlank { "owner" },
            isOwner = json.optBoolean("isOwner", true),
            plan = PlanRef.from(json.optJSONObject("plan")),
            status = json.optString("status", ""),
            features = json.stringListOrEmpty("features"),
            currentPeriodEnd = json.longOrNull("currentPeriodEnd"),
            subscriptionActive = json.optBoolean("subscriptionActive", false),
            companyName = json.optString("companyName", ""),
            ownerName = json.optString("ownerName", ""),
            website = json.optString("website", ""),
            phone = json.optString("phone", "")
        )
    }
}

/** One entry of GET /v1/billing/plans */
data class PlanCard(
    val id: String,
    val name: String,
    val tier: Int,
    val priceCents: Long,
    val currency: String,
    val features: List<String>,
    val description: String?
) {
    val priceLabel: String get() = formatPrice(priceCents, currency)

    companion object {
        fun from(json: JSONObject): PlanCard = PlanCard(
            id = json.optString("id", ""),
            name = json.optString("name", json.optString("id", "Plan")),
            tier = json.optInt("tier", 0),
            priceCents = json.optLong("priceCents", 0L),
            currency = json.optString("currency", "USD"),
            features = json.stringListOrEmpty("features"),
            description = json.stringOrNull("description")
        )
    }
}

/** GET /v1/billing/plans envelope. */
data class PlanCatalog(
    val plans: List<PlanCard>,
    val currentPlan: String?,
    val status: String?
) {
    companion object {
        fun from(json: JSONObject): PlanCatalog = PlanCatalog(
            plans = (json.optJSONArray("plans") ?: JSONArray()).objects().map(PlanCard::from),
            currentPlan = json.stringOrNull("currentPlan"),
            status = json.stringOrNull("status")
        )
    }
}

/** POST /v1/billing/apply-coupon */
data class CouponResult(
    val activated: Boolean,
    val planId: String?,
    val percentOff: Int?,
    val originalPriceCents: Long?,
    val discountedPriceCents: Long?,
    val currency: String,
    val currentPeriodEnd: Long?,
    val message: String?
) {
    companion object {
        fun from(json: JSONObject): CouponResult = CouponResult(
            activated = json.optBoolean("activated", false),
            planId = json.stringOrNull("planId"),
            percentOff = if (json.has("percentOff")) json.optInt("percentOff") else null,
            originalPriceCents = json.longOrNull("originalPriceCents") ?: json.longOrNull("priceCents"),
            discountedPriceCents = json.longOrNull("discountedPriceCents") ?: json.longOrNull("amountCents"),
            currency = json.optString("currency", "USD"),
            currentPeriodEnd = json.longOrNull("currentPeriodEnd"),
            message = json.stringOrNull("message")
        )
    }
}

/** POST /v1/billing/checkout */
data class CheckoutResult(
    val paymentId: String,
    val reference: String?,
    val provider: String?,
    val status: String?,
    val redirectUrl: String?,
    val activated: Boolean,
    val amountCents: Long,
    val currency: String,
    val planId: String?
) {
    companion object {
        fun from(json: JSONObject): CheckoutResult = CheckoutResult(
            paymentId = json.optString("paymentId", ""),
            reference = json.stringOrNull("reference"),
            provider = json.stringOrNull("provider"),
            status = json.stringOrNull("status"),
            redirectUrl = json.stringOrNull("redirectUrl"),
            activated = json.optBoolean("activated", false),
            amountCents = json.optLong("amountCents", 0L),
            currency = json.optString("currency", "USD"),
            planId = json.stringOrNull("planId")
        )
    }
}

/** tenants/{t}/websites/{id} as returned by GET /v1/websites */
data class Website(
    val id: String,
    val domain: String,
    val active: Boolean,
    val keyFingerprint: String?,
    val createdAt: Long?
) {
    companion object {
        fun from(json: JSONObject): Website = Website(
            id = json.optString("id", ""),
            domain = json.optString("domain", ""),
            active = json.optBoolean("active", true),
            keyFingerprint = json.stringOrNull("keyFingerprint"),
            createdAt = json.longOrNull("createdAt")
        )
    }
}

/** POST /v1/websites and POST /v1/websites/{id}/rotate-key. The raw key is shown once. */
data class WebsiteSecret(
    val id: String,
    val domain: String?,
    val apiKey: String,
    val warning: String?,
    /** Display form, WBX7-KQ2M. Null when this result carries no fresh code. */
    val linkCode: String? = null,
    val linkCodeExpiresAt: Long? = null
) {
    /** Drives the countdown. Zero once the code is dead, so the UI never shows a negative. */
    val linkCodeMillisLeft: Long
        get() = ((linkCodeExpiresAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    companion object {
        fun from(json: JSONObject): WebsiteSecret = WebsiteSecret(
            id = json.optString("id", ""),
            domain = json.stringOrNull("domain"),
            apiKey = json.optString("apiKey", ""),
            warning = json.stringOrNull("warning"),
            linkCode = json.stringOrNull("linkCode"),
            linkCodeExpiresAt = json.longOrNull("linkCodeExpiresAt")
        )
    }
}

/** tenants/{t}/leads/{id} */
data class Lead(
    val id: String,
    val email: String,
    val name: String?,
    val websiteId: String?,
    val websiteDomain: String,
    val source: String,
    val emailVerified: Boolean,
    val marketingConsent: Boolean,
    val firstSeenAt: Long?,
    val lastSeenAt: Long?,
    val conversationCount: Int
) {
    val fromGoogle: Boolean get() = source.equals("google", ignoreCase = true)

    companion object {
        fun from(json: JSONObject): Lead = Lead(
            id = json.optString("id", ""),
            email = json.optString("email", ""),
            name = json.stringOrNull("name"),
            websiteId = json.stringOrNull("websiteId"),
            websiteDomain = json.optString("websiteDomain", "Unknown site"),
            source = json.optString("source", "manual"),
            emailVerified = json.optBoolean("emailVerified", false),
            marketingConsent = json.optBoolean("marketingConsent", false),
            firstSeenAt = json.longOrNull("firstSeenAt"),
            lastSeenAt = json.longOrNull("lastSeenAt"),
            conversationCount = json.optInt("conversationCount", 0)
        )
    }
}

/** GET /v1/leads envelope. */
data class LeadPage(
    val leads: List<Lead>,
    val nextCursor: String?,
    val hasMore: Boolean
) {
    companion object {
        fun from(json: JSONObject): LeadPage = LeadPage(
            leads = (json.optJSONArray("leads") ?: JSONArray()).objects().map(Lead::from),
            nextCursor = json.stringOrNull("nextCursor"),
            hasMore = json.optBoolean("hasMore", false)
        )
    }
}

/** GET /v1/leads/groups */
data class LeadGroup(
    val websiteId: String?,
    val websiteDomain: String,
    val leadCount: Int
) {
    companion object {
        fun from(json: JSONObject): LeadGroup = LeadGroup(
            websiteId = json.stringOrNull("websiteId"),
            websiteDomain = json.optString(
                "websiteDomain",
                json.optString("domain", "Unknown site")
            ),
            leadCount = json.optInt("leadCount", json.optInt("count", 0))
        )
    }
}

/** GET /v1/email-stats */
data class EmailStats(
    val totalRegistered: Int,
    val emailsSent: Int,
    val emailsFailed: Int,
    val emailsClicked: Int
) {
    companion object {
        val EMPTY = EmailStats(0, 0, 0, 0)

        fun from(json: JSONObject): EmailStats = EmailStats(
            totalRegistered = json.optInt("totalRegistered", 0),
            emailsSent = json.optInt("emailsSent", 0),
            emailsFailed = json.optInt("emailsFailed", 0),
            emailsClicked = json.optInt("emailsClicked", 0)
        )
    }
}

/** tenants/{t}/emailTemplates/{id} */
data class EmailTemplate(
    val id: String,
    val name: String,
    val subject: String,
    val body: String,
    val seeded: Boolean,
    val updatedAt: Long?
) {
    companion object {
        fun from(json: JSONObject): EmailTemplate = EmailTemplate(
            id = json.optString("id", ""),
            name = json.optString("name", "Untitled"),
            subject = json.optString("subject", ""),
            body = json.optString("body", ""),
            seeded = json.optBoolean("seeded", false),
            updatedAt = json.longOrNull("updatedAt")
        )
    }
}
