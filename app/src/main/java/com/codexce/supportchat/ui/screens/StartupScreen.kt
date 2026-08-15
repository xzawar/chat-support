package com.codexce.supportchat.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.ui.theme.IconBlue

/*
 * Cold start.
 *
 * Firebase restores its session from disk, the tenant claims are read and the plan is rehydrated
 * before the inbox can show anything truthful. That work used to happen behind an empty scaffold,
 * so a cold launch showed a bare bar and a blank list for a moment and looked broken rather than
 * busy. This screen owns that window, and the graph crossfades in once the workspace has actually
 * resolved.
 *
 * The bar is the entire screen. No mark, no wordmark, no status line.
 *
 * What was here before was a breathing rounded square, a title and three staggered dots: three
 * separate animations, all saying the same single thing, on a screen whose whole job is to be
 * over quickly. A splash is not a branding opportunity. The user just tapped an icon with the
 * name under it, so telling them the name again is the least useful thing this frame could do,
 * and it is a frame they will see on every cold launch for as long as they own the app.
 *
 * It is also deliberately the cheapest thing that can be drawn: two rounded rectangles and one
 * animated property, no image decode and no text layout, so it cannot slow down the work it
 * exists to cover.
 */
@Composable
fun StartupScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        IndeterminateBar()
    }
}

/**
 * A single indeterminate bar.
 *
 * Indeterminate on purpose. Nothing on this path can say how much of the session restore is
 * done, and a bar that creeps to some invented ninety percent and then sits there is a worse
 * lie than one that never claimed to be measuring anything.
 *
 * The travelling segment is moved with translationX through graphicsLayer rather than by
 * changing padding or offset. Only the layer's transform is touched, so no measure or layout
 * pass runs per frame, which matters more here than anywhere else in the app: this animation is
 * on screen precisely while the main thread is busy with the startup work, and anything that
 * needed a relayout every frame would stutter exactly when it is being watched.
 */
@Composable
private fun IndeterminateBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "startupBar")

    // Fraction of the track's width to translate by. Runs a little past 1 at each end so the
    // segment fully clears the rounded ends instead of appearing to bounce off them.
    val progress by transition.animateFloat(
        initialValue = -0.42f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "startupBarProgress",
    )

    val trackWidth = 220.dp
    val segmentWidth = 84.dp

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(3.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .width(segmentWidth)
                .height(3.dp)
                .graphicsLayer {
                    // Resolved against the track rather than the segment, so the travel distance
                    // stays correct if either width is ever changed.
                    translationX = progress * trackWidth.toPx()
                }
                .clip(RoundedCornerShape(percent = 50))
                // The launcher icon's blue, not the UI accent. This bar is the first thing drawn
                // after the system splash hands over, so it continues the icon rather than
                // introducing the accent a moment early.
                .background(IconBlue),
        )
    }
}
