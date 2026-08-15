package com.codexce.supportchat.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.codexce.supportchat.ui.components.AvatarPalette

/**
 * The notification tray's copy of [com.codexce.supportchat.ui.components.PersonAvatar].
 *
 * The tray needs a Bitmap, not a composable, so the same two rules - a stable disc colour from
 * the conversation id and a single white letter from the name - are drawn here by hand. Sharing
 * AvatarPalette rather than repeating the colour list is what keeps the face in the tray and the
 * face in the inbox from drifting apart.
 *
 * This replaced a lookup into the illustrated sphere drawables, which no longer exist.
 */
object AvatarBitmap {

    fun forPerson(name: String?, seed: String, sizePx: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AvatarPalette.discFor(seed).toArgb()
        }
        canvas.drawCircle(radius, radius, radius, disc)

        val letter = AvatarPalette.letterFor(name, null)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = sizePx * 0.44f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        /*
         * Centred on the glyph's own bounds, not on the font metrics. Ascent/descent centring
         * leaves a capital letter sitting visibly high in the disc, because the descent it
         * reserves space for is never used by "W".
         */
        val bounds = Rect()
        text.getTextBounds(letter, 0, letter.length, bounds)
        canvas.drawText(letter, radius, radius + bounds.height() / 2f, text)

        return bitmap
    }
}
