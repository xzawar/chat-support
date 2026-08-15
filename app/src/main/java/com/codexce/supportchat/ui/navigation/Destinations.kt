package com.codexce.supportchat.ui.navigation

import androidx.annotation.DrawableRes
import com.codexce.supportchat.ui.components.AppIcons

object Routes {
    const val LOGIN = "login"

    /** All four tabs live behind this one destination; the pager owns which one is showing. */
    const val MAIN = "main"

    const val SETTINGS = "settings"
    const val ACCOUNT = "account"
    /*
     * APP_ICON is gone. Alternate launcher icons are toggled by enabling and disabling
     * activity-alias components, which force-stops the process and can leave a blank tile on
     * the home screen until the launcher redraws. It was removed along with its screen and its
     * switcher; do not reintroduce the route.
     */

    /** Cache size and the three pages behind it. Account deletion moved to ACCOUNT. */
    const val STORAGE = "storage"

    /** The Room cache, listed as it actually is on the device. */
    const val CACHED_CHATS = "cachedChats"

    /** Plain-words page about which database holds what, and for how long. */
    const val DATA_LOCATION = "dataLocation"

    /** How to request a full export, since it cannot be assembled on the phone. */
    const val EXPORT_COPY = "exportCopy"
    const val WALLPAPER = "wallpaper"
    const val LINK_WEBSITE = "linkWebsite"
    const val LINK_COMPUTER = "linkComputer"

    /** Plans and coupons. Owner only; the row is not shown to agents. */
    const val SUBSCRIPTION = "subscription"

    /**
     * One plan in full. Subscribe on a plan card pushes here; the coupon box and the actual
     * commitment live on this page rather than on the card.
     */
    const val PLAN_DETAIL = "planDetail/{planId}"

    /** Lead book, stats and templates. Hidden unless the plan includes email_automation. */
    const val EMAILS = "emails"
    const val PERMISSIONS = "permissions"
    const val HELP = "help"
    const val CONVERSATION = "conversation/{conversationId}"

    /**
     * Phase 8.1. A push destination in its own right rather than a sheet over the thread, so
     * that back from the profile returns to the conversation and back again returns to the
     * inbox, which is the order people expect after drilling in twice.
     */
    const val VISITOR_PROFILE = "visitorProfile/{conversationId}"

    const val COMING_SOON = "comingSoon/{title}"

    fun planDetail(planId: String) = "planDetail/$planId"
    fun conversation(conversationId: String) = "conversation/$conversationId"
    fun visitorProfile(conversationId: String) = "visitorProfile/$conversationId"
    fun comingSoon(title: String) = "comingSoon/$title"
}

/**
 * Tab order from the reference image: Chat first, then Email, Social.
 *
 * These no longer carry a route. Tabs are pages of a HorizontalPager inside Routes.MAIN, so the
 * selected tab is an index, not a navigation destination — that is what lets a swipe and a tap
 * drive the same state.
 */
enum class TopLevelTab(
    val label: String,
    @DrawableRes val outlineIcon: Int,
    @DrawableRes val filledIcon: Int,
) {
    Chat("Chat", AppIcons.ChatOutline, AppIcons.ChatFilled),
    Email("Email", AppIcons.MailOutline, AppIcons.MailFilled),
    Social("Social", AppIcons.SocialOutline, AppIcons.SocialFilled),
}

val topLevelTabs: List<TopLevelTab> = TopLevelTab.entries.toList()
