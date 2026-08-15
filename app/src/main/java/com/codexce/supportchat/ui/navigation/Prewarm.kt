package com.codexce.supportchat.ui.navigation

import android.content.Context
import android.util.DisplayMetrics
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.codexce.supportchat.data.WallpaperOption
import com.codexce.supportchat.data.WallpaperSelection

/**
 * Starts the expensive part of a screen before the screen is asked for.
 *
 * A push waits PAGE_DEFER_MILLIS before it moves, and that wait is dead time: the destination
 * has already been composed and is sitting still. This fills it. Anything enqueued here is
 * fetched and decoded on Coil's own IO dispatchers during the pause, so by the time the slide
 * begins the bitmap is in memory and the frame that draws it has nothing left to do.
 *
 * What this can and cannot move off the main thread, stated plainly:
 *
 * - Image fetch and decode: yes. That is the work worth moving, and it is the only work in a
 *   chat open that is measured in tens of milliseconds rather than fractions of one. A
 *   full-screen wallpaper is by far the largest bitmap the app ever decodes.
 * - Composition, layout and draw: no, and nothing can. Compose runs those on the main thread by
 *   definition. The defer helps them by giving them a frame of their own instead of making them
 *   share one with a running animation, which is a different fix for a different problem.
 *
 * Called only on the paths that need it, not at startup. Warming a wallpaper the user may never
 * scroll to costs memory for nothing.
 */
object Prewarm {

    /**
     * Warms the chat wallpaper.
     *
     * Sized to the display on purpose. Coil's memory-cache key includes the requested size, so a
     * prefetch at the wrong size is not a hit later - it still warms the disk cache and the
     * decoder, but the bitmap gets built twice. The wallpaper is drawn at fillMaxSize with
     * ContentScale.Crop, so the screen's own pixel size is the size that will be asked for.
     */
    fun chatWallpaper(context: Context, selection: WallpaperSelection) {
        if (!selection.isSet) return

        val model: Any = when {
            selection.option == WallpaperOption.Custom ->
                selection.customUri ?: return
            else -> selection.option.drawable ?: return
        }

        val metrics: DisplayMetrics = context.resources.displayMetrics
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(metrics.widthPixels, metrics.heightPixels)
            .scale(Scale.FILL)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()

        // enqueue, never execute: this returns immediately and must never block the tap that
        // triggered it. If the decode outlives the defer the screen simply draws when it is
        // ready, exactly as it did before - a slow prewarm is never worse than no prewarm.
        context.imageLoader.enqueue(request)
    }
}
