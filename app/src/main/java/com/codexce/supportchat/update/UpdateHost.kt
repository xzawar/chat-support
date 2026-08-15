package com.codexce.supportchat.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.data.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole update feature's UI, as one composable with no route of its own.
 *
 * It is mounted next to the nav graph rather than inside it because an update prompt is not a
 * destination: it must be able to appear over the inbox, over a chat, or over the login screen,
 * and it must not be something the back stack can navigate to or away from.
 *
 * Nothing here runs before the app is usable. The check is delayed past the first frames and the
 * whole thing renders nothing at all in the common case, so the cost on a cold start where no
 * update exists is one background GET and no composition.
 */
@Composable
fun UpdateHost(preferences: AppPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var manifest by remember { mutableStateOf<UpdateManifest?>(null) }
    var downloading by remember { mutableStateOf(false) }
    /** Null means the server sent no Content-Length, so the bar has to be indeterminate. */
    var progress by remember { mutableStateOf<Float?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }

    /*
     * rememberSaveable, so a configuration change does not re-run the check.
     *
     * A plain remember is reset when the activity is recreated — a rotation, a theme change, a
     * font-size change in system settings — and the prompt would reappear having just been
     * dismissed. Surviving that is exactly what saveable is for.
     */
    var alreadyChecked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (alreadyChecked) return@LaunchedEffect
        alreadyChecked = true
        /*
         * Deliberately late.
         *
         * Startup is already contended: Firebase is restoring its session, the tenant claims are
         * resolving and the graph is composing. Adding a socket to that window would take
         * bandwidth from the work the user is waiting on, to answer a question that is almost
         * always "no update". Two seconds after first frame costs nothing and is invisible.
         */
        delay(STARTUP_CHECK_DELAY_MS)
        val result = UpdateChecker.check(context)
        if (result is UpdateChecker.Result.Available) manifest = result.manifest
    }

    val pending = manifest ?: return

    val dismiss: () -> Unit = {
        // Remembering the declined version is what stops Later meaning "ask me again in a minute".
        preferences.setSkippedUpdateVersion(pending.versionCode)
        manifest = null
    }

    AlertDialog(
        // A mandatory update has no dismiss path: no outside tap, no back press, no Later button.
        onDismissRequest = { if (!pending.mandatory && !downloading) dismiss() },
        title = { Text(if (downloading) "Downloading update" else "Update available") },
        text = {
            Column {
                Text(
                    text = "Version ${pending.versionName}" +
                        (pending.sizeBytes?.let { " \u00b7 ${formatSize(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                pending.changelog?.let { notes ->
                    Spacer(Modifier.height(8.dp))
                    Text(text = notes, style = MaterialTheme.typography.bodySmall)
                }
                if (needsPermission) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Android needs permission to install apps from Support Chat. " +
                            "Allow it on the screen that opens, then tap Update again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                failure?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(16.dp))
                    val fraction = progress
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!downloading) {
                TextButton(
                    onClick = {
                        failure = null

                        // Asked for before the download, not after it: a 25MB transfer that ends
                        // at a permission wall the user then declines is 25MB wasted.
                        if (!UpdateInstaller.canInstall(context)) {
                            needsPermission = true
                            UpdateInstaller.requestInstallPermission(context)
                            return@TextButton
                        }

                        needsPermission = false
                        downloading = true
                        progress = null

                        scope.launch {
                            val outcome = UpdateInstaller.download(context, pending) { fraction ->
                                progress = fraction
                            }
                            downloading = false
                            when (outcome) {
                                is UpdateInstaller.Outcome.Ready -> {
                                    /*
                                     * The prompt is closed WITHOUT recording a skip.
                                     *
                                     * The installer is a separate process and the user can still
                                     * back out of it. Recording a skip here would mean a declined
                                     * install silences this version forever; leaving it unrecorded
                                     * means the next launch offers it again, which is correct.
                                     */
                                    manifest = null
                                    if (!UpdateInstaller.install(context, outcome.file)) {
                                        manifest = pending
                                        failure = "Could not open the installer."
                                    }
                                }

                                is UpdateInstaller.Outcome.Failed -> failure = outcome.reason
                            }
                        }
                    },
                ) {
                    Text(if (failure == null) "Update" else "Try again")
                }
            }
        },
        dismissButton = {
            if (!pending.mandatory && !downloading) {
                TextButton(onClick = dismiss) { Text("Later") }
            }
        },
    )
}

private const val STARTUP_CHECK_DELAY_MS = 2_000L

/** Binary units, because that is what a file manager will report for the same APK. */
private fun formatSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1.0) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
}
