package com.codexce.supportchat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Loading placeholders. Every async surface in the app uses these instead of a bare spinner
 * or an empty screen, so first load reads as "content arriving" rather than "nothing here".
 *
 * These pulse between two opacities rather than sweeping a highlight across themselves.
 *
 * The sweep was a linearGradient whose start and end offsets were re-animated every frame. That
 * meant a new Brush allocation and a full repaint of every placeholder on screen on each frame,
 * and the conversation list draws seven of them at once. Animating alpha instead is handled by
 * the render thread without touching the shader, and at these sizes the two are indistinguishable
 * anyway: a highlight travelling across a 52dp circle is not perceived as a highlight travelling,
 * only as the circle changing brightness, which is exactly what this does directly.
 */
@Composable
private fun rememberPlaceholderAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "placeholder")
    val alpha by transition.animateFloat(
        initialValue = 0.07f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "placeholderAlpha",
    )
    return alpha
}

@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val alpha = rememberPlaceholderAlpha()
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
    )
}

@Composable
fun SkeletonLine(width: Dp, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    SkeletonBlock(modifier.width(width).height(height))
}

/** Chat list first load. */
@Composable
fun ConversationListSkeleton(rows: Int = 7, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(Modifier.size(52.dp), CircleShape)
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonLine(width = if (index % 2 == 0) 132.dp else 108.dp, height = 13.dp)
                    SkeletonLine(width = if (index % 3 == 0) 210.dp else 168.dp, height = 11.dp)
                }
            }
        }
    }
}

/** Message thread first load: alternating bubble stubs. */
@Composable
fun MessageThreadSkeleton(modifier: Modifier = Modifier) {
    val widths = listOf(180.dp, 132.dp, 220.dp, 156.dp, 196.dp, 120.dp)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        widths.forEachIndexed { index, width ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (index % 2 == 0) Arrangement.Start else Arrangement.End,
            ) {
                SkeletonBlock(
                    Modifier.width(width).height(if (index % 3 == 0) 56.dp else 38.dp),
                    MaterialTheme.shapes.large,
                )
            }
        }
    }
}

@Composable
fun CardListSkeleton(cards: Int = 3, cardHeight: Dp = 92.dp, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(cards) {
            SkeletonBlock(Modifier.fillMaxWidth().height(cardHeight), MaterialTheme.shapes.medium)
        }
    }
}

/** Account / settings profile block. */
@Composable
fun ProfileSkeleton(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SkeletonBlock(Modifier.size(64.dp), CircleShape)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonLine(width = 148.dp, height = 15.dp)
            SkeletonLine(width = 196.dp, height = 12.dp)
        }
    }
}
