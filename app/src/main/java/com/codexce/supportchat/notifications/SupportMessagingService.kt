package com.codexce.supportchat.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.codexce.supportchat.MainActivity
import com.codexce.supportchat.R
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.data.api.SupportApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PushNotifications {
    const val CHANNEL_ID = "support_messages"
    const val EXTRA_CONVERSATION_ID = "conversationId"

    /**
     * The conversation currently on screen, or null.
     *
     * Volatile because it is written from the main thread by ChatScreen and read from whichever
     * thread FCM or the watch service happens to deliver on.
     */
    @Volatile
    private var activeConversationId: String? = null

    fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    /**
     * Dismisses any notification for this conversation.
     *
     * It has to cancel twice, and this is the part that is easy to get wrong. Notifications the
     * app draws itself use conversationId.hashCode() as the id. Notifications the system draws
     * from the plugin payload arrive with the conversation id as a *tag* and an id of 0, because
     * the server sets android.notification.tag. Cancelling only the first leaves plugin-sent
     * notifications stuck in the shade after the chat has been read.
     */
    fun clearFor(context: Context, conversationId: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(conversationId.hashCode())
            manager.cancel(conversationId, 0)
        }
    }

    /** Required from API 26 on; creating it twice is a no-op, so this is safe to call anywhere. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New messages from visitors"
            enableVibration(true)
            setShowBadge(true)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Posts a message notification that opens the conversation directly rather than the launch
     * screen: the conversation id travels in the Intent and MainActivity routes on it.
     */
    /**
     * conversationId is nullable on purpose. A push that does not name a thread is still worth
     * showing; it just opens the app at the inbox instead of deep linking into a conversation.
     * Dropping those was why only replies ever popped up.
     */
    fun show(
        context: Context,
        conversationId: String?,
        title: String,
        body: String,
    ) {
        ensureChannel(context)
        if (!hasPermission(context)) return
        // Already reading this thread: a notification for it would be noise. The system only
        // auto-draws a payload when the app is backgrounded, so if we are here with the chat
        // open, the decision is ours to make. A push with no thread attached is never suppressed
        // by this rule, because it cannot be the thread on screen.
        if (conversationId != null && conversationId == activeConversationId) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        // Untargeted pushes must not share one request code, or each would overwrite the last
        // one's PendingIntent. Their tray slot is unique too, so two of them can coexist.
        val notificationId = conversationId?.hashCode() ?: UNTARGETED_ID_SEQUENCE.getAndIncrement()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // The same face the inbox shows for this conversation: disc colour from the id, white
        // letter from the sender name.
        val face = runCatching {
            conversationId?.let { AvatarBitmap.forPerson(title, it) }
        }.getOrNull()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_chat_filled)
            .setLargeIcon(face)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId, notification)
        }
    }

    /** Tray ids for pushes that name no conversation. Kept well clear of real hash codes. */
    private val UNTARGETED_ID_SEQUENCE = java.util.concurrent.atomic.AtomicInteger(900_000)
}

/**
 * FCM entry point.
 *
 * onNewToken hands the token to POST /v1/devices, keyed by a stable per-install id so a rotated
 * token replaces the old one instead of accumulating dead entries. The device row is filed by the
 * backend under the tenant in the verified claims; this process never writes a database itself.
 *
 * onMessageReceived posts the notification itself. A data message, or any message that arrives
 * while the app is in the foreground, is never displayed automatically by the system.
 */
class SupportMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Signed out there is no token to attach the call to, and the endpoint would reject it.
        FirebaseAuth.getInstance().currentUser ?: return
        val deviceId = AppPreferences.get(this).deviceId
        scope.launch { runCatching { SupportApi.registerDevice(deviceId, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data

        // Only some notifications used to appear. The cause was this line, which used to read
        //
        //     val conversationId = data["conversationId"] ?: return
        //
        // Any payload whose data map did not carry that exact key was thrown away in silence.
        // Replies carry it because they are sent from an open thread; new-chat, queue, handoff
        // and system pushes do not, so those never reached the tray at all. Nothing about them
        // was broken -- they were simply discarded here. Now a missing id only costs us deep
        // linking, not the notification itself, and several spellings are accepted because the
        // sender is not always the same code path.
        val conversationId = data["conversationId"]
            ?: data["conversation_id"]
            ?: data["chatId"]
            ?: data["threadId"]

        val title = data["senderName"]
            ?: data["title"]
            ?: message.notification?.title
            ?: "New message"
        val body = data["text"]
            ?: data["body"]
            ?: data["message"]
            ?: message.notification?.body
            ?: "You have a new message"

        PushNotifications.show(
            context = this,
            conversationId = conversationId,
            title = title,
            body = body,
        )
    }
}
