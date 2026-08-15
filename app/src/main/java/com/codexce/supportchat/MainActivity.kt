package com.codexce.supportchat

import android.app.Activity
import android.app.UiModeManager
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.notifications.PushNotifications
import com.codexce.supportchat.ui.navigation.SupportChatApp
import com.codexce.supportchat.update.UpdateHost
import com.codexce.supportchat.ui.theme.SupportChatTheme
import com.codexce.supportchat.ui.theme.LocalThemeRevealActive
import com.codexce.supportchat.ui.theme.ThemeRevealHost

class MainActivity : ComponentActivity() {

    /**
     * Make the -night resource qualifier follow the APP's stored preference instead of the
     * system setting.
     *
     * The bug this fixes: dark mode in this app is a SharedPreferences flag read by Compose,
     * but res/values-night/ is selected by the SYSTEM. A phone on system-dark with the app set
     * to light resolved window_background to #000000 while Compose painted a white UI, so a
     * black frame showed through on every launch and in any region Compose had not drawn yet.
     * The two disagreed because they were reading two different sources of truth.
     *
     * AppCompatDelegate.setDefaultNightMode is the usual answer and is NOT available here:
     * there is no appcompat dependency, MainActivity is a ComponentActivity, and that API only
     * drives AppCompatActivity. Adding appcompat to a pure-Compose app to move one colour would
     * be a poor trade.
     *
     * So, two mechanisms:
     *
     *  - API 31+ gets UiModeManager.setApplicationNightMode, which is the platform's own
     *    per-app override. It persists across launches, so it fixes the cold-start splash too,
     *    before any of this code has run.
     *  - Below 31 there is no per-app override at all, so the window background is set directly
     *    instead. That covers the symptom that is actually visible - the flash of the wrong
     *    colour - on every supported API level.
     *
     * Honest limitation on API 24-30: the launch theme is inflated by the system before the
     * process starts, so the very first splash frame can still follow the system setting. Once
     * this runs, everything after it is correct.
     *
     * The colours are literals rather than R.color.window_background on purpose: resolving that
     * resource goes through the -night qualifier, which is the thing being overridden. Reading
     * it here would hand back the value we are trying not to use. They match TgBlack and the
     * light scheme background in Color.kt; if either changes, change these with it.
     */
    private fun applyStoredNightMode(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Wrapped: this is a decoration, and no appearance override is worth a launch crash
            // if an OEM has done something unexpected with UiModeManager.
            runCatching {
                getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                    if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO,
                )
            }
        }

        window.setBackgroundDrawable(
            ColorDrawable(if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()),
        )
    }

    /** Conversation id carried by a tapped notification, consumed once by the nav graph. */
    private val deepLinkConversationId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * Must be called before super.onCreate(), and before enableEdgeToEdge() has any window
         * to work on. It swaps the launch theme out for Theme.SupportChat via the
         * postSplashScreenTheme attribute, so the activity ends up on exactly the theme it used
         * to start on and nothing downstream can tell the difference.
         *
         * No setKeepOnScreenCondition here, deliberately. The system splash is released as soon
         * as the first frame is ready; StartupScreen then owns the wait for Firebase session
         * restore and shows a moving bar while it happens. Holding this one until the workspace
         * resolved would mean a frozen icon for an unbounded network round trip.
         */
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()

        // Read once, synchronously, before the first composition. The saved dark-mode value is
        // therefore already correct on the first frame rather than one frame late.
        val preferences = AppPreferences.get(this)
        applyStoredNightMode(preferences.darkThemeAtStartup)
        // Notification-channel setup is deferred until the signed-in workspace has loaded. It
        // does not affect the first frame, and a message arriving earlier creates the channel in
        // SupportMessagingService itself, so there is no delivery race here.
        deepLinkConversationId.value = readConversationId(intent)

        setContent {
            val darkTheme by preferences.darkTheme.collectAsStateWithLifecycle()
            /*
             * The reveal host sits outside the theme, not inside it.
             *
             * It holds a photograph of the previous appearance and wipes it away. Anything
             * inside SupportChatTheme is recomposed the instant the colour scheme changes, which
             * is precisely the moment the photograph has to survive. Nesting it the other way
             * round would throw the snapshot away on the first frame of the animation.
             */
            ThemeRevealHost(onThemeChange = preferences::setDarkTheme) {
                SupportChatTheme(darkTheme = darkTheme) {
                    SystemBarAppearance(darkTheme)
                    SupportChatApp(
                        preferences = preferences,
                        deepLinkConversationId = deepLinkConversationId.value,
                        onDeepLinkHandled = { deepLinkConversationId.value = null },
                    )
                    /*
                     * The update prompt is a sibling of the nav graph, not a destination inside it.
                     *
                     * An update is relevant wherever the user happens to be - the inbox, a chat,
                     * the login screen - so it cannot belong to one route. Putting it in the graph
                     * would also make it something the back stack can reach, which means back
                     * could navigate to a stale prompt or away from a mandatory one.
                     *
                     * It renders nothing at all unless a newer build exists, so the cost on a
                     * normal launch is one background request a couple of seconds after the first
                     * frame. Placed after SupportChatApp so the dialog draws above it.
                     */
                    UpdateHost(preferences = preferences)
                }
            }
        }
    }

    /** Fires when the activity is already running and a notification is tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkConversationId.value = readConversationId(intent)
    }

    override fun onResume() {
        super.onResume()
        // The mode preference lives on the window, and a window can be rebuilt behind our
        // back: a configuration change, or another app taking the display and handing it
        // back in its own mode. Re-asserting on every resume is a few microseconds and
        // makes the request stick. Setting it once in onCreate does not.
        requestHighestRefreshRate()
    }

    private fun readConversationId(intent: Intent?): String? =
        intent?.getStringExtra(PushNotifications.EXTRA_CONVERSATION_ID)

    /**
     * Ask for the fastest display mode that keeps the resolution we already have.
     *
     * Three separate things had to be right before this took effect:
     *
     * 1. The chosen mode must match the current physical resolution. Taking the single
     *    highest refresh rate is wrong on panels that expose 1080p120 next to 1440p60 --
     *    the display obeys, and the phone quietly drops resolution to buy the frame rate.
     *    Filtering on size first means we only ever trade up.
     * 2. The LayoutParams passed to setAttributes has to be a *different* object from the
     *    one the window already holds. setAttributes copies field by field and skips the
     *    relayout when nothing changed, so handing back the same mutated instance compares
     *    it against itself, finds no difference, and drops the request. That was the real
     *    reason this sat at 60Hz.
     * 3. It has to run on the phones that need it. preferredDisplayModeId is API 23, not
     *    API 30; the old guard skipped every 90Hz and 120Hz device on Android 9 and 10.
     *
     * On a 60Hz panel supportedModes returns one entry and this is a no-op.
     */
    private fun requestHighestRefreshRate() {
        runCatching {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val current = display.mode ?: return
            val fastest = display.supportedModes
                .filter {
                    it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: return

            // Tolerance because reported rates are values like 59.94 and 120.00048.
            if (fastest.refreshRate <= current.refreshRate + 1f) return

            window.attributes = WindowManager.LayoutParams().apply {
                copyFrom(window.attributes)
                preferredDisplayModeId = fastest.modeId
                // Secondary hint for devices whose frame-rate selector treats the mode id
                // as advisory. Redundant where the id is already honoured, never harmful.
                preferredRefreshRate = fastest.refreshRate
            }
        }
    }
}

/**
 * Keeps the status and navigation bar icons in step with the reveal.
 *
 * The flash reported at the top of Settings was this. The bar icons are painted by the system
 * from a window flag, so they sit outside the frozen snapshot and cannot be clipped by the
 * overlay: flipping them the moment the colour scheme changed inverted the whole strip at once
 * while the circle was still crossing the screen. Against a header that had not been uncovered
 * yet, that reads as a flash.
 *
 * So the flag is only written while no reveal is in flight. During one, this composes with
 * revealing = true and does nothing; when the overlay is released the effect re-runs with the
 * final value and the bars catch up. A theme change from anywhere other than the toggle never
 * sees a reveal and applies immediately, as before.
 */
@Composable
private fun SystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    val revealing = LocalThemeRevealActive.current
    DisposableEffect(darkTheme, revealing) {
        if (!revealing) {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
        onDispose { }
    }
}
