package com.codexce.supportchat.ui.components

import androidx.annotation.DrawableRes
import com.codexce.supportchat.R

/**
 * One icon language across the whole app: 24dp vector drawables, 2dp round-capped strokes,
 * no filled/outlined mixing except the deliberate filled tab-bar variants.
 *
 * The weight moved from 1.6dp to 2dp when the set was redrawn. 1.6dp is fine at 24dp and goes
 * thin and characterless at the 20dp the settings list and the top bars actually use, which is
 * where almost every icon in this app is seen. The geometry is also rounder now: rectangles
 * carry a large corner radius and arms meet in curves rather than corners.
 *
 * Every path here is byte-identical to the matching entry in the web console's icons.js. That
 * is deliberate and worth preserving. The two surfaces are the same product and an icon that
 * is merely similar across them is more jarring than one that is openly different.
 */
object AppIcons {
    @DrawableRes val ChatOutline = R.drawable.ic_nav_chat
    @DrawableRes val ChatFilled = R.drawable.ic_nav_chat_filled
    @DrawableRes val MailOutline = R.drawable.ic_nav_mail
    @DrawableRes val MailFilled = R.drawable.ic_nav_mail_filled
    @DrawableRes val SocialOutline = R.drawable.ic_nav_social
    @DrawableRes val SocialFilled = R.drawable.ic_nav_social_filled

    /**
     * The WhatsApp mark, for the Help screen contact row.
     *
     * It is a white glyph with no colour of its own. SettingsRow already paints a white
     * icon onto a coloured tile, so the green comes from that tile rather than from the
     * drawable, which keeps this one file usable anywhere else on any background. The
     * Help screen sets the tile to the WhatsApp green.
     *
     * Before this, that row borrowed the bar chart glyph, which had nothing to do with
     * WhatsApp and only survived because nobody looked at it after wiring the link up.
     */
    @DrawableRes val WhatsApp = R.drawable.ic_whatsapp

    @DrawableRes val Back = R.drawable.ic_back
    @DrawableRes val Send = R.drawable.ic_send
    @DrawableRes val Search = R.drawable.ic_search
    @DrawableRes val CloseTicket = R.drawable.ic_close_ticket

    /** The mirror of CloseTicket, on the same circle, so the pair reads as one control in two states. */
    @DrawableRes val ReopenTicket = R.drawable.ic_reopen_ticket

    /** Opens the visitor profile from the conversation header. */
    @DrawableRes val Person = R.drawable.ic_person

    /** Keep Chat: exempts a thread from the 24-hour purge. */
    @DrawableRes val Pin = R.drawable.ic_pin
    @DrawableRes val Delete = R.drawable.ic_delete
    @DrawableRes val Settings = R.drawable.ic_settings

    /** Top-bar affordance that turns the Accounts profile rows into fields. */
    @DrawableRes val Pencil = R.drawable.ic_pencil

    /** Dial handset, used by the Help contact list. */
    @DrawableRes val Phone = R.drawable.ic_phone

    /** Three-line menu, used in the inbox top bar in place of the gear. */
    @DrawableRes val Menu = R.drawable.ic_menu
    @DrawableRes val Moon = R.drawable.ic_moon
    @DrawableRes val Bell = R.drawable.ic_bell
    @DrawableRes val Lock = R.drawable.ic_lock
    @DrawableRes val Unlock = R.drawable.ic_unlock

    /** Bookmark-style save. Fills amber once the thread is saved. */
    @DrawableRes val SaveBookmark = R.drawable.ic_save_bookmark
    @DrawableRes val Cloud = R.drawable.ic_cloud
    @DrawableRes val Help = R.drawable.ic_help
    @DrawableRes val Grid = R.drawable.ic_grid
    @DrawableRes val ChevronRight = R.drawable.ic_chevron_right
    @DrawableRes val Logout = R.drawable.ic_logout

    /**
     * A monitor, for "Link a computer" and the pairing screen.
     *
     * Both used to borrow [Globe] from "Link your website", which made two unrelated rows read
     * as the same action. Pairing is about this machine, not about a domain.
     */
    @DrawableRes val Monitor = R.drawable.ic_monitor
    @DrawableRes val Plus = R.drawable.ic_plus
    @DrawableRes val Check = R.drawable.ic_check
    @DrawableRes val MailOpen = R.drawable.ic_mail_open
    @DrawableRes val Chart = R.drawable.ic_chart
    @DrawableRes val Mic = R.drawable.ic_mic
    @DrawableRes val Calendar = R.drawable.ic_calendar
    @DrawableRes val Heart = R.drawable.ic_heart
    @DrawableRes val Palette = R.drawable.ic_palette

