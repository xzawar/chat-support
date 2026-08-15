package com.codexce.supportchat.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Phase 10 - the reference restyle.
 *
 * The dark appearance is no longer the WhatsApp near-black. It is a true-black page with a
 * single lifted card colour on top of it, which is the structure the reference app uses:
 *
 *   #000000  the page. Nothing is drawn on this directly except cards and text.
 *   #181818  every card, top bar, bubble and the tab bar. One step, not a ramp.
 *   #1C1C1C  menus and dialogs, which float above cards and must separate from them.
 *   #272727  search pills, pressed rows, the tab bar's own fill.
 *
 * True black is not a style choice on OLED: the page pixels are switched off, so the cards read
 * as genuinely lifted rather than as a slightly different grey. It also means hairlines have to
 * be brighter than they were, because #222D34 on #0B141A was visible and the same line on
 * #181818 is not.
 *
 * The accent is the reference blue rather than the launcher blue. See TgBlue below.
 *
 * The light appearance mirrors the same structure: a faintly grey page carrying white cards.
 * The recording only ever showed the light chat list, never light Settings, so the light card
 * structure is inferred from the dark one rather than copied. If it looks wrong it is this
 * decision that is wrong, and it is two constants.
 *
 * The legacy palette below has been cut down to the three names that still have call sites:
 * SkyLight, BrandFill and BrandDeep. The WhatsApp greys and the unused sky ramp shades were
 * deleted outright - they were dead from the moment the schemes stopped referencing them, and
 * an earlier version of this comment claimed WaSurface still had call sites when it had none.
 */

// ---------------------------------------------------------------------------
// Reference palette
// ---------------------------------------------------------------------------

/**
 * The accent. FAB, links, active tab, switch tracks, badges, checkboxes, the whole lot.
 *
 * White clears 3.3:1 on this, which is below the 4.5:1 body-text bar and above the 3:1 bar for
 * large text and UI components. That is the same trade the reference app makes: this colour is
 * only ever a fill behind a glyph or a short button label, never behind a paragraph.
 */
val TgBlue = Color(0xFF3390EC)

/** Pressed and hovered states of the accent. */
val TgBluePressed = Color(0xFF2B7FD4)

/**
 * The launcher icon's blue, under a name that says what it is for.
 *
 * It is the same value as [TgBluePressed] by coincidence rather than by design: the supplied
 * icon artwork happens to use the darker end of the same ramp. They are aliased instead of
 * duplicated so a future icon change only has to move one of them, but do not assume changing
 * this one is safe for pressed states, or the reverse.
 *
 * Used by the startup screen's loading bar, which sits directly under the launcher icon in the
 * cold-start sequence and would otherwise hand over from icon blue to an unrelated teal.
 */
val IconBlue = TgBluePressed

/** The accent at rest on a dark page, for text and glyphs rather than fills. */
val TgBlueLight = Color(0xFF62A8F0)

/** The page. Switched-off pixels on OLED. */
val TgBlack = Color(0xFF000000)

/** Cards, top bars, bubbles. The only lifted surface on the dark page. */
val TgCard = Color(0xFF181818)

/** Menus and dialogs, which float above cards and need to separate from them. */
val TgElevated = Color(0xFF1C1C1C)

/** Search pills, pressed rows, the tab bar fill. */
val TgPill = Color(0xFF272727)

/** One step above the pill, for a selected row inside a menu. */
val TgPillHigh = Color(0xFF2E2E2E)

/** Subtitles, timestamps, placeholders, inactive tab glyphs. */
val TgSecondary = Color(0xFF7B7B7B)

/** Hairlines between rows inside a card. Brighter than the old ones: #181818 hides them. */
val TgHairline = Color(0xFF262626)

/** Destructive. Sign out, delete, the trailing swipe panel. */
val TgRedDark = Color(0xFFEC4E4E)

// Light mirrors of the above.

/** The light page. Faintly grey so that white cards read as cards. */
val TgLightPage = Color(0xFFF4F4F4)

/** Light cards, top bars, bubbles. */
val TgLightCard = Color(0xFFFFFFFF)

/** Light search pills and pressed rows. */
val TgLightPill = Color(0xFFEDEDED)

/** Light secondary text. */
val TgLightSecondary = Color(0xFF777777)

/** Light hairlines. */
val TgLightHairline = Color(0xFFE6E6E6)

/** Light primary text. Not pure black: pure black on white vibrates at small sizes. */
val TgInk = Color(0xFF0F0F0F)

/** Light destructive. */
val TgRedLight = Color(0xFFE14545)

