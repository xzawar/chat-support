package com.codexce.supportchat.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp

/*
 * One dot's full trip: up, down, then a pause before its turn comes round again.
 *
 * The stagger is what turns three identical bounces into a wave. It has to be a good bit
 * shorter than the lift itself, otherwise the dots read as three separate animations that
 * happen to share a row rather than one travelling motion.
 *
 * The tail of the cycle is deliberately dead time. Without it the wave restarts the instant
 * the third dot lands and the whole thing looks frantic.
 */
private const val CYCLE_MILLIS = 1_100
private const val LIFT_MILLIS = 200
private const val STAGGER_MILLIS = 130
private const val DOT_COUNT = 3
private const val LIFT_DP = 3f
private const val REST_ALPHA = 0.4f

/**
 * Three dots rising and falling in sequence, looping until it is taken off screen.
 *
 * Both the lift and the fade are driven off a single 0..1 phase per dot rather than two
 * separate animations, so a dot can never be caught bright at the bottom or dim at the top.
 *
 * @param modifier Layout modifier.
 * @param color Dot colour. Defaults to the muted on-surface tone.
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val transition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DOT_COUNT) { index ->
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = CYCLE_MILLIS
                        0f at 0 using FastOutSlowInEasing
                        1f at LIFT_MILLIS using FastOutSlowInEasing
                        0f at LIFT_MILLIS * 2
                        0f at CYCLE_MILLIS
                    },
                    repeatMode = RepeatMode.Restart,
                    /* Each dot starts its identical loop a little later than the one before. */
                    initialStartOffset = StartOffset(index * STAGGER_MILLIS),
                ),
                label = "dot$index",
            )

            Box(
                Modifier
                    .size(5.dp)
                    /*
                     * A layer transform, so the bounce runs on the render thread and never
                     * re-measures the row underneath it.
                     */
                    .graphicsLayer {
                        translationY = -LIFT_DP.dp.toPx() * phase
                        alpha = REST_ALPHA + (1f - REST_ALPHA) * phase
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * The dots dressed as an incoming message, for the bottom of a thread.
 *
 * It borrows the visitor-side bubble shape from [MessageBubble] on purpose: a typing hint that
 * does not match the bubble it is about to become reads as a different kind of object.
 *
 * @param modifier Layout modifier.
 */
@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 6.dp,
                        bottomEnd = 18.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            TypingDots()
        }
    }
}
