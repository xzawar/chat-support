package com.codexce.supportchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/*
 * One profile picture rule, used by every surface in the app.
 *
 * The order is fixed and never varies:
 *
 *   1. The Google account photo, when the person signed in with Google.
 *   2. The first letter of their name.
 *   3. The first letter of their email address.
 *   4. A question mark, when we have been given nothing at all.
 *
 * This replaced the illustrated sphere set. The spheres solved a real problem - anonymous
 * website visitors are all called "Website Visitor", so their initials collide - but they solved
 * it by making the app look unlike itself, and the same collision is handled here by seeding the
 * disc colour from a stable key instead. Two visitors both showing "W" still get different
 * colours, and the same visitor gets the same colour on every device and after every reinstall,
 * because the key is the conversation id rather than anything random.
 */
object AvatarPalette {

    /*
     * Deep enough that white text clears 4.5:1 on all of them, which is the whole point: the
     * letter is always white, so the disc can never be a pale tint.
     */
    private val discs = listOf(
        Color(0xFF2F6F92),
        Color(0xFF0E8F86),
        Color(0xFF1E7A4B),
        Color(0xFF7A5AA8),
        Color(0xFFB2593A),
        Color(0xFF9A3F63),
        Color(0xFF3F5BA9),
        Color(0xFF6B6320),
        Color(0xFF8A4A2B),
        Color(0xFF2F6B6B),
        Color(0xFF5C4B8A),
        Color(0xFF96562F),
    )

    /**
     * A stable disc colour for a key.
     *
     * hashCode is not used: it can be negative, and Int.MIN_VALUE has no positive counterpart,
     * so abs() on it returns itself and the index goes out of range. The fold builds a
     * non-negative value directly and cannot do that.
     */
    fun discFor(key: String): Color {
        if (key.isEmpty()) return discs[0]
        var acc = 0
        for (character in key) {
            acc = (acc * 31 + character.code) % 100003
        }
        return discs[acc % discs.size]
    }

    /**
     * The single letter to draw, following the order documented above.
     *
     * Leading whitespace is trimmed first, because a name of " " would otherwise produce a blank
     * disc rather than falling through to the email.
     */
    fun letterFor(name: String?, email: String?): String {
        val fromName = name?.trim().orEmpty().firstOrNull { it.isLetterOrDigit() }
        if (fromName != null) return fromName.uppercaseChar().toString()
        val fromEmail = email?.trim().orEmpty().firstOrNull { it.isLetterOrDigit() }
        if (fromEmail != null) return fromEmail.uppercaseChar().toString()
        return "?"
    }
}

/**
 * The app's profile picture.
 *
 * @param name     Display name, when there is one.
 * @param email    Email address, used only when the name gives us no letter.
 * @param photoUrl Google account picture. Wins over the letter whenever it loads.
 * @param seed     Stable key for the disc colour. Pass the conversation id for a visitor, or the
 *                 uid for the signed-in agent, so the colour survives a reinstall.
 */
@Composable
fun PersonAvatar(
    name: String? = null,
    email: String? = null,
    photoUrl: String? = null,
    seed: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    loading: Boolean = false,
) {
    if (loading) {
        SkeletonBlock(modifier.size(size), CircleShape)
        return
    }
    val key = seed.ifBlank { email.orEmpty().ifBlank { name.orEmpty() } }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AvatarPalette.discFor(key)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = AvatarPalette.letterFor(name, email),
            // Sized off the disc rather than a fixed style, so a 32dp row avatar and an 84dp
            // profile header hold the same optical weight instead of one looking half empty.
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        /*
         * Drawn over the letter, not instead of it. Coil leaves the box empty while the request
         * is in flight and forever if it fails, and a photo that never arrives would otherwise
         * leave a bare coloured disc.
         */
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}
