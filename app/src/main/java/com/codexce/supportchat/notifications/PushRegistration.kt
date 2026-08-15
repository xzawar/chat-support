package com.codexce.supportchat.notifications

import com.codexce.supportchat.data.api.SupportApi
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Token lifecycle outside of onNewToken: the current token also has to be fetched and stored on
 * every sign-in, because onNewToken only fires when a token is first created or rotates. A user
 * signing in on a device that already holds a token would otherwise never be registered.
 *
 * 4.2 routes this through POST /v1/devices instead of writing owners/{uid}/devices directly.
 * The backend files the token under tenants/{tenantId}/devices/{deviceId}, which is exactly where
 * the notification functions look for it, and the tenant comes from the verified token rather
 * than from anything this device claims about itself.
 */
object PushRegistration {

    suspend fun register(deviceId: String): String? = try {
        val token = FirebaseMessaging.getInstance().token.await()
        SupportApi.registerDevice(deviceId, token)
        null
    } catch (failure: Throwable) {
        failure.localizedMessage ?: "Could not register for push notifications"
    }

    /** Called before signing out, while the session token is still valid. */
    suspend fun unregister(deviceId: String): String? = try {
        SupportApi.unregisterDevice(deviceId)
        null
    } catch (failure: Throwable) {
        failure.localizedMessage
    }
}
