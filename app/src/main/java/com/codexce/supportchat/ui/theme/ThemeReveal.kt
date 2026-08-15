package com.codexce.supportchat.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max

/**
 * The theme-switch circular reveal.
 *
 * What the reference does, and what this reproduces: the screen you are looking at is frozen as
 * an image, the app underneath it redraws in the other appearance, and then a circle centred on
 * whatever you tapped is punched through the frozen image and grown until it covers the screen.
 * The old appearance is not animated at all. It is a photograph being wiped away.
 *
 * Doing it the other way around - animating the real UI - does not work. Every colour in the
 * tree would have to animate independently, text would cross through unreadable mid-greys, and
 * anything drawing a bitmap or a shadow would not participate at all.
 *
 * ## Why View.draw and not GraphicsLayer.toImageBitmap
 *
 * Compose 1.7 offers rememberGraphicsLayer() plus a suspending toImageBitmap(), which is the
 * modern way to do this. It is not the way used here, for two reasons:
 *
 *  1. toImageBitmap() is documented as requiring API 28 on the hardware-accelerated path. This
 *     app ships minSdk 24, so a quarter of the reachable install base would have fallen back to
 *     a plain crossfade.
 *  2. Routing the entire app through a recorded graphics layer means every frame of normal use
 *     pays for the recording, not just the two frames a year when someone flips the theme.
 *
 * View.draw(Canvas) into a software bitmap is the older technique and has neither problem. It
 * works unchanged on API 24, it costs nothing until it is called, and it is what the reference
 * app itself does. The cost is one synchronous main-thread draw of a full-screen bitmap at the
 * moment of the tap - on the order of 10-30ms, so possibly one dropped frame, hidden underneath
 * a still image that has not started moving yet.
 *
 * ## Failure behaviour
 *
 * Every failure path ends in the theme still changing. If the view has not been laid out, or the
 * allocation fails on a low-memory device, the capture returns null and the appearance switches
 * instantly with no animation. A missed animation is a cosmetic loss; a switch that does not
 * happen is a bug.
 */
@Stable
class ThemeSwitcher internal constructor(
    private val apply: (Boolean, Offset?) -> Unit,
) {
    /**
     * Change the appearance, revealing from [origin].
     *
     * @param enabled true for dark.
     * @param origin Centre of the reveal, in root pixel coordinates. Pass the centre of whatever
     *   the user actually touched. Null falls back to the middle of the screen, which is correct
     *   for a change that did not come from a tap.
     */
    fun setDark(enabled: Boolean, origin: Offset? = null) = apply(enabled, origin)
}

/**
 * Reaches the appearance toggle from anywhere without threading a callback through every screen.
 *
 * Deliberately has no no-op default. A silent default would mean that a screen composed outside
 * the host toggles nothing and reports no error, which is a far worse afternoon than a crash on
 * the first run.
 */
val LocalThemeSwitcher = staticCompositionLocalOf<ThemeSwitcher> {
    error("No ThemeSwitcher. Wrap the content in ThemeRevealHost, as MainActivity does.")
}

/**
 * True while a reveal is being captured or played.
 *
 * Anything that paints OUTSIDE the frozen snapshot has to wait for this to go false, or it
 * changes appearance across the whole screen at once while the circle is still travelling. The
 * status and navigation bar icons are the case that matters: they are drawn by the system, not
 * by us, so no amount of clipping inside the overlay can hold them back.
 *
 * Defaults to false so a composable used outside the host behaves exactly as it did before.
 */
val LocalThemeRevealActive = staticCompositionLocalOf { false }

/**
 * Hosts the reveal overlay and publishes [LocalThemeSwitcher].
 *
 * Place this outside SupportChatTheme, not inside it. The overlay has to survive the colour
 * scheme changing underneath it, and anything inside the theme is recomposed by that change.
 *
 * @param onThemeChange Persist the new value. Called synchronously, before the animation starts,
 *   so the app underneath is already redrawn in the new appearance by the time the first hole is
 *   punched through the snapshot.
 */
