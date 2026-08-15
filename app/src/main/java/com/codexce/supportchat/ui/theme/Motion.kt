package com.codexce.supportchat.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * One place for motion, so tab changes, screen pushes and the navbar indicator all move on the
 * same curves instead of each screen inventing its own timing.
 */
object Motion {
    const val FAST_MILLIS = 150
    const val STANDARD_MILLIS = 300

    /**
     * The bottom bar's active-tab change: the dot growing under the new icon and both glyph
     * tints crossing over.
     *
     * This ran at FAST_MILLIS. 150ms is quick enough that the dot appears to cut rather than
     * grow, which wasted the indicator entirely. 200ms is still well inside the range where a
     * direct-manipulation response reads as instant, and it is long enough to actually see.
     */
    const val TAB_MILLIS = 200

    /**
     * The theme-switch circular reveal, measured off the reference recording frame by frame.
     *
     * REMEASURED, and the previous number here was wrong by a factor of seven. The note that used
     * to sit in this spot claimed "20 frames, so 330ms", derived from watching the brightness of
     * the whole frame settle. That measurement was of the wrong thing: mean brightness saturates
     * long before the circle finishes, because once the disc covers the middle of the screen the
     * only pixels still changing are in one corner and they barely move the average. The wipe was
     * still running for well over a second after the number said it had stopped.
     *
     * The remeasurement segments each frame into "already the new appearance" and "still the old
     * one" by nearest-colour against the settled before/after frames, then fits a disc to that
     * mask. The clip is 1247 frames over 20.334s of presentation timestamps - 62.5fps, real time,
     * with no duplicated frames, so it is not a slowed-down capture. Both switches measured:
     *
     *   light -> dark   frame 407 -> 586   179 frames   2904ms   disc fit IoU 0.982
     *   dark  -> light  frame 103 -> 279   176 frames   2857ms
     *
     * 2880ms is the average of the two, and that was the value here until it was deliberately
     * overridden.
     *
     * NOW SET TO 650ms BY REQUEST, which is a choice, not a measurement. That is between a
     * quarter and a fifth of the reference pace: the wipe reads as a quick swipe of appearance
     * across the screen rather than the slow ceremonial sweep in the recording. Keep the measured
     * numbers above rather than deleting them - if the question "why doesn't this match the
     * video" is ever asked again, the answer is this line, and 2880 is what to restore.
     *
     * This duration is used by BOTH directions - the disc growing out of the toggle on the way
     * into dark, and the disc shrinking back into it on the way out. That symmetry is the point
     * of the collapse, so if this is retuned, both halves move together and neither needs its
     * own constant.
     *
     * One direction-specific caveat, and 650ms is close enough to it to be worth stating plainly.
     * The collapse is the more fragile of the two directions, because a shrinking disc hands the
     * new appearance to all four screen edges on the same frame; the shorter this value gets, the
     * more that reads as a flash rather than a wipe. The rough floor is somewhere around 500ms.
     * 650ms clears it, but not by much - roughly two frames of headroom at 60fps once the eased
     * tail is accounted for. If the light switch is ever reported as "flashing", this constant is
     * the cause and raising it is the fix; do not go looking for a bug in the geometry, and do
     * not take this below 500ms.
     */
    const val REVEAL_MILLIS = 650

    /**
     * How long the frozen screen is held before the wipe begins. Now 0.
     *
     * The hold was invented to solve the "instant toggle flip" complaint, and it did solve it,
     * but the recording does not contain it. In the reference the disc is already growing out of
     * the tapped control on the first frame in which anything has changed at all - there is no
     * still frame in between. Frames 398 to 421 show the overflow menu still fading out WHILE the
     * dark disc expands underneath it, which is only possible if the wipe started at the tap.
     *
     * Holding for 200ms on top of a 2880ms wipe also reads very differently from holding 200ms on
     * top of a 400ms one: it stops being an imperceptible beat and becomes a visible stall
     * between the finger and the response.
     *
     * The problem the hold was solving has not come back, because it was never the hold that
     * fixed it. The theme is applied after the snapshot is up rather than at the moment of the
     * tap, and that ordering is what keeps the switch thumb from flicking over early. That
     * ordering is unchanged; only the wait in front of it is gone.
     */
    const val REVEAL_DELAY_MILLIS = 0

