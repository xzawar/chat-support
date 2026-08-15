@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.notifications.BatteryOptimization
import com.codexce.supportchat.notifications.MessageWatchService
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.safeClickable
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.GroupDivider
import com.codexce.supportchat.ui.components.OwnerInitialsAvatar
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.SettingsGroup
import com.codexce.supportchat.ui.components.SettingsRow
import com.codexce.supportchat.ui.theme.LocalThemeSwitcher
import com.codexce.supportchat.ui.theme.supportSwitchColors

/*
 * Row tints follow the iOS Settings convention: one saturated colour per row, grouped so that
 * related rows share a family. They are literals rather than theme roles on purpose - these are
 * decoration, not semantic colour, and pulling them from the scheme would make every tile blue.
 *
 * Unchanged in Phase 10. The reference uses the same idea with slightly different hues, and
 * these were already chosen deliberately; swapping ten known-good colours for ten guessed ones
 * would have been change for its own sake.
 */
private val TintBlue = Color(0xFF1677FF)
private val TintTeal = Color(0xFF00A89D)
private val TintGreen = Color(0xFF16A34A)
private val TintOrange = Color(0xFFF97316)
private val TintRed = Color(0xFFEF4444)
private val TintIndigo = Color(0xFF6366F1)
private val TintPurple = Color(0xFF8B5CF6)
private val TintPink = Color(0xFFEC4899)
private val TintBronze = Color(0xFFE0A126)
private val TintPlum = Color(0xFF7C3AED)

/**
 * How far the page scrolls before the profile header is fully collapsed.
 *
 * Not the header's own height. The header stops being useful well before it stops being visible,
 * and tying the fade to the full height means it is still half-opaque when the first card has
 * already reached the top bar.
 */
