package com.codexce.supportchat.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.data.api.ApiException
import com.codexce.supportchat.data.api.PlanCard
import com.codexce.supportchat.data.api.SupportApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SubscriptionUiState(
    val loading: Boolean = false,
    val locked: Boolean = false,
    val plans: List<PlanCard> = emptyList(),
    val currentPlanId: String? = null,
    val status: String? = null,
    val loadError: String? = null,
    /** Coupon text per plan id: each card owns its own field. */
    val coupons: Map<String, String> = emptyMap(),
    /** The plan currently being applied or checked out, if any. */
    val busyPlanId: String? = null,
    val messages: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
)

/**
 * Backs the Subscription screen.
 *
 * Coupons and checkout are tracked per plan because the screen puts a coupon field on every
 * card: DEMO100 typed on the Scale card has to activate Scale and report back on that card.
 *
 * A successful activation refreshes the tenant, since the plan, features and period end shown
 * elsewhere in the app all come from that one record.
 */
class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, loadError = null)

        viewModelScope.launch {
            try {
                val catalog = SupportApi.plans()
                _state.value = _state.value.copy(
                    loading = false,
                    locked = false,
                    plans = catalog.plans,
                    currentPlanId = catalog.currentPlan,
                    status = catalog.status,
                    loadError = null,
                )
            } catch (error: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    locked = error.ownerOnly,
                    loadError = error.message,
                )
            }
        }
    }

    fun setCoupon(planId: String, code: String) {
        _state.value = _state.value.copy(
            coupons = _state.value.coupons + (planId to code),
            // Whatever the last attempt said no longer applies to what is typed now.
            errors = _state.value.errors - planId,
            messages = _state.value.messages - planId,
        )
    }

    fun applyCoupon(planId: String) {
        val code = _state.value.coupons[planId].orEmpty().trim()

        if (code.isBlank() || _state.value.busyPlanId != null) return
        startWork(planId)

        viewModelScope.launch {
            try {
                val result = SupportApi.applyCoupon(planId, code)

                if (result.activated) {
                    finishWork(
                        planId = planId,
                        message = result.message ?: "Coupon applied. The plan is active.",
                    )
                    _state.value = _state.value.copy(coupons = _state.value.coupons - planId)
                    TenantSession.refresh(getApplication())
                    load()
                } else {
                    // A discount without activation still needs a payment step, which does not
                    // exist yet, so say so rather than implying the plan changed.
                    finishWork(
                        planId = planId,
                        message = result.message
                            ?: "That coupon discounts this plan but does not activate it.",
                    )
                }
            } catch (error: ApiException) {
                finishWork(planId = planId, error = error.message)
            }
        }
    }

    fun checkout(planId: String) {
        if (_state.value.busyPlanId != null) return
        val code = _state.value.coupons[planId].orEmpty().trim().ifBlank { null }
        startWork(planId)

        viewModelScope.launch {
            try {
                val result = SupportApi.checkout(planId, code)

                if (result.activated) {
                    finishWork(planId = planId, message = "This plan is now active.")
                    TenantSession.refresh(getApplication())
                    load()
                } else {
                    finishWork(
                        planId = planId,
                        message = "Payment is pending. This plan activates once it clears.",
                    )
                }
            } catch (error: ApiException) {
                finishWork(planId = planId, error = error.message)
            }
        }
    }

    private fun startWork(planId: String) {
        _state.value = _state.value.copy(
            busyPlanId = planId,
            errors = _state.value.errors - planId,
            messages = _state.value.messages - planId,
        )
    }

    private fun finishWork(planId: String, message: String? = null, error: String? = null) {
        _state.value = _state.value.copy(
            busyPlanId = null,
            messages = if (message != null) {
                _state.value.messages + (planId to message)
            } else {
                _state.value.messages
            },
            errors = if (error != null) {
                _state.value.errors + (planId to error)
            } else {
                _state.value.errors
            },
        )
    }
}
