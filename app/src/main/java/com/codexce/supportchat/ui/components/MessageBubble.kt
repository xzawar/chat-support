package com.codexce.supportchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.data.model.ChatMessage
import com.codexce.supportchat.ui.theme.BrandDeep

@Composable
fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    // Start Chat writes a "You're now connected with ..." message into the thread. It belongs to
    // neither side, so it gets a centred chip rather than a bubble with a tail.
    if (message.isSystem) {
        SystemNotice(text = message.text, modifier = modifier)
        return
    }

    val fromOwner = message.fromOwner
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (fromOwner) 18.dp else 6.dp,
        bottomEnd = if (fromOwner) 6.dp else 18.dp,
    )
    Row(
        // Phase 9: the thread container tightens from 16dp/3dp to 12dp/2dp, and bubbles cap at
        // 280dp rather than 300dp, so the column of text reads narrower and denser.
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (fromOwner) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(
                    // Outgoing bubbles are BrandDeep rather than colorScheme.primary. primary is
                    // SkyLight in the dark appearance, and white on SkyLight is about 1.9:1 -
                    // unreadable. BrandDeep is the one blue this app puts behind white text.
                    if (fromOwner) {
                        BrandDeep
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fromOwner) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = formatTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fromOwner) {
                        Color.White.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SystemNotice(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