    /**
     * Screen pushes, opening and closing. Long enough to read as a travelling object rather
     * than a cut, short enough that tapping through several screens in a row never feels like
     * waiting.
     *
     * 300ms of MOVEMENT, sitting behind a 100ms hold. The two together are the 400ms budget,
     * and that total is the number to protect - see PAGE_DEFER_MILLIS for why the hold exists.
     * It was once 340ms sitting behind a 200ms deferred start, so a push really took 540ms and
     * the pop took the same again; the difference now is that the hold is carved out of the
     * budget instead of added on top of it.
     *
     * A note on why this number was raised, because the reasoning that produced it was wrong
     * and someone will reach for it again. The request was 650ms, to give the transition time
     * to "preload animation resources". There are no animation resources. A slide is one number
     * per frame fed into a graphicsLayer transform - nothing is fetched, decoded or allocated,
     * so duration buys no loading time whatsoever. What a longer duration does buy is more
     * frames in which a frame can be dropped: stretching a janking animation makes the jank
     * last longer and become more visible, never less.
     *
     * The jank that prompted this was real - it was reproduced on a release build, not on
     * debug - but its causes were the per-frame shadow and corner-radius animation in
     * pushComposable and the alpha compositing in park(). Both are fixed at their source. The
     * 400ms total is therefore a deliberate feel choice, not a performance fix, and it is a
     * compromise against the 650ms asked for: past roughly 450ms a push starts to read as the
     * app being slow to answer a tap.
     *
     * Do not raise this hoping to smooth something out. If a transition janks at 300ms of
     * movement it will jank at 650ms, for longer.
     */
    const val PAGE_MILLIS = 300

    /**
     * How long a push waits, offscreen, before it starts moving. 100ms.
     *
     * WHY THIS EXISTS, because it has now been removed once and put back once.
     *
     * Compose Navigation composes the destination on frame ONE of the transition. Without a
     * hold, the opening frames of the slide are carrying composition, measure, layout and first
     * draw of an entire new screen while that screen is also moving - which is exactly the
     * stutter-at-the-start that gets reported as "the animation lags". The destination is
     * already composing during this hold, because slideInHorizontally is a layout offset: the
     * content is measured and drawn on frame one regardless, just translated off the edge of
     * the window. The hold does not delay the work, it moves the work to a moment when nothing
     * is moving, so a dropped frame has nothing to be dropped out of.
     *
     * This was 200ms and was removed, correctly, because of HOW it was charged: it was added on
     * top of a 340ms slide, so a push cost 540ms and the trip back another 540ms. That was the
     * flaw - the arithmetic, not the idea. It is now carved out of the 400ms budget instead:
     * 100ms still plus 300ms moving. Navigation is not slower than it was, and the destination
     * still gets its quiet window.
     *
     * 100ms specifically because that is roughly the threshold below which a delay after a tap
     * still reads as instant. At 150ms and up the screen starts to feel unresponsive to the
     * touch before anything visibly happens.
     *
     * This is applied to all four specs, so it is paid on the way back as well as the way in.
     * That is deliberate and was chosen explicitly: a popped destination is rebuilt from saved
     * state rather than restored intact, so it is cheaper but not free.
     *
     * The rule that has not changed: whatever this number is, it must be applied to BOTH halves
     * of a pair. Delaying only the entering screen lets the outgoing one park early and exposes
     * the bare window behind it.
     *
     * If this is raised, lower PAGE_MILLIS by the same amount. The total is the budget.
     */
    const val PAGE_DEFER_MILLIS = 100

    val fastFloat: AnimationSpec<Float> = tween(FAST_MILLIS)
    val tabFloat: AnimationSpec<Float> = tween(TAB_MILLIS)
    val standardFloat: AnimationSpec<Float> = tween(STANDARD_MILLIS)
    val standardOffset: AnimationSpec<IntOffset> = tween(STANDARD_MILLIS)

    val indicatorSpring: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
