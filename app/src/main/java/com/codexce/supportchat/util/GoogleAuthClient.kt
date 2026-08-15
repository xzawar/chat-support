package com.codexce.supportchat.util

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Wraps Credential Manager so the ViewModel only ever deals with a Google ID token string.
 *
 * Credential Manager replaces the deprecated GoogleSignInClient / startActivityForResult flow.
 * The bottom sheet it shows is drawn by Play services, so it must be launched from an Activity
 * context - an application context throws at runtime.
 *
 * Two request shapes are used, in order, and the fallback is the important part:
 *
 *  1. GetGoogleIdOption is the quiet one. It shows the account sheet only when Credential Manager
 *     already knows about a usable account, and throws NoCredentialException otherwise - which is
 *     what produced the "no Google account on your device" message even on phones that plainly had
 *     one signed in.
 *  2. GetSignInWithGoogleOption is the explicit button flow. It always opens the full picker,
 *     including the "Add another account" entry, so the user can sign in from scratch.
 *
 * Note: GetSignInWithGoogleOption is written against the documented googleid 1.1.1 API but has
 * never been compiled here, since this sandbox has no Android SDK.
 */
object GoogleAuthClient {

    private const val TAG = "GoogleAuthClient"

    /**
     * @param serverClientId the OAuth **web** client id (client_type 3) from google-services.json,
     *   not the Android client id. Passing the Android one fails with a developer error.
     */
    suspend fun requestIdToken(context: Context, serverClientId: String): String {
        val manager = CredentialManager.create(context)

        Log.d(TAG, "requestIdToken: trying quiet GetGoogleIdOption")

        val quiet = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            // false so accounts that have never used this app are offered too, otherwise the
            // sheet is empty on a fresh install and looks broken.
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val credential = try {
            manager.getCredential(
                context,
                GetCredentialRequest.Builder().addCredentialOption(quiet).build(),
            ).credential
        } catch (none: NoCredentialException) {
            // Nothing matched the quiet request. Fall through to the explicit picker rather than
            // telling the user they have no account.
            Log.d(TAG, "quiet flow found nothing, opening explicit picker")
            val explicit = GetSignInWithGoogleOption.Builder(serverClientId).build()
            manager.getCredential(
                context,
                GetCredentialRequest.Builder().addCredentialOption(explicit).build(),
            ).credential
        }

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Log.d(TAG, "Google ID token received")
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }

        Log.w(TAG, "unexpected credential type: ${credential.type}")
        throw IllegalStateException("Unexpected credential type: ${credential.type}")
    }
}
