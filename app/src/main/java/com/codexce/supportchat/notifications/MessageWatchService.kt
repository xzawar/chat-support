package com.codexce.supportchat.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.codexce.supportchat.MainActivity
import com.codexce.supportchat.R
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.data.DATABASE_URL
import com.codexce.supportchat.data.TenantSession
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * The connection that does not wait for FCM.
 *
 * This is the part that was missing. FCM is a courier: the site writes a message, the server
 * wakes up, mints a token, calls Google, and Google eventually reaches the phone. Every one of
 * those hops can stall, and the phone can be dozing at the end of it.
 *
 * A messenger that feels instant does not work that way. It keeps its own socket open and reads
 * messages off it the moment they arrive - which is exactly why this app is already instant while
 * you are looking at it. The Realtime Database listener is that socket. The only reason it stops
 * being instant is that Android tears the process down once the app leaves the screen.
 *
 * A foreground service is the officially sanctioned way to say "do not tear this down". It costs
 * a permanent low-priority notification, which is the same trade every always-connected app on
 * Android makes. With it, a visitor message reaches the phone over the socket in a few hundred
 * milliseconds whether the app is open, backgrounded or swiped away, and FCM becomes a backstop
 * rather than the primary path.
 */
class MessageWatchService : Service() {

    private var root: DatabaseReference? = null
    private var listener: ChildEventListener? = null
    private var watchedRef: DatabaseReference? = null

    /** Highest lastMessage timestamp already notified, per conversation. */
    private val notified = mutableMapOf<String, Long>()

    /**
     * Anything already in the database when the service starts is history, not news. Without
     * this, every restart would replay the whole inbox as notifications.
     */
    private var startedAt = 0L