// ---------------------------------------------------------------------------
// Legacy brand palette
//
// Not wired into either scheme. These three survive only because they still have call sites:
// SkyLight and BrandFill in the launcher artwork and the web console, BrandDeep on white-label
// fills. Anything added here that a scheme does not reference will be dead on arrival.
// ---------------------------------------------------------------------------

/** Top stop of the launcher gradient. */
val SkyLight = Color(0xFF51C9FD)

val PureWhite = Color(0xFFFFFFFF)

/** Large solid brand fills. */
val BrandFill = Color(0xFF69C5FF)

/**
 * Brand fill for anything carrying a white text label.
 *
 * This was an alias for SkyShadow, the deepest shade of the old icon ramp. SkyShadow itself had
 * no remaining call sites, so the literal is inlined here rather than keeping a second name
 * alive purely to be aliased once.
 */
val BrandDeep = Color(0xFF2F6F92)

// ---------------------------------------------------------------------------
// Schemes
// ---------------------------------------------------------------------------

/*
 * Token mapping, and why each one is what it is:
 *
 *   background              the page
 *   surface                 cards, top bars, bubbles - the single lifted step
 *   surfaceContainerLow     grouped Settings cards. Same value as surface on purpose: a card
 *                           on the page and a top bar on the page are the same height.
 *   surfaceContainer        dialogs and menus, one step up so they clear a card underneath
 *   surfaceContainerHigh    search pills, the tab bar, pressed rows
 *   surfaceContainerHighest a selected row inside a menu
 *   outlineVariant          hairlines inside a card
 *   outline                 the rare real border
 *   onSurfaceVariant        every subtitle and inactive glyph in the app
 */

val LightColors = lightColorScheme(
    primary = TgBlue,
    onPrimary = PureWhite,
    primaryContainer = TgBlue,
    onPrimaryContainer = PureWhite,
    inversePrimary = TgBlueLight,
    secondary = TgBluePressed,
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFFDCEBFB),
    onSecondaryContainer = Color(0xFF0B3D66),
    tertiary = TgBlue,
    onTertiary = PureWhite,
    tertiaryContainer = Color(0xFFDCEBFB),
    onTertiaryContainer = Color(0xFF0B3D66),
    background = TgLightPage,
    onBackground = TgInk,
    surface = TgLightCard,
    onSurface = TgInk,
    surfaceVariant = TgLightPill,
    onSurfaceVariant = TgLightSecondary,
    surfaceTint = TgBlue,
    surfaceContainerLowest = TgLightCard,
    surfaceContainerLow = TgLightCard,
    surfaceContainer = TgLightCard,
    surfaceContainerHigh = TgLightPill,
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFFC9C9C9),
    outlineVariant = TgLightHairline,
    inverseSurface = Color(0xFF2B2B2B),
    inverseOnSurface = PureWhite,
    error = TgRedLight,
    onError = PureWhite,
    errorContainer = Color(0xFFFBE4E2),
    onErrorContainer = Color(0xFF5C1610),
    scrim = Color(0xFF000000),
)

val DarkColors = darkColorScheme(
    primary = TgBlue,
    /*
     * White on the accent in both appearances. One answer rather than a per-screen theme check,
     * and it is what the reference does: the active tab glyph, the FAB glyph and the checkbox
     * tick are all white on #3390EC.
     */
    onPrimary = PureWhite,
    primaryContainer = TgBlue,
    onPrimaryContainer = PureWhite,
    inversePrimary = TgBlue,
    secondary = TgBlueLight,
    onSecondary = TgBlack,
    secondaryContainer = Color(0xFF1B3F63),
    onSecondaryContainer = PureWhite,
    tertiary = TgBlueLight,
    onTertiary = TgBlack,
    tertiaryContainer = Color(0xFF1B3F63),
    onTertiaryContainer = PureWhite,
    background = TgBlack,
    onBackground = PureWhite,
    surface = TgCard,
    onSurface = PureWhite,
    surfaceVariant = TgPill,
    onSurfaceVariant = TgSecondary,
    surfaceTint = TgBlue,
    surfaceContainerLowest = TgBlack,
    surfaceContainerLow = TgCard,
    surfaceContainer = TgElevated,
    surfaceContainerHigh = TgPill,
    surfaceContainerHighest = TgPillHigh,
    outline = Color(0xFF3A3A3A),
    outlineVariant = TgHairline,
    inverseSurface = Color(0xFFF0F0F0),
    inverseOnSurface = TgBlack,
    error = TgRedDark,
    onError = PureWhite,
    errorContainer = Color(0xFF5C1B1B),
    onErrorContainer = Color(0xFFFBE4E2),
    scrim = Color(0xFF000000),
)
