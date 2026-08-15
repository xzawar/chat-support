package com.codexce.supportchat.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.data.OwnerPhoto

/**
 * The owner's avatar, with the owner's own choice taking priority over Google's.
 *
 * Order is: the picture the owner picked from their gallery, then the Google account photo,
 * then initials. The Google photo stays the default and is what everyone sees until someone
 * deliberately overrides it, which is the behaviour that was asked for.
 *
 * This exists as a wrapper rather than as a change to [PersonAvatar] because PersonAvatar also
 * draws visitors, and a visitor must never be shown the owner's face.
 *
 * The stored value is base64, not a URL, so it cannot go through Coil like photoUrl does. It is
 * decoded once and remembered against the string, so scrolling does not re-decode it - it is a
 * 256px thumbnail under a 20 KB ceiling, so that decode is cheap, but doing it every frame
 * would still be waste.
 *
 * FilterQuality.High on the draw, not the default.
 *
 * Compose defaults to FilterQuality.Low, which is bilinear. Bilinear is the right choice when a
 * bitmap is being drawn at roughly its own size, and the wrong one when it is being minified by
 * more than about half - which is exactly this case, because the source is 256px and the common
 * draw sizes are 52dp and 64dp. Minifying past 2x with bilinear samples too few of the source
 * pixels and drops detail, and the result looks soft in a way that is easy to mistake for the
 * stored picture being low quality. High is mipmap-filtered and holds the detail that the
 * larger source now actually contains.
 */
@Composable
fun OwnerAvatar(
    name: String,
    email: String,
    photoUrl: String?,
    seed: String,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
) {
    val stored by OwnerPhoto.photo.collectAsStateWithLifecycle()
    val bitmap = remember(stored) { OwnerPhoto.decode(stored) }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(modifier) {
            PersonAvatar(
                name = name,
                email = email,
                photoUrl = photoUrl,
                seed = seed,
                size = size,
            )
        }
    }
}

/**
 * The same override, for the collapsing Settings header.
 *
 * That header draws [InitialsAvatar] rather than [PersonAvatar] because it works from a
 * pre-computed initials string, not a name and email. Without this the owner would set a photo
 * on the Account page and still see their Google photo one screen up, which looks like the
 * change did not save.
 */
@Composable
fun OwnerInitialsAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    loading: Boolean = false,
    photoUrl: String? = null,
) {
    val stored by OwnerPhoto.photo.collectAsStateWithLifecycle()
    val bitmap = remember(stored) { OwnerPhoto.decode(stored) }

    if (bitmap != null && !loading) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        InitialsAvatar(
            initials = initials,
            modifier = modifier,
            size = size,
            loading = loading,
            photoUrl = photoUrl,
        )
    }
}
