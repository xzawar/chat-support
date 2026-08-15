package com.codexce.supportchat.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.codexce.supportchat.data.SupportRepository
import com.codexce.supportchat.data.model.Conversation
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.ErrorBanner
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.PersonAvatar
import com.codexce.supportchat.ui.components.PlainRow
import com.codexce.supportchat.ui.components.StatusPill
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.theme.GroupedCardShape
import com.codexce.supportchat.ui.theme.TgRedDark
import com.codexce.supportchat.viewmodel.ConversationViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The visitor profile.
 *
 * The avatar is centred, with the name, email and status stacked under it - the portrait
 * arrangement this screen had before it was flattened into a left-aligned identity row.
 * Everything below the portrait is Accounts vocabulary and nothing else: grouped rounded cards,
 * label left, value right, hairlines between rows, no icons, no sub-headings.
 *
 * Chat, Keep and Lock are chips rather than rows. A row with a chevron promises a page to open,
 * and two of these three are toggles that change a value and stay here.
 *
 * Lock is the conversation status underneath - the same single write. A separate lock field in
 * Realtime Database would produce a payload the security rules reject outright.
 */
@Composable
fun VisitorProfileScreen(
    conversationId: String,
    agentUid: String?,
    repository: SupportRepository,
    onBack: () -> Unit,
) {
    if (agentUid == null) {
        EmptyState(
            icon = AppIcons.Lock,
            title = "Signed out",
            message = "Sign in again to view this visitor.",
        )
        return
    }

    val viewModel: ConversationViewModel = viewModel(
        key = "profile-$conversationId-$agentUid",
        factory = viewModelFactory {
            initializer { ConversationViewModel(repository, conversationId, agentUid) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversation = state.conversation

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // No header at all, not even a back arrow: the Chat chip below already returns to the
        // conversation, and two controls doing one job is one too many.
        Spacer(Modifier.height(28.dp))

        if (conversation == null) {
            EmptyState(
                icon = AppIcons.Person,
                title = "Conversation not found",
                message = "It may have been deleted, or the 24-hour retention window has passed.",
            )
            return@Column
        }

        state.error?.let { message ->
            ErrorBanner(message = message, onDismiss = viewModel::dismissError)
        }

        // Portrait header: avatar centred, identity stacked beneath it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PersonAvatar(
                name = conversation.visitorName,
                email = conversation.visitorEmail,
                seed = conversation.id,
                size = 96.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = conversation.visitorName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (conversation.visitorEmail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = conversation.visitorEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            StatusPill(
                text = when {
                    conversation.isPending -> "Waiting"
                    conversation.isClosed -> "Locked"
                    else -> "Active"
                },
                emphasised = conversation.isPending,
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(GroupedCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionChip(
                icon = AppIcons.ChatOutline,
                label = "Chat",
                tint = MaterialTheme.colorScheme.primary,
                onClick = debounced(onBack),
            )
            ActionChip(
                icon = AppIcons.SaveBookmark,
                label = if (conversation.keepChat) "Kept" else "Keep",
                tint = if (conversation.keepChat) {
                    SavedAmber
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = debounced { viewModel.setKeepChat(!conversation.keepChat) },
            )
            ActionChip(
                icon = if (conversation.isClosed) AppIcons.Lock else AppIcons.Unlock,
                label = if (conversation.isClosed) "Unlock" else "Lock",
                tint = if (conversation.isClosed) {
                    TgRedDark
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = debounced(viewModel::toggleClosed),
            )
        }

        GroupGap()

        /*
         * Facts. Blank values are dropped rather than shown as "Not provided": this page lists
         * what the widget actually reported, and a column of empty placeholders reads as a form
         * waiting to be filled in.
         */
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(GroupedCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            var drawn = false

            @Composable
            fun fact(label: String, value: String) {
                if (value.isBlank() || value == "Unknown") return
                if (drawn) Hairline()
                drawn = true
                PlainRow(title = label, value = value)
            }

            fact("Email", conversation.visitorEmail)
            fact("Page", conversation.pageUrl)
            fact("Country", conversation.country)
            fact("Device", conversation.userAgent)
            fact("First seen", formatFullDate(conversation.createdAt))
            fact("Last activity", formatFullDate(conversation.lastActivityAt))
            if (conversation.startedAt > 0) {
                fact("Chat started", formatFullDate(conversation.startedAt))
            }
            fact("Retention", retentionSubtitle(conversation))
        }

        Text(
            text = if (conversation.isClosed) {
                "Locked. The visitor cannot send anything until it is unlocked."
            } else {
                "Unlocked. New messages notify the assigned agent."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        Spacer(Modifier.height(32.dp))
    }
}

/** One of the three actions. Equal width, glyph over label, no chevron. */
@Composable
private fun RowScope.ActionChip(
    @DrawableRes icon: Int,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Row separator inside an icon-less card. Indented to the text, not to a missing tile. */
@Composable
private fun Hairline() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The countdown is derived from the same last-activity timestamp the scheduled Cloud Function
 * reads, so what the agent is told here and what the server actually does cannot drift.
 */
private fun retentionSubtitle(conversation: Conversation): String {
    if (conversation.keepChat) return "Exempt from the 24-hour delete"
    val remaining = conversation.expiresAt - System.currentTimeMillis()
    if (conversation.expiresAt <= 0L) return "Deleted 24 hours after the last message"
    if (remaining <= 0L) return "Due for deletion on the next run"
    val hours = TimeUnit.MILLISECONDS.toHours(remaining)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
    return if (hours > 0) "Deletes in ${hours}h ${minutes}m" else "Deletes in ${minutes}m"
}

private fun formatFullDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Unknown"
    return SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
}

// Matches the saved-state amber used by the save glyph elsewhere.
private val SavedAmber = Color(0xFFF5C518)
