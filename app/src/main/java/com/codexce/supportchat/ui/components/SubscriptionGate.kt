package com.codexce.supportchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.ui.theme.TgRedDark
import com.codexce.supportchat.ui.theme.supportButtonColors

/**
 * Shown when the real reason a screen has no data is "this account has no active subscription".
 *
 * The screens used to surface the raw backend refusal for this case, which reads as a database
 * rules problem and sends the owner looking in entirely the wrong place. A lapsed subscription
 * is a billing state with an obvious next action, so it gets one.
 *
 * TODO: replicate this on the Email and Social tabs, and on the Emails screen reached from
 * Settings. Only the Chatbox tab is wired up in this pass, by request.
 *
 * The lock glyph is drawn in TgRedDark (#EC4E4E) rather than a muted grey, so the reason the
 * screen is empty is legible at a glance instead of looking like an ordinary empty state.
 */
@Composable
fun SubscriptionGate(
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = "You need an active subscription to use this.",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AppIcons.Lock),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                /*
                 * The one red thing on the screen, by request. The heading, the body copy and
                 * the Subscribe button all stay on the normal palette: colouring the button red
                 * as well would read as a warning about pressing it, when it is the action we
                 * actually want taken.
                 */
                tint = TgRedDark,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Subscription required",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = debounced(onSubscribe), colors = supportButtonColors()) { Text("Subscribe") }
    }
}
