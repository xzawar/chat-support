@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.data.local.SupportDatabase
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.InfoBox
import com.codexce.supportchat.ui.components.PlainRow
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.theme.GroupedCardShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The three pages behind the Storage and data rows.
 *
 * All three were previously captions on dead rows. A row that describes a thing and does
 * nothing when tapped is worse than no row: it reads as a broken control.
 */

/**
 * What is actually on this device, read straight from Room.
 *
 * This is the cache itself, not a summary of it. The list is the same table the inbox renders
 * from when it opens offline, so if a thread appears here it will open without a network round
 * trip, and if it does not appear here it will not.
 */
@Composable
fun CachedConversationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Application context, and remembered: Room's builder is synchronised and cheap to call
    // twice, but holding an Activity in a DAO reference outlives the Activity.
    val dao = remember { SupportDatabase.get(context.applicationContext).supportDao() }
    val rows by dao.observeConversations().collectAsStateWithLifecycle(initialValue = emptyList())

    DetailScaffold(title = "Cached conversations", onBack = onBack) {
        if (rows.isEmpty()) {
            EmptyState(
                icon = AppIcons.Database,
                title = "Nothing cached yet",
                message = "Threads are stored here as you open them, so the inbox works " +
                    "without a connection.",
            )
            return@DetailScaffold
        }

        GroupGap()
        Text(
            text = rows.size.toString() + (if (rows.size == 1) " thread" else " threads") +
                " stored on this device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(GroupedCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        thickness = Dp.Hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                PlainRow(
                    title = row.visitorName.ifBlank { "Visitor" },
                    value = shortDate(row.lastAt),
                )
            }
        }

        GroupGap()
        InfoBox(
            text = "Clearing the cache on the previous page empties this list. No conversation " +
                "is deleted by doing so - each one downloads again when you open it.",
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Where the data lives, in plain words.
 *
 * The point of this page is to be reassuring by being specific. Vague privacy copy reads as
 * evasive; naming the two databases, the retention rule and who can read what is what actually
 * earns the trust.
 */
@Composable
fun DataLocationScreen(onBack: () -> Unit) {
    DetailScaffold(title = "Where your data lives", onBack = onBack) {
        GroupGap()
        InfoBox(
            title = "Your own Firebase project",
            text = "Nothing is stored on Keykraft servers. Messages, visitors and plan records " +
                "all sit in the Firebase project attached to your workspace, under your " +
                "Google account and your billing.",
        )

        GroupGap()
        InfoBox(
            title = "Two databases, one workspace",
            text = "Live chat messages are held in Realtime Database so they arrive instantly. " +
                "Visitor emails, linked websites and subscription details are held in " +
                "Firestore. Both are scoped to your workspace id, and the security rules " +
                "reject a read from any account that is not yours.",
        )

        GroupGap()
        InfoBox(
            title = "Chats are deleted for you",
            text = "A conversation and its messages are removed 24 hours after the last " +
                "message, automatically, unless you mark the chat as kept. Keeping less is " +
                "the point: data you no longer hold cannot leak.",
        )

        GroupGap()
        InfoBox(
            title = "What visitors are asked for",
            text = "A name and an email address, and only when they offer them. The widget " +
                "also records the page they were on so you know what they were reading. No " +
                "tracking pixels, no advertising identifiers, no third-party analytics.",
        )

        GroupGap()
        InfoBox(
            title = "On this phone",
            text = "Recent threads are cached locally so the inbox opens instantly. That copy " +
                "lives in the app's private storage, which no other app can read, and it is " +
                "erased when you clear the cache or uninstall.",
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Export, which is a conversation with us rather than a button.
 *
 * An export worth having has to be assembled server side across two databases, and a button
 * that produced a partial file would be worse than none. So this page says who to ask and what
 * you will get, and hands off to email with the subject already written.
 */
@Composable
fun ExportCopyScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    DetailScaffold(title = "Export a copy", onBack = onBack) {
        GroupGap()
        InfoBox(
            title = "Ask us and we will build it",
            text = "Your records span two databases, so an export is put together for you " +
                "rather than generated on the phone. Email Keykraft from the address you " +
                "sign in with and the copy is prepared for your workspace.",
        )

        GroupGap()
        InfoBox(
            title = "What you get",
            text = "Every conversation still inside the 24-hour window plus everything you " +
                "marked as kept, your full visitor and lead list, your linked websites and " +
                "your subscription history, as machine-readable files.",
        )

        GroupGap()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(GroupedCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            PlainRow(
                title = "Email Keykraft",
                value = "info@keykraftt.com",
                onClick = debounced {
                    // Wrapped: a device with no mail client would otherwise throw
                    // ActivityNotFoundException and take the screen down with it.
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "mailto:info@keykraftt.com?subject=" +
                                        Uri.encode("Export a copy of my workspace data"),
                                ),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }

        GroupGap()
        InfoBox(
            text = "Requests are answered within one working day. There is no ticket number " +
                "to quote - the address you write from identifies your workspace.",
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** The shared frame: same top bar, same scroll, same background as every other pushed page. */
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
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
            content = content,
        )
    }
}

private fun shortDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
