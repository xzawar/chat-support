@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.GroupDivider
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.SettingsGroup
import com.codexce.supportchat.ui.components.SettingsRow
import com.codexce.supportchat.ui.components.debounced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/*
 * Storage and data.
 *
 * Now built out of SettingsGroup and SettingsRow, the same two pieces the Settings page uses,
 * so the cards, insets and dividers are not merely similar to that page - they are the same
 * components. It previously used loose rows and ThinDivider, which is why it read as a
 * different screen.
 *
 * Every row here goes somewhere. Cached conversations, Where your data lives and Export a copy
 * were all dead rows that described something instead of doing it; each now pushes a real page.
 *
 * Two removals. Stay connected has gone: it is a notification and battery setting, and it was
 * only here because this page existed before Settings had somewhere better for it. Delete
 * account and workspace has moved to the Accounts page, where deleting an account belongs and
 * where it is not one row away from Clear cache.
 */
@Composable
fun StorageDataScreen(
    onBack: () -> Unit,
    onOpenCached: () -> Unit,
    onOpenDataLocation: () -> Unit,
    onOpenExport: () -> Unit,
) {
    val context = LocalContext.current

    /*
     * Measured, not guessed. The cache directory is walked on a background dispatcher because
     * File.length() on every entry is a real filesystem call, and doing that on the main thread
     * is exactly the kind of thing that shows up as a dropped frame when a screen opens.
     */
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var recount by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(recount) {
        cacheBytes = withContext(Dispatchers.IO) { directorySize(context.cacheDir) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Storage and data", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState()),
        ) {
            GroupGap()
            SettingsGroup {
                SettingsRow(
                    icon = AppIcons.Sweep,
                    title = "Clear cache",
                    tint = TintCacheOrange,
                    onClick = debounced { confirmClear = true },
                    trailing = {
                        Text(
                            text = cacheBytes?.let { formatBytes(it) } ?: "\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.Database,
                    title = "Cached conversations",
                    tint = TintDataPlum,
                    onClick = debounced(onOpenCached),
                )
            }

            GroupGap()
            SettingsGroup {
                SettingsRow(
                    icon = AppIcons.Shield,
                    title = "Where your data lives",
                    tint = TintPrivacyBlue,
                    onClick = debounced(onOpenDataLocation),
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.Download,
                    title = "Export a copy",
                    tint = TintExportTeal,
                    onClick = debounced(onOpenExport),
                )
            }

            Text(
                text = "Clearing the cache never deletes a conversation. Anything removed here " +
                    "is downloaded again the next time you open the thread.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cache?") },
            text = {
                Text(
                    "Frees " + (cacheBytes?.let { formatBytes(it) } ?: "space") +
                        ". Your conversations are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = debounced {
                    clearCache(context)
                    confirmClear = false
                    recount++
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = debounced { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

/** Recursive walk. Returns 0 for a missing directory rather than throwing. */
private fun directorySize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    var total = 0L
    val children = dir.listFiles() ?: return 0L
    for (child in children) {
        total += if (child.isDirectory) directorySize(child) else child.length()
    }
    return total
}

/*
 * deleteRecursively can fail part way through if the image loader is writing at the same moment.
 * That is not worth surfacing: whatever was not deleted this time is deleted next time, and the
 * size is re-measured immediately afterwards so the row tells the truth either way.
 */
private fun clearCache(context: Context) {
    runCatching { context.cacheDir?.deleteRecursively() }
    runCatching { context.externalCacheDir?.deleteRecursively() }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

// Same jewel-tone family as the Settings list, kept local so the two files stay independent.
private val TintCacheOrange = Color(0xFFD97706)
private val TintDataPlum = Color(0xFF5B3A72)
private val TintPrivacyBlue = Color(0xFF1D6FE0)
private val TintExportTeal = Color(0xFF0E8F86)