@Composable
fun ThemeRevealHost(
    onThemeChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(0f) }

    /*
     * Which way this particular reveal runs.
     *
     * false - going dark. A hole opens out of the toggle and grows: the disc is the NEW
     *         appearance arriving, and the frozen light screen is eaten away from the toggle
     *         outwards.
     * true  - going light. The frozen dark screen is clipped INTO a disc that shrinks back into
     *         the toggle, so the reveal plays as the reverse of the one that brought it in.
     *
     * Latched once per reveal, at the moment the snapshot is published, and read by the draw
     * block. It must not be derived from the live theme value inside the Canvas: the theme flips
     * partway through the sequence, which would swap the geometry mid-animation.
     */
    var collapsing by remember { mutableStateOf(false) }

    /*
     * Whether [progress] has been reset for THIS reveal yet.
     *
     * The animation itself is unchanged and always was correct. The bug was one frame at the
     * front of it: progress keeps its value between reveals, so it still read 1f from the
     * previous switch at the moment the next snapshot was published, and the reset is a suspend
     * call that cannot land before that frame is drawn. So the overlay drew the finished state
     * once, then jumped back and ran.
     *
     * Rather than swap the driver out, the overlay simply draws the snapshot untouched until
     * the reset has happened - radius 0, meaning "old appearance, nothing revealed yet". That
     * is the correct first frame, and the animation proper is left exactly as it was.
     */
    var ready by remember { mutableStateOf(false) }

    /*
     * Capture is suspending now, so "a reveal is in flight" starts before the snapshot exists.
     * Without this flag a fast double tap would start two captures, and the second would
     * photograph a screen the first was about to freeze.
     */
    var capturing by remember { mutableStateOf(false) }

    val switcher = remember(view) {
        ThemeSwitcher { enabled, at ->
            /*
             * Re-entrancy: a second tap while a reveal is already running just commits. Taking a
             * fresh snapshot mid-animation would photograph the half-wiped screen and the two
             * reveals would fight over the same overlay.
             */
            if (snapshot != null || capturing) {
                /*
                 * A tap while a reveal is already running, or while its snapshot is still being
                 * taken, is DROPPED rather than committed. Committing would land as a hard cut,
                 * because the overlay on screen belongs to the previous switch; taking a fresh
                 * snapshot would photograph a half-wiped screen and the two reveals would fight
                 * over one overlay. The Switch reads the stored value, so a dropped tap leaves
                 * the thumb where it is instead of flicking over and snapping back.
                 */
            } else {
                capturing = true
                scope.launch {
                    val shot = captureWindow(view)
                    capturing = false
                    if (shot == null) {
                        onThemeChange(enabled)
                    } else {
                        origin = at ?: Offset(view.width / 2f, view.height / 2f)
                        // Going light runs the wipe backwards; going dark opens it outwards.
                        collapsing = !enabled
                        ready = false
                        snapshot = shot
                        try {
                            progress.snapTo(0f)
                            /*
                             * The hold. The frozen screen is already up and identical to what
                             * was there a moment ago, so nothing appears to happen - and then
                             * the theme is applied and the wipe uncovers it. Applying the theme
                             * here rather than before the hold is the whole point: it is what
                             * stops the toggle flipping on the frame you touched it.
                             */
                            delay(Motion.REVEAL_DELAY_MILLIS.toLong())
                            onThemeChange(enabled)
                            ready = true
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = Motion.REVEAL_MILLIS,
                                    easing = RevealEasing,
                                ),
                            )
                        } finally {
                            // finally, not just after animateTo: if this coroutine is cancelled
                            // by the activity going away mid-reveal, the overlay must still be
                            // released or the app comes back wearing a stale photograph.
                            snapshot = null
                        }
                    }
                }
            }
        }
    }

    /*
     * One Path, reused for every frame of every reveal.
     *
     * This used to be built inside the draw block, which meant a fresh Path and a fresh set of
     * geometry objects allocated on the main thread on each of the ~24 frames of the wipe. That
     * is a burst of garbage during the one animation on screen, and the collector landing in the
     * middle of it is what the stutter was. rewind() keeps the already-grown internal buffer and
     * only resets the contents, so after the first frame the reveal allocates nothing at all.
     */
    val revealPath = remember { Path() }

    CompositionLocalProvider(
        LocalThemeSwitcher provides switcher,
        LocalThemeRevealActive provides (snapshot != null || capturing),
    ) {
        Box(Modifier.fillMaxSize()) {
            content()

            val shot = snapshot
            if (shot != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val full = maxRadius(origin, size.width, size.height)

                    /*
                     * Two directions, one disc.
                     *
                     * Going dark, the disc is a HOLE punched through the frozen screen and grown
                     * outwards - the clip is Difference, so the snapshot survives everywhere the
                     * disc is not, and the new dark appearance shows through the middle.
                     *
                     * Going light, the disc is a WINDOW onto the frozen screen and shrunk back
                     * into the toggle - the clip is Intersect, so the snapshot survives only
                     * inside the disc, and the new light appearance is what is left around it.
                     * The old dark screen therefore retreats into the control that dismissed it,
                     * which is the reverse of the motion that brought it in.
                     *
                     * A previous revision deleted this branch on the grounds that a collapse
                     * makes the new appearance arrive at all four edges on the same frame, and
                     * that arriving everywhere at once is what a flash is. That observation is
                     * correct and it has not stopped being correct - it is inherent to running
                     * the wipe backwards and cannot be designed away, only paced. It is much
                     * less objectionable at the current duration than it was at the 400ms this
                     * was first judged at, because the edges now have time to move rather than
                     * simply appearing. If the light switch ever reads as a flash again, the
                     * lever is REVEAL_MILLIS, not this geometry.
                     */
                    val fraction = if (ready) progress.value else 0f
                    val radius = if (collapsing) (1f - fraction) * full else fraction * full

                    when {
                        // Collapsing and fully closed: the disc has reached the toggle and there
                        // is nothing of the old screen left to show. Drawing an empty clip would
                        // be a no-op anyway; skipping it avoids a pointless rasterise.
                        collapsing && radius <= 0f -> Unit

                        // Expanding, before the hole opens: the frozen screen is drawn whole.
                        !collapsing && radius <= 0f -> drawImage(shot)

                        else -> {
                            revealPath.rewind()
                            revealPath.addOval(Rect(origin, radius))
                            /*
                             * One convex oval either way, rather than an even-odd path built from
                             * a full-screen rectangle plus that oval. Identical pixels, but a
                             * simpler shape to rasterise on each frame, and the rectangle no
                             * longer has to be rebuilt every time the size is read.
                             */
                            clipPath(
                                revealPath,
                                clipOp = if (collapsing) ClipOp.Intersect else ClipOp.Difference,
                            ) {
                                drawImage(shot)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The reveal curve, fitted to the reference recording rather than chosen by feel.
 *
 * The radius of the disc was measured on every frame of the light -> dark switch (frames 407 to
 * 586) by segmenting each frame against the settled before/after frames and taking the radius
 * whose disc has the same area as the changed region. Normalising both axes gives 179 samples of
 * progress against radius, and a cubic Bezier fitted to those samples by least squares lands on
 * the curve below, with a root-mean-square error of 0.003 - i.e. the model and the recording
 * agree to within a third of one percent of the screen diagonal, on every frame.
 *
 * For comparison, on the same samples:
 *
 *   this curve            (0.48, 0.36, 0.57, 0.84)   rmse 0.003
 *   linear                                           rmse 0.055
 *   FastOutSlowIn         (0.40, 0.00, 0.20, 1.00)   rmse 0.131
 *   the previous curve    (0.22, 1.00, 0.36, 1.00)   rmse 0.350
 *
 * The previous curve was not slightly off, it was the wrong shape entirely. It was a hard
 * ease-out - by a third of the way through it had already covered three quarters of the
 * distance, so the disc shot out of the toggle and then crept. The reference does the opposite
 * of creeping: it is very close to linear, with only a gentle ease in at the start and a gentle
 * settle at the end. That near-constant edge speed is what makes it read as a wipe travelling
 * across the screen rather than something springing open.
 *
 * y1 = 0.36 and y2 = 0.84 straddling the diagonal is the signature of that near-linearity. If
 * this is ever retuned by hand, keep the curve close to the diagonal; pulling y1 up towards 1
 * is exactly the mistake that was made before.
 */
private val RevealEasing = CubicBezierEasing(0.48f, 0.36f, 0.57f, 0.84f)

/**
 * Distance from [origin] to the furthest corner.
 *
 * The reveal has to reach the corner that is hardest to get to, otherwise a switch triggered
 * near one edge finishes with a crescent of the old appearance still showing on the far side.
 */
private fun maxRadius(origin: Offset, width: Float, height: Float): Float {
    val dx = max(origin.x, width - origin.x)
    val dy = max(origin.y, height - origin.y)
    return hypot(dx, dy)
}

/**
 * Freezes the current frame. Null if that is not possible right now.
 *
 * PixelCopy first, View.draw second, and the order matters. View.draw re-runs the view tree into
 * a software canvas, which is not the same thing as reading the frame that is actually on screen:
 * anything drawn by the GPU rather than by the view tree does not come back, and on a
 * hardware-accelerated window the result can be blank. A blank snapshot is invisible - the theme
 * appears to change with no animation at all, which is exactly what a reveal that "does nothing"
 * looks like. PixelCopy reads the real composited frame and has neither problem, at the cost of
 * needing API 26 and a Window, so View.draw stays as the fallback for API 24-25.
 */
private suspend fun captureWindow(view: View): ImageBitmap? {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return null

    val window = view.hostWindow()
    if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val copied = runCatching { view.pixelCopy(window, width, height) }.getOrNull()
        if (copied != null) return copied
    }

    return runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
    // Throwable, including OutOfMemoryError, on purpose. A full-screen ARGB_8888 bitmap is around
    // 10MB and this is a decoration; it is never worth taking the process down for.
}

/** Reads the composited frame for this view's bounds. API 26+. */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
private suspend fun View.pixelCopy(window: Window, width: Int, height: Int): ImageBitmap? =
    suspendCancellableCoroutine { continuation ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val location = IntArray(2)
        getLocationInWindow(location)
        val bounds = android.graphics.Rect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height,
        )
        PixelCopy.request(
            window,
            bounds,
            bitmap,
            { result ->
                continuation.resume(
                    if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null,
                )
            },
            Handler(Looper.getMainLooper()),
        )
    }

/** The Activity window behind this view, unwrapping any ContextWrappers in between. */
private fun View.hostWindow(): Window? {
    var candidate = context
    while (candidate is ContextWrapper) {
        if (candidate is Activity) return candidate.window
        candidate = candidate.baseContext
    }
    return null
}