    /*
     * Purpose-built glyphs. Before these, half the settings list shared one generic cloud:
     * "Link your website", "Stay connected" and "Storage and data" were the same picture three
     * times, which means the icon column carried no information at all and the eye had to read
     * every label. Each row now has a glyph that names its own subject.
     *
     * All of them are single-stroke, 1.6dp, on the same 24dp grid as the existing set, so they
     * sit at one optical weight rather than looking like clip art dropped in from elsewhere.
     */
    /** Globe with meridians - a website, not a cloud. */
    @DrawableRes val Globe = R.drawable.ic_globe

    /** Payment card - subscription and billing. */
    @DrawableRes val Card = R.drawable.ic_card

    /** Broadcast arcs - a live connection being held open. */
    @DrawableRes val Broadcast = R.drawable.ic_broadcast

    /** Power symbol - launch on boot. */
    @DrawableRes val Power = R.drawable.ic_power

    /** Picture frame - chat wallpaper. */
    @DrawableRes val Image = R.drawable.ic_image

    /** Stacked cylinders - stored data. */
    @DrawableRes val Database = R.drawable.ic_database

    /** Shield with a tick - privacy and retention. */
    @DrawableRes val Shield = R.drawable.ic_shield

    /** Brush sweeping - clearing the cache. */
    @DrawableRes val Sweep = R.drawable.ic_sweep

    /** Arrow into a tray - exporting a copy of your data. */
    @DrawableRes val Download = R.drawable.ic_download

    /*
     * Filled variants, used only by the Settings rows.
     *
     * The tile these sit in shrank from 40dp to 32dp and the glyph from 24dp to 18dp,
     * and a 2dp stroke does not survive that - it stops being a line around a shape and
     * becomes most of the shape. Solid geometry stays legible at any size.
     *
     * The outline originals are deliberately still here. They are correct in empty
     * states, headers and buttons, where the glyph is large and not on a coloured tile,
     * and several screens still use them.
     */
    val PersonSolid = R.drawable.ic_person_solid
    val MonitorSolid = R.drawable.ic_monitor_solid
    val CardSolid = R.drawable.ic_card_solid
    val MoonSolid = R.drawable.ic_moon_solid
    val ImageSolid = R.drawable.ic_image_solid
    val DatabaseSolid = R.drawable.ic_database_solid
    val HelpSolid = R.drawable.ic_help_solid
    val LogoutSolid = R.drawable.ic_logout_solid

    /*
     * Replacements for four settings glyphs that were each saying the wrong thing. Broadcast
     * arcs for "Stay connected" read as transmitting and were near-identical to the bell one
     * row below. A bell for "Instant notifications" was the same picture as the row it sits
     * under. A globe for "Link your website" is a planet, not a page. A power symbol for
     * "Autostart" is the universal OFF switch, on a row that turns something on.
     *
     * The four glyphs they replaced (GlobeSolid, BroadcastSolid, BellSolid, PowerSolid) were
     * left declared here afterwards with no remaining call sites, along with their drawables.
     * Both are now deleted. If a solid globe or bell is ever wanted again, re-add it knowingly
     * rather than inheriting a glyph that was already judged wrong for its row.
     */
    val WifiSolid = R.drawable.ic_wifi_solid
    val ZapSolid = R.drawable.ic_zap_solid
    val WindowSolid = R.drawable.ic_window_solid
    val RocketSolid = R.drawable.ic_rocket_solid

    /*
     * The visitor profile chips. The old set borrowed whatever was nearest: a pin for Keep,
     * and a circled cross and circled arrow for Close and Reopen that were the same circle
     * twice and read as "cancel" and "refresh".
     */
    val Bookmark = R.drawable.ic_bookmark
    val ArchiveDown = R.drawable.ic_archive_down
    val ArchiveUp = R.drawable.ic_archive_up

    /* The bin split in two so the lid can hinge independently of the body. */
    val DeleteLid = R.drawable.ic_delete_lid
    val DeleteBody = R.drawable.ic_delete_body
}
