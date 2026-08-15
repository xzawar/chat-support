@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import kotlin.math.absoluteValue
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.codexce.supportchat.R
import com.codexce.supportchat.ui.components.BackButton

/**
 * The Email and Social tabs, following the supplied "Nothing to show yet" design.
 *
 * This replaces the three mock dashboards that were built in the previous pass — removed at your
 * request, in favour of this screen.
 *
 * onBack is null for the tab roots, since they are pages of the pager and have nothing to
 * pop; the Settings entry points pass a real callback and get a top bar.
 */
@Composable
fun ComingSoonScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    bottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    navigationIcon = { BackButton(onBack) },
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(start = 40.dp, end = 40.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Your sphere set, as real vectors this time, so these scale cleanly at any size
            // instead of the soft upscaled crops that were here before. Which trio appears is
            // derived from the screen title, so each Coming Soon page has its own cast rather
            // than every page showing the same picture.
            SphereCluster(seed = title)
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Nothing to show yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle
                    ?: "Your recently viewed boards, docs and dashboards will be shown here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}


/** The supplied sphere illustrations, picked deterministically so a screen always looks the same. */
private val Spheres = listOf(
    R.drawable.il_sphere_red_happy,
    R.drawable.il_sphere_blue_smile,
    R.drawable.il_sphere_yellow_smile,
    R.drawable.il_sphere_green_wink,
    R.drawable.il_sphere_pink_big,
    R.drawable.il_sphere_coral_surprised,
    R.drawable.il_sphere_orange_curious,
    R.drawable.il_sphere_peach_smile,
    R.drawable.il_sphere_green_swirl,
    R.drawable.il_sphere_yellow_swoop,
    R.drawable.il_sphere_green_brow,
    R.drawable.il_sphere_pink_mini,
    R.drawable.il_sphere_blue_mini,
    R.drawable.il_sphere_yellow_mini,
)

/**
 * Three spheres at different sizes, overlapping slightly. The offsets are deliberate: a straight
 * row of equal circles reads as a loading indicator rather than as artwork.
 */
@Composable
fun SphereCluster(seed: String, modifier: Modifier = Modifier) {
    val start = (seed.hashCode().absoluteValue) % Spheres.size
    val trio = List(3) { Spheres[(start + it * 5) % Spheres.size] }

    // Each sphere bobs on its own clock (different durations and delays, so they drift out of
    // phase), which is what makes the group read as alive rather than as three static pictures.
    val transition = rememberInfiniteTransition(label = "sphereBob")
    val bobLeft by transition.animateFloat(
        initialValue = 0f,
        targetValue = -7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bobLeft",
    )
    val bobCenter by transition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, delayMillis = 250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bobCenter",
    )
    val bobRight by transition.animateFloat(
        initialValue = 0f,
        targetValue = -9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2300, delayMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bobRight",
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        Image(
            painter = painterResource(trio[1]),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .offset(x = 10.dp, y = (-10).dp)
                .offset { IntOffset(0, bobLeft.dp.roundToPx()) },
        )
        Image(
            painter = painterResource(trio[0]),
            contentDescription = null,
            modifier = Modifier
                .size(112.dp)
                .offset { IntOffset(0, bobCenter.dp.roundToPx()) },
        )
        Image(
            painter = painterResource(trio[2]),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .offset(x = (-12).dp, y = (-6).dp)
                .offset { IntOffset(0, bobRight.dp.roundToPx()) },
        )
    }
}
