package com.codexce.supportchat.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.data.api.TenantMe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TenantUiState(
    val tenant: TenantMe? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isOwner: Boolean get() = tenant?.isOwner == true

    /** Never trusted for security, only for hiding menu items the server would refuse anyway. */
    fun hasFeature(feature: String): Boolean = tenant?.hasFeature(feature) == true

    val planName: String get() = tenant?.plan?.name ?: "—"

    /** The account signed in but carries no tenant claim yet. */
    val notProvisioned: Boolean get() = tenant == null && !loading
}

/**
 * Owns the cached copy of GET /v1/tenants/me.
 *
 * The plan is fetched once at login and refreshed after every billing event. Gating in the UI
 * is a courtesy so agents are not shown doors that will not open; the backend enforces every
 * one of these gates for real, so a stale or tampered cache buys nothing.
 */
class TenantViewModel(application: Application) : AndroidViewModel(application) {

    private val loading = MutableStateFlow(false)

    val state: StateFlow<TenantUiState> = combine(
        TenantSession.tenant,
        loading,
        TenantSession.lastError,
    ) { tenant, busy, error ->
        TenantUiState(tenant = tenant, loading = busy, error = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenantUiState())

    init {
        refresh()
    }

    fun refresh(uid: String? = null) {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            TenantSession.refresh(getApplication(), uid)
            loading.value = false
        }
    }
}
