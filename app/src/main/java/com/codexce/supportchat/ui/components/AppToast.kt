package com.codexce.supportchat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long a toast stays put before it starts leaving. */
private const val VISIBLE_MILLIS = 3_000L
private const val ENTER_MILLIS = 320
private const val EXIT_MILLIS = 260

/**
 * How far off the bottom the card sits.
 *
 * The toast used to sit in the bottom-right corner, where on the tab screens it landed on top
 * of the floating tab bar and covered the right-hand icons. It is now centred and lifted clear
 * of the bar by its full height.
 *
 * TabBarHeight is read rather than hard-coded so the two cannot drift apart. The same inset is
 * used on screens that have no tab bar: a toast that changes height depending on which screen
 * raised it reads as a layout bug, and the extra clearance costs nothing.
 */
private val ToastBottomInset = TabBarHeight + 16.dp

/**
 * One short message on screen at a time.
 *
 * @property text What to show.
 * @property id Distinguishes two identical messages in a row, so the second one still
 *   restarts the timer instead of being swallowed as "no change".
 */
data class ToastMessage(
    val text: String,
    val id: Long,
    /**
     * Optional action label, e.g. "Undo". Null draws a plain text toast exactly as before.
     *
     * Keep it to one word. It sits on the same line as the message and competes with it for a
     * card that is capped at 320dp.
     */
    val actionLabel: String? = null,
    /**
     * Run when the action is tapped. The toast dismisses itself first, so this does not need to.
     */
    val action: (() -> Unit)? = null,
)

/**
 * The toast queue, such as it is: the newest message replaces whatever was showing.
 *
 * This is deliberately a plain object rather than something passed down the tree. Toasts get
 * raised from repositories and view models that have no business knowing about composition,
 * and threading a handle through every screen to reach them is not worth it for a transient
 * strip of text.
 */
object AppToast {
    private val _current = MutableStateFlow<ToastMessage?>(null)

    val current: StateFlow<ToastMessage?> = _current.asStateFlow()

    /**
     * Show a short message.
     *
     * @param text The message. Keep it to a few words; it is capped at two lines.
     */
    fun show(text: String) {
        if (text.isBlank()) return
        _current.value = ToastMessage(text, System.nanoTime())
    }

    /**
     * Show a short message with one action beside it.
     *
     * The reference pairs every reversible destructive action with one of these rather than with
     * a confirmation dialog: the thing happens immediately and you get a few seconds to take it
     * back. That is only honest if [action] genuinely restores the previous state - offering an
     * Undo that cannot undo is worse than offering nothing.
     *
     * @param text The message.
     * @param actionLabel One word, e.g. "Undo".
     * @param action Run on tap. The toast is dismissed before this is called.
     */
    fun show(text: String, actionLabel: String, action: () -> Unit) {
        if (text.isBlank()) return
        _current.value = ToastMessage(text, System.nanoTime(), actionLabel, action)
    }

    /** Take the current message away early. */
    fun dismiss() {
        _current.value = null
    }
}

/**
 * Renders whatever [AppToast] is currently showing, pinned to the bottom-right.
 *
 * Place this once, above the nav host, so a toast survives the screen that raised it.
 *
 * @param modifier Layout modifier.
 */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val message by AppToast.current.collectAsStateWithLifecycle()

    /*
     * The text is held separately from the flow. On dismissal the flow goes null immediately,
     * but the card is still on screen animating out and needs something to draw - without this
     * the message blanks the instant the exit starts.
     */
    var shown by remember { mutableStateOf("") }
    var shownAction by remember { mutableStateOf<String?>(null) }
    message?.let {
        shown = it.text
        shownAction = it.actionLabel
    }

    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(VISIBLE_MILLIS)
            AppToast.dismiss()
        }
    }

    Box(
        /*
         * fillMaxSize gives the alignment something to work against. Nothing in here is
         * clickable, so the empty area does not swallow taps meant for the screen below.
         */
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = ToastBottomInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = message != null,
            /* In from the right edge, with a touch of lift so it arrives on a diagonal. */
            enter = slideInVertically(
                animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
            ) { height -> height + 24 } + fadeIn(tween(ENTER_MILLIS)),
            /*
             * Out is a fade with only a small drift. Sending it all the way back off-screen
             * on the way out draws the eye to something that is already finished with.
             */
            exit = fadeOut(tween(EXIT_MILLIS)) + slideOutVertically(
                animationSpec = tween(EXIT_MILLIS, easing = FastOutSlowInEasing),
            ) { height -> height / 3 },
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                /*
                 * Surface, not inverseSurface.
                 *
                 * inverseSurface is deliberately the opposite of the page - a dark card in
                 * light mode and a light card in dark mode - which is the Material default and
                 * the opposite of what this app wants. surfaceContainerHigh follows the theme
                 * instead: light card on the light scheme, dark card on the true-black one, one
                 * step lifted off the page so it still reads as sitting above the list.
                 */
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val label = shownAction
                    if (label != null) {
                        Spacer(Modifier.width(16.dp))
                        /*
                         * The action is read off the live message rather than off the captured
                         * label, so a toast that is already animating out cannot fire the
                         * action of the toast that replaced it.
                         */
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.safeClickable {
                                val pending = message?.action
                                AppToast.dismiss()
                                pending?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}
