package com.codexce.supportchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.data.model.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One inbox row.
 *
 * Two things changed here in this pass, and they compound:
 *
 * Phase 8.4 removed the Assign / Close / Reopen action strip that used to sit under the preview
 * on a 66dp indent. Assignment happens by itself when an agent hits Start Chat, and status is
 * managed on the visitor profile, so the strip had nothing left to do. Deleting it is most of
 * the Phase 9 compaction on its own - the row loses a whole line plus its spacing.
 *
 * Phase 9 then tightens what remains: the avatar drops 52dp to 42dp and the vertical padding
 * 12dp to 8dp. Net effect is roughly a 100dp row down to about 62dp.
 *
 * Phase 10 walks part of that back. The 28dp side gutters were eating a quarter of the screen
 * width, which is what made the row read as a narrow card floating in the middle of the list
 * rather than a full-width inbox line; they are now 16dp. The avatar goes back up to 48dp so
 * the name has something to sit against, and vertical padding to 10dp.
 *
 * The timestamp used to sit inside the text column, immediately after the status pill, so it
 * drifted left or right depending on how long the visitor's name was. It is now a sibling of
 * the column, pinned to the far right edge of the row, so it lines up down the whole list.
 *
 * Status is now one small pill next to the timestamp, shown only when it is worth saying.
 * "Unassigned" on every row was noise; Pending is a call to action and Closed is an explanation
 * for why a thread is read-only, so those two are all that survive.
 */
@Composable
fun ConversationRow(
    conversation: Conversation,
    isMine: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            // Opaque, so the swipe-to-delete panel never shows through the row itself.
            .background(MaterialTheme.colorScheme.surface)
            .safeClickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Every visitor is "Website Visitor", so the letter collides for all of them. The disc
        // colour is seeded from the conversation id, which is what keeps them apart.
        PersonAvatar(
            name = conversation.visitorName,
            email = conversation.visitorEmail,
            seed = conversation.id,
            size = 48.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.visitorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (conversation.unreadForAgents > 0) {
                        FontWeight.Bold
                    } else {
                        FontWeight.SemiBold
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.isPending) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill(text = "Pending", emphasised = true)
                } else if (conversation.isClosed) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill(text = "Closed")
                } else if (isMine) {
                    Spacer(Modifier.width(6.dp))
                    // A dot, not a word: "Assigned to you" is the common case and does not
                    // need a sentence on every row.
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.lastText.ifBlank { "No messages yet" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unreadForAgents > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = conversation.unreadForAgents.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        /*
         * Outside the column on purpose. Pinned here it is always hard against the right
         * gutter, so the whole list has one clean vertical edge of timestamps regardless of
         * how long each visitor's name or preview happens to be.
         */
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatTime(conversation.lastActivityAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Top).padding(top = 2.dp),
        )
    }
}

fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let {
        it.format(Date(epochMillis)) == it.format(Date(now))
    }
    val pattern = if (sameDay) "HH:mm" else "dd MMM"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
}
