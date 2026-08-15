package com.codexce.supportchat.viewmodel

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexce.supportchat.data.SupportAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthUiState(
    val user: FirebaseUser? = null,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val signedIn: Boolean get() = user != null
    val uid: String? get() = user?.uid
    val email: String get() = user?.email ?: ""

    /**
     * Google supplies this on the FirebaseUser after sign-in. Email accounts have none, so
     * every caller has to cope with null rather than treat a picture as guaranteed.
     */
    val photoUrl: String? get() = user?.photoUrl?.toString()
}

/**
 * Email/password sign-in, unchanged in behaviour from the original app.
 *
 * The one fix: auth state now comes from an AuthStateListener instead of a single read of
 * currentUser, so a revoked or expired token propagates to the UI instead of leaving the app
 * showing a signed-in shell that can no longer read the database.
 */
class AuthViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()

    /**
     * The account bootstrap. Signing in calls POST /v1/bootstrap, which creates the tenant and
     * writes the tenantId custom claim server-side. The client provisions nothing
     * itself any more; it cannot write either database.
     */
    private val account = SupportAccount()

    private val _state = MutableStateFlow(AuthUiState(user = auth.currentUser))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { instance ->
        _state.update { it.copy(user = instance.currentUser) }
    }

    init {
        auth.addAuthStateListener(authStateListener)
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
    }

    fun signIn(email: String, password: String) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val user = auth.signInWithEmailAndPassword(email.trim(), password).await().user
                // Email sign-in has to provision too, otherwise the account signs in
                // successfully and then carries no tenant claim, so it can read nothing.
                val problem = if (user != null) account.ensureProvisioned(user) else null
                _state.update { it.copy(submitting = false, error = problem) }
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        submitting = false,
                        error = failure.localizedMessage ?: "Sign in failed",
                    )
                }
            }
        }
    }

    /**
     * Google sign-in via Credential Manager, then exchanged for a Firebase credential.
     *
     * [context] must be an Activity context. [serverClientId] is the OAuth web client id; it is
     * blank until Google is enabled in the Firebase console and google-services.json is replaced,
     * so that case is reported as a readable message rather than a crash.
     */
    fun signInWithGoogle(context: Context, serverClientId: String) {
        if (_state.value.submitting) return

        if (serverClientId.isBlank() || serverClientId.startsWith("REPLACE_")) {
            _state.update {
                it.copy(
                    error = "Google sign-in is not configured yet. Enable Google in Firebase " +
                        "Authentication, add your SHA-1, then put the web client id in " +
                        "res/values/strings.xml (google_web_client_id).",
                )
            }
            return
        }

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                // SupportAccount owns the whole Google path: credential, Firebase exchange,
                // and the POST /v1/bootstrap call that mints the tenant claims.
                account.signInWithGoogle(context, serverClientId)
                _state.update { it.copy(submitting = false, error = null) }
            } catch (cancelled: GetCredentialCancellationException) {
                // A sheet that closes itself right after an account is picked is almost never
                // the user changing their mind: it is the project's missing SHA-1 fingerprint
                // making Play services abort the flow. Swallowing this silently is what made
                // "I pick my account and nothing happens" undebuggable.
                _state.update {
                    it.copy(
                        submitting = false,
                        error = "Sign-in was cancelled. If the Google sheet closed by itself " +
                            "right after you chose an account, add your debug SHA-1 in the " +
                            "Firebase console (Project settings > Your apps > SHA certificate " +
                            "fingerprints), re-download google-services.json into app/, and " +
                            "reinstall. The keytool command is in NOTIFICATIONS.md.",
                    )
                }
            } catch (none: NoCredentialException) {
                _state.update {
                    it.copy(
                        submitting = false,
                        error = "No Google account available. Add one in Settings > " +
                            "Accounts, or use Sign in with Email. On an emulator this " +
                            "needs a system image that includes the Play Store.",
                    )
                }
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        submitting = false,
                        error = failure.localizedMessage ?: "Google sign-in failed",
                    )
                }
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _state.update { it.copy(error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
