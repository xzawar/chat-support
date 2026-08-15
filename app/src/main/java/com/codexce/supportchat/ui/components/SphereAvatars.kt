package com.codexce.supportchat.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.codexce.supportchat.R
import kotlin.math.absoluteValue

/**
 * Profile pictures for visitors, drawn from the sphere illustration set.
 *
 * Website visitors are anonymous: there is no photo to show and initials from "Website Visitor"
 * are the same two letters for everyone. Picking a sphere from the conversation id instead gives
 * every person a face that is distinct, colourful, and - because the id never changes - always
 * the same one for the same person, on every device and after every reinstall. Nothing is stored
 * and nothing is random at runtime.
 */
object SphereAvatars {

    private val faces = listOf(
        R.drawable.il_sphere_pink_mini,
        R.drawable.il_sphere_green_wink,
        R.drawable.il_sphere_coral_surprised,
        R.drawable.il_sphere_blue_smile,
        R.drawable.il_sphere_yellow_smile,
        R.drawable.il_sphere_orange_curious,
        R.drawable.il_sphere_red_happy,
        R.drawable.il_sphere_blue_mini,
        R.drawable.il_sphere_yellow_mini,
        R.drawable.il_sphere_peach_smile,
        R.drawable.il_sphere_pink_big,
        R.drawable.il_sphere_green_swirl,
        R.drawable.il_sphere_green_brow,
        R.drawable.il_sphere_yellow_swoop,
    )

    /**
     * A stable face for a key. hashCode alone can be negative and Int.MIN_VALUE has no positive
     * counterpart, so the fold below builds a non-negative value directly instead.
     */
    @DrawableRes
    fun faceFor(key: String): Int {
        if (key.isEmpty()) return faces[0]
        var acc = 0
        for (character in key) {
            acc = (acc * 31 + character.code) % 100003
        }
        return faces[acc.absoluteValue % faces.size]
    }
}

/**
 * A visitor avatar: the illustrated face on a soft tinted disc.
 *
 * [photoUrl] still wins when one exists (an agent's Google picture), so this can be used
 * everywhere InitialsAvatar was without losing real photos.
 */
@Composable
fun VisitorAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    loading: Boolean = false,
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
        Image(
            painter = painterResource(SphereAvatars.faceFor(seed)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Slightly larger than the disc so the sphere fills it edge to edge rather than
            // floating in the middle with a ring of background around it.
            modifier = Modifier.size(size * 1.02f),
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
