@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.data.DATABASE_URL
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.debounced
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One browser that has been granted access to this tenant. */
private data class LinkedDevice(
    val webUid: String,
    val label: String,
    val grantedAt: Long,
    val lastSeenAt: Long,
)

/**
 * Approves a browser that has displayed a Support Chat Web QR code, and lists the browsers that
 * are currently approved so any of them can be signed out again.
 *
 * The QR carries only a short pairing id and one-time secret; the browser remains anonymous, and
 * this phone grants that UID access to this tenant only. No account password or Firebase
 * credential is ever put in a QR.
 */
@Composable
fun LinkComputerScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val tenant by TenantSession.tenant.collectAsStateWithLifecycle()
    val tenantId = tenant?.tenantId
    var devices by remember { mutableStateOf<List<LinkedDevice>>(emptyList()) }

    /*
     * Live list of grants, straight off the node that actually controls access. Reading the
     * authoritative node rather than keeping a local copy means a browser revoked from another
     * phone disappears here too, and a link approved here appears without a refresh - which is
     * what makes the "device linked" confirmation below trustworthy rather than optimistic.
     */
    DisposableEffect(tenantId) {
        val id = tenantId
        if (id.isNullOrBlank()) {
            devices = emptyList()
            onDispose { }
        } else {
            val ref = FirebaseDatabase.getInstance(DATABASE_URL).reference
                .child("chats").child(id).child("sessions")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    devices = snapshot.children.mapNotNull { child ->
                        val uid = child.key ?: return@mapNotNull null
                        val row = child.value as? Map<*, *> ?: return@mapNotNull null
                        LinkedDevice(
                            webUid = uid,
                            label = row["label"] as? String ?: "Support Chat Web",
                            grantedAt = (row["grantedAt"] as? Number)?.toLong() ?: 0L,
                            lastSeenAt = (row["lastSeenAt"] as? Number)?.toLong() ?: 0L,
                        )
                    }.sortedByDescending { it.grantedAt }
                }

                // Silent on purpose. A read failure here means the list is empty, not that the
                // link the user just completed went wrong, and painting a red error next to a
                // successful pairing is the exact bug this screen is being fixed for.
                override fun onCancelled(errorSnapshot: DatabaseError) = Unit
            }
            ref.addValueEventListener(listener)
            onDispose { ref.removeEventListener(listener) }
        }
    }

    fun signOutDevice(device: LinkedDevice) {
        val id = tenantId ?: return
        scope.launch {
            try {
                // Removing the grant node is the revoke. Everything else is bookkeeping.
                FirebaseDatabase.getInstance(DATABASE_URL).reference
                    .child("chats").child(id).child("sessions").child(device.webUid)
                    .removeValue().await()
                result = "Signed out of ${device.label}."
                error = null
                runCatching {
                    FirebaseFirestore.getInstance()
                        .collection("tenants").document(id)
                        .collection("sessions").document(device.webUid)
                        .delete().await()
                }
            } catch (t: Throwable) {
                error = t.message ?: "Could not sign that computer out."
            }
        }
    }

    fun approve(raw: String) {
        val parts = raw.trim().split(":")
        if (parts.size != 3 || parts[0] != "sc1" || parts[1].length != 22 || parts[2].length != 24) {
            error = "This is not a Support Chat Web pairing code."
            return
        }
        val id = tenantId
        val ownerUid = FirebaseAuth.getInstance().currentUser?.uid
        if (id.isNullOrBlank() || ownerUid.isNullOrBlank()) {
            error = "Your workspace is still loading. Please try again in a moment."
            return
        }

        busy = true
        error = null
        scope.launch {
            try {
                val (sessionId, secret) = parts.drop(1)
                val root = FirebaseDatabase.getInstance(DATABASE_URL).reference
                val pairing = root.child("pairing").child(sessionId)
                val waiting = pairing.get().await().value as? Map<*, *>
                    ?: throw IllegalStateException("Code expired")
                val webUid = waiting["webUid"] as? String
                    ?: throw IllegalStateException("Code expired")
                val expiresAt = (waiting["expiresAt"] as? Number)?.toLong() ?: 0L
                if (waiting["status"] != "waiting" || webUid.isBlank() || expiresAt < System.currentTimeMillis()) {
                    throw IllegalStateException("This pairing code has expired. Refresh the web page for a new one.")
                }

                val now = System.currentTimeMillis()
                // Approval echoes the secret that the browser displayed. The browser checks this
                // before accepting the tenant id, preventing a guessed session id from linking.
                pairing.updateChildren(
                    mapOf(
                        "status" to "approved",
                        "tenantId" to id,
                        "secret" to secret,
                        "label" to "Support Chat Web",
                    ),
                ).await()
                root.child("chats").child(id).child("sessions").child(webUid).setValue(
                    mapOf("grantedAt" to now, "label" to "Support Chat Web", "lastSeenAt" to now),
                ).await()

                /*
                 * The link is complete at this point. Everything above is what actually grants
                 * access: the browser is watching the pairing node and the RTDB grant node, and
                 * neither of them knows or cares about Firestore.
                 *
                 * The Firestore write below is a mirror for reporting. Nothing in the app, the
                 * web console or the WordPress plugin ever reads it. It used to sit inside the
                 * same try block as the two writes above, so when the Firestore rules refused it
                 * - which they do - the throw landed in the catch and painted "Permission denied"
                 * over a pairing that had already succeeded. That is the bug: the error was real,
                 * the failure was not.
                 *
                 * runCatching, not a fixed rules file, because the mirror is genuinely optional.
                 * Making the success of a link depend on a collection no reader consumes would be
                 * inventing a dependency, and the rules are deployed separately from this app.
                 */
                runCatching {
                    FirebaseFirestore.getInstance()
                        .collection("tenants").document(id).collection("sessions").document(webUid)
                        .set(mapOf("grantedAt" to now, "label" to "Support Chat Web"))
                        .await()
                }

                error = null
                result = "Computer linked. The web console will open automatically."
            } catch (t: Throwable) {
                error = t.message ?: "Could not link this computer. Check your Firebase rules and try again."
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Link a computer") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Icon(
                painter = painterResource(AppIcons.Monitor),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Use Support Chat on your computer",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                "Open the Support Chat web console, then scan its QR code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
            )
            Button(enabled = !busy, onClick = debounced {
                error = null
                GmsBarcodeScanning.getClient(context).startScan()
                    .addOnSuccessListener { barcode -> barcode.rawValue?.let(::approve) ?: run { error = "No QR code was detected." } }
                    .addOnCanceledListener { }
                    .addOnFailureListener { error = it.message ?: "Scanner could not open." }
            }) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(" Linking…", Modifier.padding(start = 8.dp))
                } else Text("Scan QR code")
            }
            if (result != null) {
                Text(
                    result!!,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }

            if (devices.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                Text(
                    "Linked devices",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                devices.forEach { device ->
                    DeviceRow(device = device, onSignOut = { signOutDevice(device) })
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DeviceRow(device: LinkedDevice, onSignOut: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(AppIcons.Monitor),
            contentDescription = null,
            Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(device.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                describeDevice(device),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = debounced(onSignOut)) {
            Text("Log out", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Last seen is the useful line when the browser has checked in, because it answers "is this still
 * somebody". Falls back to when the grant was made, which is all that exists immediately after a
 * link and before the console has written its first heartbeat.
 */
private fun describeDevice(device: LinkedDevice): String = when {
    device.lastSeenAt > 0L -> "Last active ${formatStamp(device.lastSeenAt)}"
    device.grantedAt > 0L -> "Linked ${formatStamp(device.grantedAt)}"
    else -> "Linked"
}

private fun formatStamp(epochMillis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))