private val HeaderCollapseDistance = 120.dp

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    signedInEmail: String?,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenWallpaper: () -> Unit,
    onOpenLinkWebsite: () -> Unit,
    onOpenLinkComputer: () -> Unit,
    onOpenHelp: () -> Unit,
    onComingSoon: (String) -> Unit,
    /** Signs the account out. The only destructive action on this screen. */
    onSignOut: () -> Unit = {},
    /** Owner-only. Agents never see the subscription row. */
    showSubscription: Boolean = false,
    /** e.g. "Growth \u00b7 Active". Shown under the subscription row. */
    planSummary: String? = null,
    onOpenSubscription: () -> Unit = {},
    /**
     * Google supplies this after sign-in; email accounts have none. Used for the header name
     * only - the Account row still shows the address, because that is the thing you sign in with.
     */
    displayName: String? = null,
    /** The Google account picture. Null falls back to initials. */
    photoUrl: String? = null,
) {
    val darkTheme by preferences.darkTheme.collectAsStateWithLifecycle()
    val context = LocalContext.current
    /*
     * Re-read when the screen returns to the foreground, not on every recomposition.
     *
     * isExempt() is a binder round trip into PowerManager. It used to run on every
     * recomposition, which on this screen now means once per frame for the whole length of a
     * collapsing-header scroll: a system call per frame, for a value that can only change while
     * the app is in the background, on the system screen the user was just sent to.
     *
     * Keying it to ON_RESUME preserves the behaviour that mattered -- grant the exemption, come
     * straight back, see the row update -- and costs one call per visit instead of one per
     * frame. The counter is what invalidates the remember; its value is never read for itself.
     */
    var resumeCount by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val batteryExempt = remember(resumeCount) { BatteryOptimization.isExempt(context) }
    val keepConnected by preferences.keepConnected.collectAsStateWithLifecycle()
    val updateOnStartup by preferences.updateOnStartup.collectAsStateWithLifecycle()

    val scroll = rememberScrollState()
    val collapsePx = with(LocalDensity.current) { HeaderCollapseDistance.toPx() }
    val collapse = (scroll.value / collapsePx).coerceIn(0f, 1f)

    val themeSwitcher = LocalThemeSwitcher.current
    /*
     * Where the theme reveal starts from.
     *
     * The reference expands the new appearance out of the control you actually touched, and that
     * is most of why the animation reads as cause and effect rather than as a transition. Null
     * until the switch has been laid out at least once, which the host treats as "centre".
     */
    var switchCentre by remember { mutableStateOf<Offset?>(null) }

    /*
     * Why the header name looked frozen after editing it on the Account screen.
     *
     * It was never showing the owner name. displayName is the GOOGLE account name, handed down
     * from the sign-in result, with the email prefix as a fallback - so editing Owner name on
     * the tenant document changed a field this screen does not read, and no amount of
     * refreshing was ever going to move it.
     *
     * The tenant profile now comes first, read from the same TenantSession flow the Account
     * screen writes through, so a rename lands here as soon as the session emits. Google is the
     * fallback for accounts that have not set an owner name, and the email prefix behind that.
     */
    val tenantProfile by TenantSession.tenant.collectAsStateWithLifecycle()
    val headerName = tenantProfile?.ownerName?.takeIf { it.isNotBlank() }
        ?: displayName?.takeIf { it.isNotBlank() }
        ?: signedInEmail?.substringBefore('@')
        ?: "Not signed in"
    val initials = headerName.trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    /*
                     * The page, not the card colour. On the true-black scheme the bar has to be
                     * the same black as the page behind it, otherwise the first card scrolls up
                     * under a visibly lighter strip and the whole screen looks mis-assembled.
                     */
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(scroll),
        ) {
            /*
             * The collapsing profile header.
             *
             * Scale and fade only - the header keeps its slot in the scroll and travels up with
             * everything else. Animating its height instead would re-measure the entire column
             * on every scroll frame to buy an effect nobody can see once the content has moved.
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .safeClickable(onClick = onOpenAccount)
                    .graphicsLayer {
                        alpha = 1f - collapse
                        val shrink = 1f - (collapse * 0.25f)
                        scaleX = shrink
                        scaleY = shrink
                        // Anchor the shrink to the top edge so the avatar pulls up toward the
                        // bar rather than collapsing into its own middle.
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    }
                    .padding(top = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OwnerInitialsAvatar(
                    initials = initials,
                    size = 96.dp,
                    photoUrl = photoUrl,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = headerName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                if (signedInEmail != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = signedInEmail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            GroupGap()
            SettingsGroup {
                SettingsRow(
                    // A person, not a padlock. This row opens the account, it does not lock it.
                    icon = AppIcons.PersonSolid,
                    title = signedInEmail ?: "Sign in",
                    tint = TintBlue,
                    onClick = debounced(onOpenAccount),
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.WindowSolid,
                    title = "Link your website",
                    tint = TintTeal,
                    onClick = debounced(onOpenLinkWebsite),
                )
                GroupDivider()
                SettingsRow(
                    // A monitor, not the website globe. Two adjacent rows sharing one glyph made
                    // "Link your website" and "Link a computer" look like the same action.
                    icon = AppIcons.MonitorSolid,
                    title = "Link a computer",
                    subtitle = "Scan a QR code to use Support Chat Web",
                    tint = TintPurple,
                    onClick = debounced(onOpenLinkComputer),
                )
                /*
                 * Both rows below are hidden rather than disabled when they do not apply. A
                 * greyed row invites a tap and then explains itself with an error; leaving it out
                 * says the same thing more quietly. The server refuses either way.
                 */
                if (showSubscription) {
                    GroupDivider()
                    SettingsRow(
                        icon = AppIcons.CardSolid,
                        title = "Subscription",
                        subtitle = planSummary,
                        tint = TintIndigo,
                        onClick = debounced(onOpenSubscription),
                    )
                }
            }
            /*
             * There is deliberately no Emails row here any more. Email automation is a home
             * screen destination now: it is the Email tab in the bottom bar, not a page buried
             * behind Settings. Do not add it back.
             */

            GroupGap()
            SettingsGroup {
                SettingsRow(
                    icon = AppIcons.WifiSolid,
                    title = "Stay connected",
                    tint = TintGreen,
                    trailing = {
                        Switch(
                            colors = supportSwitchColors(),
                            checked = keepConnected,
                            onCheckedChange = { enabled ->
                                preferences.setKeepConnected(enabled)
                                if (enabled) {
                                    MessageWatchService.start(context)
                                } else {
                                    MessageWatchService.stop(context)
                                }
                            },
                        )
                    },
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.WindowSolid,
                    // No subtitle: SettingsRow accepts one and deliberately does not draw it,
                    // because captions under row titles were removed from the whole app.
                    title = "Check for updates on startup",
                    tint = TintIndigo,
                    trailing = {
                        Switch(
                            colors = supportSwitchColors(),
                            checked = updateOnStartup,
                            onCheckedChange = preferences::setUpdateOnStartup,
                        )
                    },
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.ZapSolid,
                    title = if (batteryExempt) "Instant notifications" else "Allow instant notifications",
                    tint = TintOrange,
                    onClick = debounced { BatteryOptimization.requestExemption(context) },
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.RocketSolid,
                    title = "Autostart",
                    tint = TintRed,
                    onClick = debounced {
                        // No public API for these screens, so fall back to the app details page.
                        if (!BatteryOptimization.openAutostart(context)) {
                            BatteryOptimization.openAppDetails(context)
                        }
                    },
                )
            }

            GroupGap()
            SettingsGroup {
                SettingsRow(
                    icon = AppIcons.MoonSolid,
                    title = "Dark mode",
                    tint = TintIndigo,
                    trailing = {
                        Switch(
                            colors = supportSwitchColors(),
                            checked = darkTheme,
                            /*
                             * Routed through the switcher rather than straight at preferences.
                             * The switcher photographs the screen first, then writes the same
                             * preference, then wipes the photograph away. Calling
                             * preferences.setDarkTheme here instead would still work and would
                             * simply cut, with no animation.
                             */
                            onCheckedChange = { enabled ->
                                themeSwitcher.setDark(enabled, switchCentre)
                            },
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                switchCentre = coordinates.boundsInRoot().center
                            },
                        )
                    },
                )
                GroupDivider()
                /*
                 * There is deliberately no App icon row here any more, and no App icon screen
                 * behind it. Alternate launcher icons work by toggling activity-alias components,
                 * which kills and relaunches the app, drops it out of the launcher's recents, and
                 * on several OEM skins leaves a blank tile until the home screen is redrawn. It
                 * was a novelty paying for itself with real breakage. Do not add it back.
                 */
                SettingsRow(
                    icon = AppIcons.ImageSolid,
                    title = "Chat wallpaper",
                    tint = TintPink,
                    onClick = debounced(onOpenWallpaper),
                )
            }

            GroupGap()
            SettingsGroup {
                SettingsRow(
                    icon = AppIcons.DatabaseSolid,
                    title = "Storage and data",
                    tint = TintPlum,
                    onClick = debounced(onOpenStorage),
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.HelpSolid,
                    title = "Help and contact",
                    tint = TintBronze,
                    onClick = debounced(onOpenHelp),
                )
            }

            /*
             * Sign out: alone, red, and the last thing on the screen.
             *
             * It used to be the third of three stacked outlined buttons on the Account screen,
             * under "Add social media accounts" and "Add another inbox source", where it read as
             * one more thing to add rather than as the one destructive action in the app. Both
             * of those buttons are gone and this is now the only way out, which means it has to
             * be findable: bottom of Settings, full width, danger colour, nothing after it but
             * the version string.
             *
             * Deliberately not inside a SettingsGroup card. It is not a setting, and putting it
             * in the last card would make it look like one more row of Other.
             */
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = debounced(onSignOut),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TintRed,
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    painter = painterResource(AppIcons.LogoutSolid),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }

            Text(
                text = "Support Chat 7.0.2",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    // The reference centres its version footer. Left-aligned it read as a stray
                    // label; centred it reads as the end of the document.
                    .alpha(0.9f),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
