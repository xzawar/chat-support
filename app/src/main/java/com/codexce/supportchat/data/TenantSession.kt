package com.codexce.supportchat.data

import android.content.Context
import com.codexce.supportchat.data.api.ApiException
import com.codexce.supportchat.data.api.SupportApi
import com.codexce.supportchat.data.api.TenantMe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Caches GET /v1/tenants/me so the UI can gate menu items without a round trip.
 *
 * This is a convenience cache only. The backend still enforces every gate, and
 * a stale cache can never grant access to anything.
 */
object TenantSession {

    private const val PREFS = "support_chat_tenant"
    private const val KEY_PAYLOAD = "tenant_me_json"
    private const val KEY_UID = "tenant_me_uid"

    private val _tenant = MutableStateFlow<TenantMe?>(null)
    val tenant: StateFlow<TenantMe?> = _tenant.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var appContext: Context? = null

    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Restores the last known plan for the given uid so the first frame is not empty. */
    fun restore(context: Context, uid: String?) {
        attach(context)
        if (uid.isNullOrBlank()) {
            _tenant.value = null
            return
        }
        val store = prefs(context)
        if (store.getString(KEY_UID, null) != uid) {
            _tenant.value = null
            return
        }
        val payload = store.getString(KEY_PAYLOAD, null) ?: return
        _tenant.value = runCatching { TenantMe.from(JSONObject(payload)) }.getOrNull()
    }

    /** Pulls a fresh copy from the backend. Returns null when the call fails. */
    suspend fun refresh(context: Context? = null, uid: String? = null): TenantMe? {
        context?.let(::attach)
        return try {
            val fresh = SupportApi.tenantsMe()
            _tenant.value = fresh
            _lastError.value = null
            persist(fresh, uid)
            fresh
        } catch (error: ApiException) {
            if (error.notProvisioned) {
                _tenant.value = null
                clearStored()
            }
            _lastError.value = error.message
            null
        }
    }

    private fun persist(tenant: TenantMe, uid: String?) {
        val context = appContext ?: return
        val resolvedUid = uid ?: prefs(context).getString(KEY_UID, null) ?: return
        val payload = JSONObject()
            .put("tenantId", tenant.tenantId)
            .put("name", tenant.name)
            .put("role", tenant.role)
            .put("isOwner", tenant.isOwner)
            .put(
                "plan",
                JSONObject()
                    .put("id", tenant.plan.id)
                    .put("name", tenant.plan.name)
                    .put("tier", tenant.plan.tier)
                    .put("priceCents", tenant.plan.priceCents)
                    .put("currency", tenant.plan.currency)
            )
            .put("status", tenant.status)
            .put("features", org.json.JSONArray(tenant.features))
            .put("currentPeriodEnd", tenant.currentPeriodEnd ?: JSONObject.NULL)
            .put("subscriptionActive", tenant.subscriptionActive)
            // Cached too, so the account page is populated on the very first frame.
            .put("companyName", tenant.companyName)
            .put("ownerName", tenant.ownerName)
            .put("website", tenant.website)
            .put("phone", tenant.phone)

        prefs(context).edit()
            .putString(KEY_PAYLOAD, payload.toString())
            .putString(KEY_UID, resolvedUid)
            .apply()
    }

    /**
     * Folds a saved profile into the cached tenant.
     *
     * companyName is mirrored into name because tenantsMe() derives name from companyName; if
     * only one moved, the header and the account page would disagree until the next refresh.
     */
    fun applyProfile(ownerName: String, companyName: String, phone: String) {
        val current = _tenant.value ?: return
        val updated = current.copy(
            name = companyName,
            companyName = companyName,
            ownerName = ownerName,
            phone = phone,
        )
        _tenant.value = updated
        persist(updated, null)
    }

    fun hasFeature(feature: String): Boolean = _tenant.value?.hasFeature(feature) == true

    val isOwner: Boolean get() = _tenant.value?.isOwner == true

    val tenantId: String? get() = _tenant.value?.tenantId?.takeIf { it.isNotBlank() }

    private fun clearStored() {
        val context = appContext ?: return
        prefs(context).edit().remove(KEY_PAYLOAD).apply()
    }

    fun clear() {
        _tenant.value = null
        _lastError.value = null
        val context = appContext ?: return
        prefs(context).edit().clear().apply()
    }
}
