package com.codexce.supportchat.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexce.supportchat.data.SupportRepository
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.ui.components.FloatingTabBar
import com.codexce.supportchat.ui.components.SubscriptionGate
import com.codexce.supportchat.ui.components.TabBarHeight
import com.codexce.supportchat.ui.navigation.TopLevelTab
import com.codexce.supportchat.ui.navigation.topLevelTabs
import kotlinx.coroutines.launch

/**
 * The four tab roots live inside one HorizontalPager, in the same order as the navbar, so
 * swiping and tapping are two views of the same state: the bar reads pagerState.currentPage and
 * tapping animates the pager rather than navigating.
 *
 * Scope note: this is a single nav destination. The chat conversation is pushed on top of it as
 * its own destination, so it keeps normal back / predictive-back behaviour and is never part of
 * the swipe.
 *
 * Gesture note: the per-row swipe-to-delete on the chat list is a child pointer-input handler,
 * and Compose dispatches to children first — a horizontal drag that starts on a row is consumed
 * by that row and never reaches the pager. Dragging anywhere else still changes tab.
 */
@Composable
fun MainTabsScreen(
    agentUid: String?,
    repository: SupportRepository,
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSubscription: () -> Unit,
) {
    val tabs = remember { topLevelTabs }
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }
    val scope = rememberCoroutineScope()
    val tenant by TenantSession.tenant.collectAsStateWithLifecycle()

    /*
     * The navbar highlight is driven by its own state, not by pagerState.currentPage.
     *
     * currentPage updates continuously while the pager animates, so tapping Social from Chat
     * walked the highlight through Email on the way - every icon in between lit up in
     * sequence. Holding the target here and jumping the pager with scrollToPage means the
     * highlight moves once, directly, and no intermediate page is ever selected. Swiping still
     * works: targetPage feeds the selection back as soon as the pager commits.
     */
    var selectedTab by remember { mutableIntStateOf(0) }
    /*
     * targetPage, not settledPage. settledPage only changes once the pager has come to a
     * complete stop, so on a swipe the highlight sat on the old tab for the whole fling and
     * then jumped - the delay. targetPage changes the moment the pager commits to a
     * destination, which is the instant the outcome is decided.
     *
     * A tap still sets selectedTab directly in onSelect, so it does not wait for the pager
     * at all, and scrollToPage means no intermediate tab is ever the target.
     */
    LaunchedEffect(pagerState.targetPage) { selectedTab = pagerState.targetPage }

    val systemBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Content clearance is the pill height plus the gesture inset, so nothing hides behind it.
    val tabBarClearance = TabBarHeight + systemBarInset + 24.dp

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { index -> tabs[index].name },
        ) { page ->
            when (tabs[page]) {
                TopLevelTab.Chat -> InboxScreen(
                    agentUid = agentUid,
                    repository = repository,
                    onOpenConversation = onOpenConversation,
                    onOpenSettings = onOpenSettings,
                    onOpenAccount = onOpenAccount,
                    onOpenSubscription = onOpenSubscription,
                    bottomPadding = tabBarClearance,
                )

                // Email automation lives here now, on the home screen, not behind a Settings row.
                // Tenants without the entitlement still get the plain "not subscribed" panel;
                // the dashboard itself also re-checks server side, so a plan that lapses while
                // the app is open degrades to the server's own lock message rather than lying.
                TopLevelTab.Email -> if (tenant?.hasFeature("email_automation") == true) {
                    EmailsScreen(
                        onOpenSettings = onOpenSettings,
                        bottomPadding = tabBarClearance,
                    )
                } else {
                    /*
                     * The same lock panel the Chats tab already used, rather than the softer
                     * "Nothing to show yet" screen. An unsubscribed tab is not empty, it is
                     * closed, and it needs to say so and offer the way out.
                     */
                    SubscriptionGate(
                        onSubscribe = onOpenSubscription,
                        modifier = Modifier.padding(bottom = tabBarClearance),
                    )
                }

                TopLevelTab.Social -> if (tenant?.hasFeature("social_media") == true) {
                    // Entitled, but there is genuinely nothing built here yet, so this stays
                    // the "nothing to show" screen and not a lock.
                    ComingSoonScreen(
                        title = "Social",
                        subtitle = "You are subscribed to this service.",
                        bottomPadding = tabBarClearance,
                    )
                } else {
                    SubscriptionGate(
                        onSubscribe = onOpenSubscription,
                        modifier = Modifier.padding(bottom = tabBarClearance),
                    )
                }
            }
        }

        FloatingTabBar(
            tabs = tabs,
            selectedIndex = selectedTab,
            onSelect = { index ->
                if (index != selectedTab) {
                    selectedTab = index
                    scope.launch { pagerState.scrollToPage(index) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = systemBarInset + 16.dp),
        )
    }
}
