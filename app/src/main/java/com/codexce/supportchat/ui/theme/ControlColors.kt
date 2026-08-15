package com.codexce.supportchat.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable

/*
 * Shared colours for the two controls that sit on a brand fill.
 *
 * These exist so the split described in Color.kt is applied in one place rather than argued
 * about at every call site: a switch is a big solid shape and can take the bright fill, a button
 * carries text and cannot.
 */

/*
 * A white thumb on BrandDeep, in both appearances.
 *
 * The track used to be BrandFill (#69C5FF) with an onPrimary thumb. Once the dark appearance
 * moved to WhatsApp surfaces that combination stopped working: onPrimary is now white in both
 * schemes, and a white thumb on a #69C5FF track is barely a shape. BrandDeep is the same fill
 * the buttons use, so the two controls finally agree, and it reads as "on" at a glance against
 * the near-black sheet behind it.
 */
/*
 * Phase 10: the track is the reference accent, not BrandDeep.
 *
 * The reference draws an "on" switch as a solid #3390EC track with a white thumb, and it is the
 * same blue as the active tab and every link, so the whole app agrees on what "on" looks like.
 * BrandDeep was chosen when the accent could not be trusted behind a white thumb; #3390EC is
 * light enough to read as a colour and dark enough that the white thumb still cuts out of it.
 */
@Composable
fun supportSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = PureWhite,
    checkedTrackColor = TgBlue,
    checkedBorderColor = TgBlue,
    checkedIconColor = PureWhite,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

/*
 * Filled buttons carry a white label in both appearances.
 *
 * This used to read onPrimary, which is dark ink in the dark scheme, so every filled button in
 * dark mode - Subscribe most visibly - printed near-black text on the deep blue fill. That is
 * roughly a 2:1 contrast ratio: legible in a screenshot, not legible on a phone.
 *
 * BrandDeep is #2F6F92, dark enough that white clears 7:1 against it, so one rule works for
 * both appearances and there is no theme check to get out of sync. The switch below still uses
 * onPrimary, because a switch thumb is a shape rather than text and the bright track needs the
 * dark thumb to stay visible.
 */
/*
 * Phase 10: filled buttons take the accent directly.
 *
 * White on #3390EC is about 3.3:1. That is under the 4.5:1 body-text bar and over the 3:1 bar
 * for large text and UI components, which is the category a button label is in. The reference
 * app makes exactly this trade on its own filled buttons. Nothing longer than a button label is
 * ever printed on this fill.
 */
@Composable
fun supportButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = TgBlue,
    contentColor = androidx.compose.ui.graphics.Color.White,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
