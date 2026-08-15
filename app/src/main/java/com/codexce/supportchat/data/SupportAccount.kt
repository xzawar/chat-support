package com.codexce.supportchat.data

/*
 * Google sign-in and workspace provisioning.
 *
 * There is no server and no custom claim any more. Provisioning is one Firestore transaction that
 * writes tenants/{tenantId} and owners/{uid} together; the owners document is the lock, and rules
 * only allow it to be created when it does not already exist, so one Firebase account owns exactly
 * one tenant for ever.
 *
 * Because there is no claim to wait for, there is nothing to force-refresh either: the tenant is
 * resolved by asking Firestore which tenant document has ownerUid == auth.uid, and every rule
 * re-checks that same field on every read and write. A stale value on the device cannot grant
 * anything.
 *
 * Running this on every sign-in is safe: bootstrap returns the existing tenant when the lock is
 * already held.
 */

import android.content.Context
import com.codexce.supportchat.data.api.ApiException
import com.codexce.supportchat.data.api.SupportApi
import com.codexce.supportchat.util.GoogleAuthClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

class SupportAccount(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    val currentUser: FirebaseUser? get() = auth.currentUser

    val uid: String? get() = auth.currentUser?.uid

    /**
     * The tenant this device belongs to. Resolved from Firestore and cached per uid for the
     * process, never taken from user input, and never treated as a permission by itself.
     */
    suspend fun tenantId(forceRefresh: Boolean = false): String? =
        SupportApi.resolveTenantId(forceRefresh)

    /**
     * Sign in with Google using Credential Manager.
     *
     * serverClientId is the WEB client ID from Firebase Console, Authentication, Sign-in method,
     * Google, Web SDK configuration. Using the Android client ID here is the usual cause of
     * "developer error" at this step.
     */
    suspend fun signInWithGoogle(context: Context, serverClientId: String): FirebaseUser {
        val idToken = GoogleAuthClient.requestIdToken(context, serverClientId)
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)

        val user = auth.signInWithCredential(firebaseCredential).await().user
            ?: error("Google sign-in returned no Firebase user")

        ensureProvisioned(user)
        return user
    }

    suspend fun signOut() {
        // Drop the resolved tenant before the uid disappears, so the next account cannot inherit it.
        SupportApi.forgetSession()
        auth.signOut()
    }

    /**
     * Makes sure the signed-in account owns a workspace.
     *
     * The lookup runs first, so a returning owner costs a single small query. Bootstrap is only
     * attempted when no tenant is found, and it is idempotent.
     *
     * @return null on success, or a human-readable reason the account is not usable yet.
     */
    suspend fun ensureProvisioned(user: FirebaseUser): String? {
        val existing = runCatching { SupportApi.resolveTenantId(forceRefresh = true) }.getOrNull()
        if (!existing.isNullOrBlank()) return null

        val workspaceName = user.displayName
            ?: user.email?.substringBefore('@')
            ?: "My workspace"

        return try {
            SupportApi.bootstrap(workspaceName)
            null
        } catch (error: ApiException) {
            when {
                // Rules refused the lock: this account already owns a different tenant, or someone
                // else owns the one it tried to claim. Whatever it owns is authoritative.
                error.ownerOnly ->
                    if (SupportApi.resolveTenantId(forceRefresh = true).isNullOrBlank()) {
                        "This account cannot create a workspace. Sign in with the owner account."
                    } else {
                        null
                    }

                else -> error.message
            }
        } catch (failure: Throwable) {
            failure.localizedMessage ?: "Could not finish setting up your workspace."
        }
    }
}
