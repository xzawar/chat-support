package com.codexce.supportchat.ui.components

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.codexce.supportchat.ui.theme.GroupedCardShape
import com.codexce.supportchat.ui.theme.SettingsTileShape

/**
 * The back affordance for every screen that has one.
 *
 * This exact six-line block was written out thirteen times, once per screen with a top bar, and
 * the copies had already begun to drift: one passed its arguments positionally, one guarded on a
 * nullable callback, and the glyph size was re-stated at all thirteen. A single definition means
 * the tap target, the 22dp glyph and the debounce all change in one place.
 *
 * [debounced] is applied here rather than at the call sites. Back is the control most likely to
 * be double-tapped, and an undebounced second tap pops two entries off the stack, which is how
 * you end up two screens back from where you meant to be.
 */
@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = debounced(onBack), modifier = modifier) {
        Icon(
            painter = painterResource(AppIcons.Back),
            contentDescription = "Back",
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun SupportCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    /*
     * The same card the Settings groups use: GroupedCardShape, no outline.
     *
     * This used to be shapes.medium with a 1dp border, which is why the Link your website and
     * Subscription screens read as a different app - every other surface in the build is a
     * borderless rounded card on the page background, and an outlined card next to one of those
     * looks like a dialog that failed to open. Fixing it here fixes every screen that uses it
     * at once, instead of restyling each screen and missing one.
     */
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(GroupedCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onClick != null) Modifier.safeClickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val background = if (emphasised) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = if (emphasised) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun InitialsAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    loading: Boolean = false,
    /**
     * The Google account photo, when there is one. It is a plain https URL on the signed-in
     * user, so nothing is uploaded or stored anywhere: if the URL fails to load the initials
     * stay behind it, which is also what happens for email-only accounts.
     */
    photoUrl: String? = null,
) {
    if (loading) {
        SkeletonBlock(modifier.size(size), CircleShape)
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

@Composable
fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    /*
     * Accepted and deliberately not drawn.
     *
     * Captions under row titles were removed from the whole app. Killing the parameter
     * instead of the rendering would mean editing every call site, and a mechanical sweep
     * across dozens of call sites is exactly how the last few builds broke. Ignoring it here
     * removes every caption in one edit that cannot miss one and cannot fail to compile.
     */
    @Suppress("UNUSED_PARAMETER") subtitle: String? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    // iOS puts a solid, per-row colour behind a white glyph. Falling back to the brand blue keeps
    // any caller that does not pass a tint looking deliberate rather than unstyled.
    val tile = tint ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.safeClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(SettingsTileShape)
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                painter = painterResource(AppIcons.ChevronRight),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Surfaces a rejected Firebase write. Previously these failures were invisible. */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.safeClickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
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
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(top = 16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.padding(top = 6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.padding(top = 20.dp))
            action()
        }
    }
}

/** Divider that respects the monochrome outline tokens. */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Phase 9 - separation by tonal shading rather than by rule.
 *
 * Material 3 expresses elevation as a surface-container step, not as a shadow, and this app
 * already leans on that everywhere else. Wrapping a group in one shade step reads as a distinct
 * block without drawing a hard line across the screen, and it survives the dark theme unchanged
 * because both schemes define the same five container steps.
 *
 * Prefer this over ThinDivider between whole sections; keep ThinDivider for splitting rows
 * inside one section, where a shade step would just create stripes.
 */
/**
 * A group of settings rows drawn as one rounded card on the page.
 *
 * This is the structural unit of the reference's Settings screen: the page itself is bare, and
 * meaning comes from which rows share a card rather than from headers and rules. It replaces
 * SectionHeader-plus-ThinDivider as the way sections are expressed, though both of those are
 * still here and still used by screens that are genuinely a flat list.
 *
 * Separate rows inside one of these with [GroupDivider], not [ThinDivider]: the inset has to
 * line up with the text column rather than with the page margin.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(GroupedCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/**
 * The hairline between two rows inside a [SettingsGroup].
 *
 * Inset to 66dp so it starts under the title rather than under the icon tile: 20dp of row
 * padding, a 32dp tile and the 14dp gap after it. A rule that runs under the tile cuts the
 * coloured squares in half and makes the card look like a table.
 *
 * Hairline rather than 1dp. On a #181818 card a full device-independent pixel of #262626 reads
 * as a drawn line; a hairline reads as a seam, which is what it is.
 */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 66.dp)
            .height(Dp.Hairline)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// =========================================================================================
// Tap spam
// =========================================================================================