    private val main = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        goForeground()
        attach()
        watchConnectivity()
    }

    /**
     * Firebase already retries with its own backoff, so this is the smaller of the two wins: it
     * stops a listener sitting on a dead socket during a long outage, and reattaches the moment
     * the network is back instead of waiting out the backoff.
     *
     * Callbacks arrive on a binder thread, so the listener work is posted to main.
     */
    private fun watchConnectivity() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { if (listener == null) attach() }
            }

            override fun onLost(network: Network) {
                main.post { detach() }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    /** START_STICKY: if the system reclaims us under memory pressure, come back. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        detach()
        super.onDestroy()
    }

    /**
     * Resolve which tenant node to watch.
     *
     * THIS IS THE BUG THAT KILLED BACKGROUND NOTIFICATIONS. This used to read
     * `result.claims["tenantId"]` off the ID token. That was correct under the old Express
     * backend, which minted custom claims when it provisioned a tenant. The Firebase-only
     * rewrite deleted the server, and with it the only thing that could ever set a custom
     * claim - ownership is now proved by `tenants/{id}.ownerUid == auth.uid` and nothing else.
     *
     * So the claim has been permanently absent since that migration. Every start of this
     * service read null, took the "not provisioned" branch and called stopSelf() within a
     * second or two. The foreground notification appeared and vanished, the socket was never
     * opened, and no message notification could ever fire - which is exactly what was reported
     * and why toggling "stay connected" changed nothing: the toggle was working, the thing it
     * started was quitting on its own.
     *
     * The tenant id now comes from the same place the rest of the app gets it: the session
     * that SupportApi persists after tenantsMe(). It survives a process death, so a service
     * restarted by START_STICKY or by BootReceiver can resolve it with no network at all.
     * Falling back to a Firestore query covers the one case prefs cannot - a boot before the
     * app has ever run in this install.
     *
     * Safety is unchanged: the database rules still reject any node whose ownerUid is not this
     * user, so a wrong id here fails closed rather than leaking anything.
     */
    private fun attach() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            // Signed out: nothing to watch, and no permission to read anything either.
            stopSelf()
            return
        }

        TenantSession.attach(this)
        TenantSession.restore(this, user.uid)
        val cached = TenantSession.tenantId
        if (!cached.isNullOrBlank()) {
            subscribe(cached)
            return
        }

        /*
         * Cold install or cleared storage. One indexed query, once per service start, then the
         * socket takes over - this is not a poll.
         */
        FirebaseFirestore.getInstance()
            .collection("tenants")
            .whereEqualTo("ownerUid", user.uid)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                val tenantId = snapshot.documents.firstOrNull()?.id
                if (tenantId.isNullOrBlank()) {
                    // Genuinely not provisioned. Idling here would hold a foreground
                    // notification up for nothing.
                    stopSelf()
                } else {
                    main.post { subscribe(tenantId) }
                }
            }
            .addOnFailureListener { stopSelf() }
    }

    private fun subscribe(tenantId: String) {
        if (listener != null) return

        val database = FirebaseDatabase.getInstance(DATABASE_URL)
        val reference = database.reference
        root = reference

        val conversations = reference.child("chats").child(tenantId).child("conversations")
        // Holds the subtree warm so the socket stays subscribed rather than idling out.
        conversations.keepSynced(true)

        val childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previous: String?) =
                consider(snapshot)

            override fun onChildChanged(snapshot: DataSnapshot, previous: String?) =
                consider(snapshot)

            override fun onChildRemoved(snapshot: DataSnapshot) {
                notified.remove(snapshot.key ?: return)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previous: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                // Usually a rules rejection or a signed-out account. Retrying would spin.
                stopSelf()
            }
        }

        conversations.addChildEventListener(childListener)
        listener = childListener
        watchedRef = conversations
    }

    private fun detach() {
        val current = listener
        val reference = watchedRef
        if (current != null && reference != null) reference.removeEventListener(current)
        listener = null
        watchedRef = null
        root = null
    }

    /**
     * Decides whether a conversation change is a new inbound message worth announcing.
     *
     * Three guards, because this fires on every field change including our own writes:
     * the message must be unread by agents, newer than this service, and newer than whatever
     * was last announced for that conversation.
     */
    private fun consider(snapshot: DataSnapshot) {
        val conversationId = snapshot.key ?: return

        // The RTDB schema writes "unread"; the older shape used "unreadForAgents". Both are read
        // so a device that has not resynced yet still notifies correctly.
        val unread = snapshot.child("unread").getValue(Long::class.java)
            ?: snapshot.child("unreadForAgents").getValue(Long::class.java)
            ?: 0
        if (unread <= 0) return

        /*
         * Only announce what arrived after this service did.
         *
         * This was `at < startedAt`, which silently swallowed the most valuable notification
         * of all: a message that lands in the same second the service starts (app swiped away,
         * service restarted, visitor already typing) compared equal and was dropped. A small
         * grace window fixes that without replaying the inbox, which is what the guard is
         * actually for.
         */
        val at = snapshot.child("lastMessage/at").getValue(Long::class.java) ?: 0
        if (at <= 0 || at < startedAt - STARTUP_GRACE_MILLIS) return
        if (at <= (notified[conversationId] ?: 0)) return

        val text = snapshot.child("lastMessage/text").getValue(String::class.java).orEmpty()
        if (text.isBlank()) return

        val name = snapshot.child("visitor/name").getValue(String::class.java) ?: "Visitor"

        notified[conversationId] = at
        PushNotifications.show(
            context = this,
            conversationId = conversationId,
            title = name,
            body = text,
        )
    }

    /** The permanent, silent notification that buys the process its right to stay alive. */
    private fun goForeground() {
        ensureServiceChannel(this)

        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_chat_filled)
            .setContentTitle("Support Chat is connected")
            .setContentText("Watching for new messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()

        /*
         * From Android 14 a foreground service must declare why it exists. specialUse is the
         * honest answer for an always-connected client, and unlike dataSync it is not capped at
         * six hours a day on Android 15.
         */
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val SERVICE_CHANNEL_ID = "support_connection"
        private const val NOTIFICATION_ID = 4711

        /** How far back a message may be and still count as news at service start. */
        private const val STARTUP_GRACE_MILLIS = 15_000L

        /** Silent and collapsed, so the ongoing notice sits at the bottom of the shade. */
        fun ensureServiceChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Connection",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps the app connected so messages arrive instantly"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        /** Safe to call repeatedly; starting a running service just re-delivers onStartCommand. */
        fun start(context: Context) {
            if (FirebaseAuth.getInstance().currentUser == null) return
            if (!AppPreferences.get(context).keepConnectedAtStartup) return
            val intent = Intent(context, MessageWatchService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MessageWatchService::class.java)) }
        }
    }
}
