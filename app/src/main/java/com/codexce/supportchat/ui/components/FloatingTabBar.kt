package com.codexce.supportchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.ui.navigation.TopLevelTab
import com.codexce.supportchat.ui.theme.Motion

/** Published so screens can pad their content clear of the floating pill. */
val TabBarHeight: Dp = 72.dp

/**
 * Floating pill nav, matching the reference: a dark rounded bar, icon-only, with the active tab
 * marked by a filled circle behind a filled-weight icon.
 *
 * Index-based rather than route-based, because the tabs are pages of a pager now. The caller
 * passes pagerState.currentPage, so the indicator tracks a drag as it settles rather than only
 * changing on tap.
 */
@Composable
fun FloatingTabBar(
    tabs: List<TopLevelTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * The bar has to separate from the page, and the two appearances need opposite answers.
     *
     * On the true-black page a shadow is invisible - there is nothing for it to darken - so the
     * bar has to be lighter than what it floats over, and surfaceContainerHigh (#272727) is the
     * first step that reads clearly against #000000. On the light page that same token is a pale
     * grey barely distinguishable from the #F4F4F4 behind it, so white plus the shadow does the
     * work instead.
     *
     * Branching on background luminance rather than taking a darkTheme flag: this component is
     * called from one place and threading a boolean down to it, only to ask a question the
     * colour scheme can already answer, is a parameter nobody would maintain.
     */
    val onLightPage = MaterialTheme.colorScheme.background.luminance() > 0.5f

    Surface(
        modifier = modifier.height(TabBarHeight),
        shape = CircleShape,
        color = if (onLightPage) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shadowElevation = 12.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                TabButton(
                    tab = tab,
                    selected = index == selectedIndex,
                    onClick = debounced { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun TabButton(
    tab: TopLevelTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 66dp keeps the touch target well above the 48dp minimum even though the icon is 28dp.
    val indicatorScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(Motion.TAB_MILLIS),
        label = "indicatorScale",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            // White glyph sitting on the solid blue dot.
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(Motion.TAB_MILLIS),
        label = "iconTint",
    )

    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .selectable(
                selected = selected,
                role = Role.Tab,
                // Already guarded by the caller; guarding again here is redundant.
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .scale(indicatorScale)
                .clip(CircleShape)
                // The brand blue, behind the filled icon of the active tab.
                .background(MaterialTheme.colorScheme.primary),
        )
        Icon(
            painter = painterResource(if (selected) tab.filledIcon else tab.outlineIcon),
            contentDescription = tab.label,
            tint = iconTint,
            modifier = Modifier.size(28.dp),
        )
    }
}
