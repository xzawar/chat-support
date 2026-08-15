@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codexce.supportchat.notifications.BatteryOptimization
import com.codexce.supportchat.notifications.PushNotifications
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.SettingsRow
import com.codexce.supportchat.ui.components.ThinDivider
import com.codexce.supportchat.ui.theme.supportButtonColors

/**
 * True while anything still standing between a visitor message and a notification is unresolved.
 *
 * Autostart is deliberately excluded: there is no API that reports it, so including it would
 * make this function permanently true on the devices that have it.
 */
fun permissionSetupIncomplete(context: Context): Boolean =
    !PushNotifications.hasPermission(context) || !BatteryOptimization.isExempt(context)

/**
 * The one-time setup walk-through, shown straight after the first sign-in.
 *
 * Three separate things have to be true before a push arrives promptly, and only the first is a
 * normal runtime permission. The second is an exemption Android grants through its own dialog,
 * and the third lives in a manufacturer screen no API can read or write. So rather than firing
 * dialogs blindly, each is listed with its live state and its own action.
 */
@Composable
fun PermissionsScreen(
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    // Bumped whenever an answer might have changed, which re-reads every state below.
    var refresh by remember { mutableIntStateOf(0) }

    val notificationsGranted = remember(refresh) { PushNotifications.hasPermission(context) }
    val batteryExempt = remember(refresh) { BatteryOptimization.isExempt(context) }

    val notificationRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    /*
     * Both the battery dialog and the OEM autostart screen are other activities, so the only
     * moment this screen can learn the outcome is when it comes back to the foreground.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The one true runtime permission is asked for immediately, without waiting for a tap.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PushNotifications.hasPermission(context)
        ) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(48.dp))

            Text(
                text = "Never miss a message",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Android holds background apps back to save battery. " +
                    "Three quick approvals and messages arrive the moment a visitor sends them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(24.dp))

            GroupGap()
            SettingsRow(
                icon = AppIcons.Bell,
                title = "Show notifications",
                subtitle = if (notificationsGranted) {
                    "Allowed"
                } else {
                    "Not allowed yet - tap to grant"
                },
                onClick = debounced {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Pre-13 there is no runtime permission; the app settings page is the switch.
                        BatteryOptimization.openAppDetails(context)
                    }
                },
                trailing = { if (notificationsGranted) GrantedTick() },
            )
            ThinDivider()
            SettingsRow(
                icon = AppIcons.Cloud,
                title = "Ignore battery optimisation",
                subtitle = if (batteryExempt) {
                    "Unrestricted"
                } else {
                    "Restricted - messages may be delayed"
                },
                onClick = debounced { BatteryOptimization.requestExemption(context) },
                trailing = { if (batteryExempt) GrantedTick() },
            )
            ThinDivider()

            GroupGap()
            SettingsRow(
                icon = AppIcons.Lock,
                title = "Autostart",
                subtitle = "Xiaomi, Oppo, Vivo and Huawei stop apps that lack this",
                onClick = debounced {
                    if (!BatteryOptimization.openAutostart(context)) {
                        BatteryOptimization.openAppDetails(context)
                    }
                },
            )
            ThinDivider()

            Spacer(Modifier.height(28.dp))

            Button(
                colors = supportButtonColors(),
                onClick = debounced(onDone),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(52.dp),
            ) {
                Text(if (notificationsGranted && batteryExempt) "Done" else "Continue anyway")
            }

            TextButton(
                onClick = debounced(onDone),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Text("Remind me later")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GrantedTick() {
    Icon(
        painter = painterResource(AppIcons.Check),
        contentDescription = "Granted",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
    )
}