/**
 * How long the app ignores a second tap.
 *
 * 500ms is above the ~300ms a double-tap lands in and below the ~700ms at which a deliberate
 * second tap starts to feel dropped.
 */
private const val CLICK_WINDOW_MILLIS = 100L

/** One frame. Repeat calls within this are one tap passing through nested guards, not spam. */
private const val NESTED_CALL_MILLIS = 16L

/**
 * One guard for the whole app, not one per control.
 *
 * The damage from tap spam is almost never a control firing twice on its own; it is a second
 * tap landing on a DIFFERENT control while the first is still animating. Tapping a settings row
 * twice pushes that screen onto the back stack twice, so the first back press appears to do
 * nothing. Tapping two rows in quick succession pushes both. A per-control guard cannot see
 * either case, because the two taps are not the same control.
 *
 * elapsedRealtime, not currentTimeMillis: the wall clock can be moved backwards by the network
 * or the user, which would disable the guard until it caught up.
 *
 * Switches and text fields are deliberately not routed through this. A Switch reports through
 * onCheckedChange rather than a click, so it stays responsive, which is correct - flipping two
 * toggles quickly is ordinary use, not spam.
 */
object ClickGuard {
    private var lastClickAt = 0L

    fun allow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastClickAt

        // Also catches a clock that has not started: 0 - 0 is not less than the window.
        if (lastClickAt != 0L && sinceLast < CLICK_WINDOW_MILLIS) {
            /*
             * Re-entrancy, not spam.
             *
             * A guarded row that is ALSO handed an already-guarded lambda asks this question
             * twice for one tap. The first call stamps the clock, the second sees ~0ms elapsed
             * and, with a naive check, refuses - so the guard cancels its own click and the
             * control becomes permanently dead. That is exactly what happened to the nav bar,
             * the Settings rows and the profile chips.
             *
             * Anything arriving inside one frame of the last click is the same tap travelling
             * through nested guards, so it is allowed through. A human cannot produce two
             * distinct taps 16ms apart; real spam lands tens of milliseconds later and is
             * still refused. Guarding twice is now harmless instead of fatal.
             */
            return sinceLast <= NESTED_CALL_MILLIS
        }
        lastClickAt = now
        return true
    }
}

/**
 * Wraps a click so it can only fire once per window. Use on Buttons, IconButtons and anything
 * else taking an `onClick` lambda.
 *
 * rememberUpdatedState so a lambda that closes over changing state is not frozen at the value
 * it had on first composition.
 */
@Composable
fun debounced(onClick: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onClick)
    return remember { { if (ClickGuard.allow()) latest() } }
}

/** [clickable] with the same guard. */
fun Modifier.safeClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    // clickable, NOT safeClickable. Calling safeClickable here recurses forever and blows the
    // stack the first time any guarded row is composed.
    this.clickable(enabled = enabled) { if (ClickGuard.allow()) onClick() }

// =========================================================================================
// Grouping without headings
// =========================================================================================

/**
 * The gap that used to be a SectionHeader.
 *
 * Every "ACCOUNT" / "NOTIFICATIONS" caption has been removed. They were doing no work: the
 * rows underneath already name themselves, and a screen with five shouty grey captions reads
 * as a form. What the captions did carry was separation, so that part stays as plain space.
 */
@Composable
fun GroupGap(modifier: Modifier = Modifier) {
    Spacer(modifier.height(18.dp))
}

/** Right-aligned controls where a section heading used to hold them. */
@Composable
fun RowActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/*
2 * How a PlainRow splits its width.
 *
 * Both columns used to ask for weight(1f), so the label reserved half the row whether it needed
 * it or not. Labels here are one or two words; values are addresses and email addresses. That
 * is why a long email broke across two right-aligned lines while the word "Email" sat in a
 * half-row of empty space beside it.
 */
private const val TitleWeight = 0.42f
private const val ValueWeight = 0.58f

/**
 * A settings row with no icon tile.
 *
 * The Account screen is a list of facts about one account, not a menu of destinations. Giving
 * each fact a coloured square would invent ten categories that do not exist.
 */
@Composable
fun PlainRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    /** Accepted and deliberately not drawn. See [SettingsRow]. */
    @Suppress("UNUSED_PARAMETER") subtitle: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.safeClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(TitleWeight)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(ValueWeight, fill = false),
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(AppIcons.ChevronRight),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Static explanatory text, boxed.
 *
 * Help had prose floating between tappable rows, which made it ambiguous which lines were
 * buttons. Anything that cannot be tapped now sits inside one of these.
 */
@Composable
fun InfoBox(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(GroupedCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
