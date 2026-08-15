package com.codexce.supportchat.ui.navigation

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.ui.components.ToastHost
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.data.SupportRepository
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.notifications.MessageWatchService
import com.codexce.supportchat.notifications.PushNotifications
import com.codexce.supportchat.notifications.PushRegistration
import com.codexce.supportchat.ui.screens.AccountScreen
import com.codexce.supportchat.ui.screens.ChatScreen
import com.codexce.supportchat.ui.screens.ComingSoonScreen
import com.codexce.supportchat.ui.screens.EmailsScreen
import com.codexce.supportchat.ui.screens.HelpScreen
import com.codexce.supportchat.ui.screens.LinkWebsiteScreen
import com.codexce.supportchat.ui.screens.LinkComputerScreen
import com.codexce.supportchat.ui.screens.LoginScreen
import com.codexce.supportchat.ui.screens.MainTabsScreen
import com.codexce.supportchat.ui.screens.PermissionsScreen
import com.codexce.supportchat.ui.screens.PlanDetailScreen
import com.codexce.supportchat.ui.screens.permissionSetupIncomplete
import com.codexce.supportchat.ui.screens.SettingsScreen
import com.codexce.supportchat.ui.screens.StartupScreen
import com.codexce.supportchat.ui.screens.CachedConversationsScreen
import com.codexce.supportchat.ui.screens.DataLocationScreen
import com.codexce.supportchat.ui.screens.ExportCopyScreen
import com.codexce.supportchat.ui.screens.StorageDataScreen
import com.codexce.supportchat.ui.screens.SubscriptionScreen
import com.codexce.supportchat.ui.screens.VisitorProfileScreen
import com.codexce.supportchat.ui.screens.WallpaperScreen
import com.codexce.supportchat.ui.theme.Motion
import kotlinx.coroutines.delay
import com.codexce.supportchat.viewmodel.AuthViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@Composable
fun SupportChatApp(
    preferences: AppPreferences,
    deepLinkConversationId: String? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val navController = rememberNavController()

    /*
     * Every detail route pops the same way, and the predictive-back handler inside
     * pushComposable needs a way to finish the gesture, so the pop is hoisted to one lambda
     * rather than repeated at fourteen call sites.
     */
    val popBack: () -> Unit = { navController.popBackStack() }
    val authViewModel: AuthViewModel = viewModel()
    val auth by authViewModel.state.collectAsStateWithLifecycle()
    /**
     * The tenant, not the account, is now the unit of data. Several agents share one tenant and
     * must see the same inbox, so the repository is keyed to the tenantId that came back in the
     * verified claims rather than to a uid. Before those claims exist it points at a path that
     * cannot resolve, and nothing subscribes to it.
     */
    val tenant by TenantSession.tenant.collectAsStateWithLifecycle()
    val tenantId = tenant?.tenantId
    val repository = remember(tenantId) { SupportRepository.create(context, tenantId ?: "") }
    val scope = rememberCoroutineScope()

    // The cached plan is restored from disk first so the menu does not flicker, then refreshed
    // from the server. The client-side gates below are cosmetic; every route is enforced again
    // on the backend.
    LaunchedEffect(auth.uid) {
        val uid = auth.uid
        if (uid != null) {
            TenantSession.restore(context, uid)
            TenantSession.refresh(context, uid)
        } else {
            TenantSession.clear()
        }
    }

    /**
     * Start destination is decided once, from the session Firebase has already persisted on this
     * device. A returning signed-in user therefore never sees the login screen; there is no
     * separate "first launch" flag, because the session itself is the flag.
     */
    val startDestination = remember {
        if (FirebaseAuth.getInstance().currentUser != null) Routes.MAIN else Routes.LOGIN
    }

    /*
     * Cold start, gated on the UI rather than on the network.
     *
     * This used to wait for the tenant claims to resolve, with a 4s timeout as an escape hatch.
     * Holding the splash for data was the wrong trade. Every list in the app already has a
     * skeleton loader and can render an honest placeholder, so data has somewhere to land while
     * it arrives.
     *
     * What could not be deferred was composition. The NavHost was built INSIDE the crossfade,
     * which meant the graph's first composition and layout ran at the exact moment the splash
     * lifted and the user started tapping. That is what the cold-start lag actually was: not
     * slow data, a graph being assembled underneath the first touches.
     *
     * So the graph is now composed underneath the splash from the first frame, and the splash
     * lifts once that work has genuinely reached the compositor. Frames, not futures: each
     * withFrameNanos resumes on a real frame boundary, so these prove the graph composed, laid
     * out and drew - rather than merely proving that some milliseconds elapsed.
     *
     * Nothing on this path can block on Firebase, so there is no timeout and no path where the
     * splash can hang. Offline, in airplane mode, or during a Firestore outage, the app reaches
     * its cached UI in the same time it does online.
     */
    var uiReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val startedAt = withFrameNanos { it }

        // Two further frames. The first carries the graph's composition and layout; the second
        // proves that pass settled instead of a re-layout chasing it.
        withFrameNanos { }
        val settledAt = withFrameNanos { it }

        // A floor as well: a splash that vanishes in 40ms is a flicker, which reads as a glitch
        // rather than as loading. Measured from real frame timestamps rather than a wall clock,
        // so it stays honest when the first frames are slow on a cheap device.
        val elapsedMillis = (settledAt - startedAt) / 1_000_000
        if (elapsedMillis < STARTUP_MIN_MILLIS) {
            delay(STARTUP_MIN_MILLIS - elapsedMillis)
        }
        uiReady = true
    }

    /*
     * Wallpaper prewarm, deliberately AFTER the workspace resolves rather than during the splash.
     *
     * This used to sit at the top of the effect above, enqueued before the delay, on the argument
     * that the splash window is dead time and the decode may as well happen inside it. That
     * argument is wrong, and it is worth writing down why, because it is convincing and it will
     * be made again.
     *
     * The splash is not dead time. It looks idle because nothing is moving on screen, but behind
     * it the process is doing the most concentrated work it will ever do: class loading, the
     * first composition, layout, Firebase's own initialisation, and reading the tenant claims.
     * Adding a full-screen bitmap decode to that window does not use spare capacity, it competes
     * for the capacity already in use.
     *
     * Being on Coil's IO dispatchers does not make it free either. It is off the main thread, so
     * it will not block a frame directly, but on a four-core phone it takes a core the main
     * thread wants, allocates several megabytes, and provokes GC during the exact window where a
     * GC pause is most visible. On a fast device none of this shows. On a cheap one - which is
     * the device the complaint about cold start came from - it is the difference between the app
     * being ready when the splash clears and the splash clearing onto a stutter.
     *
     * So: keyed on uiReady, which means it starts once the app is drawn and interactive.
     * The original benefit is kept in full. The first chat opened still finds a warm bitmap,
     * because the gap between the app becoming usable and the user's first tap on a thread is far
     * longer than the decode takes - a second at minimum, usually several. The decode simply now
     * happens in genuinely idle time instead of during the launch.
     *
     * Still fire and forget. If it has not finished when the first thread opens, the wallpaper
     * arrives late exactly as it did before this prewarm existed at all.
     */
    LaunchedEffect(uiReady) {
        if (uiReady) {
            Prewarm.chatWallpaper(context, preferences.wallpaper.value)
        }
    }

    // Signing in: leave Login behind, register for push, then ask for the notification
    // permission - after login, so the request has an obvious reason.
    LaunchedEffect(auth.uid, tenantId) {
        val uid = auth.uid
        if (uid != null) {
            if (navController.currentDestination?.route == Routes.LOGIN) {
                navController.navigate(Routes.MAIN) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                    launchSingleTop = true
                }
            }
            /*
             * Do not compete with the first composition. Until a tenant exists every registration
             * would fail anyway, and starting the service before the repository has a valid path
             * creates the /chats/unprovisioned listener seen in startup logs. Once provisioned,
             * wait long enough for the initial render and then do the non-visual setup.
             */
            if (tenantId != null) {
                delay(2_500)
                PushNotifications.ensureChannel(context)
                PushRegistration.register(preferences.deviceId)
                // The live connection. Push is now only the backstop for when this is not running.
                MessageWatchService.start(context)

                /*
                 * One walk-through instead of a bare permission dialog. This is deliberately in
                 * the deferred workspace-ready block: launching Settings/permission setup during
                 * the first render costs a full Activity transition and was visible as startup
                 * jank on MIUI devices.
                 */
                if (!preferences.permissionsDone.value && permissionSetupIncomplete(context)) {
                    navController.navigate(Routes.PERMISSIONS)
                }
            }
        }
    }

    // Signing out: drop the whole main graph so back cannot re-enter it.
    LaunchedEffect(auth.signedIn) {
        if (!auth.signedIn && navController.currentDestination != null &&
            navController.currentDestination?.route != Routes.LOGIN
        ) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // A tapped notification carries its conversation id; open that thread directly.
    LaunchedEffect(deepLinkConversationId, auth.signedIn) {
        val conversationId = deepLinkConversationId
        if (!conversationId.isNullOrBlank() && auth.signedIn) {
            // Same warm-up on the notification path. This one benefits most: a tapped
            // notification opens a chat the app has not drawn yet, so nothing is cached.
            Prewarm.chatWallpaper(context, preferences.wallpaper.value)
            navController.navigate(Routes.conversation(conversationId)) {
                launchSingleTop = true
            }
            onDeepLinkHandled()
        }
    }

    /** Clears the device token and the local cache before the session goes away. */
    fun signOut() {
        val uid = auth.uid
        scope.launch {
            if (uid != null) {
                PushRegistration.unregister(preferences.deviceId)
            }
            MessageWatchService.stop(context)
            repository.clearCache()
            TenantSession.clear()
            authViewModel.signOut()
        }
    }

    /*
     * Time to full display.
     *
     * Without this the platform only knows about time-to-initial-display, which for this app is
     * the startup screen's first frame - a moving bar over an empty background. That number can
     * be driven arbitrarily low by drawing the placeholder sooner, and doing so would improve
     * nothing whatsoever for the person holding the phone.
     *
     * reportFullyDrawn is the app telling the system that what is on screen is now the thing the
     * user came for. It is what makes StartupTimingMetric emit a TTFD figure at all - without
     * the call the metric silently reports nothing for it - and it is also what Play Console
     * vitals and the system's own launch logging record.
     *
     * The condition is uiReady rather than a fixed delay, so the number tracks the actual cost
     * of building the graph instead of a constant somebody chose. It now reports the moment the
     * UI is genuinely ready for input, which is what TTFD should mean for this app: the data
     * behind it lands later under skeletons, by design, and holding the metric open for the
     * network would have measured Firebase rather than the app.
     *
     * ReportDrawnWhen rather than a raw call, because it waits for the frame that satisfies the
     * condition to actually reach the compositor. Calling reportFullyDrawn() from a
     * LaunchedEffect fires one frame early, which quietly understates every measurement.
     */
    ReportDrawnWhen { uiReady }

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            /*
             * The graph is composed from the very first frame, underneath the splash, rather
             * than being swapped in when the splash clears. This is the whole point of the
             * change: by the time the splash lifts, every start destination has already been
             * through composition, measure and layout, so the first tap lands on a built UI.
             */
            Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                // Login <-> Main only. Deferred like everything else so the very first paint
                // after sign-in is not racing the graph swap.
                enterTransition = {
                    fadeIn(tween(Motion.FAST_MILLIS, delayMillis = Motion.PAGE_DEFER_MILLIS))
                },
                exitTransition = {
                    fadeOut(tween(Motion.FAST_MILLIS, delayMillis = Motion.PAGE_DEFER_MILLIS))
                },
            ) {
                composable(Routes.LOGIN) {
                    LoginScreen(authViewModel = authViewModel)
                }

                /*
                 * This is the background half of the slide-over. It must be the exact mirror of
                 * pushComposable or the two halves drift apart mid-gesture, which is what the
                 * jerk on the way back actually was: this destination fell back to a 150ms fade
                 * while the detail screen slid for 300ms.
                 *
                 * It parks at PARALLAX rather than sliding out, and it does not fade - a screen
                 * that is being covered should still be there underneath, not dissolving.
                 */
                composable(
                    route = Routes.MAIN,
                    enterTransition = { parkEnter() },
                    exitTransition = { park() },
                    // Coming back from any detail. Seekable so the tab roots are visible and
                    // moving while the back swipe is still held.
                    popEnterTransition = { parkEnterPop() },
                    popExitTransition = { park() },
                ) {
                    MainTabsScreen(
                        agentUid = auth.uid,
                        repository = repository,
                        onOpenConversation = {
                            // Kick the wallpaper decode off before navigating. The push then
                            // sits still for PAGE_DEFER_MILLIS, which is exactly the window
                            // that decode needs, so the chat draws its backdrop from memory
                            // instead of building it during the slide.
                            Prewarm.chatWallpaper(context, preferences.wallpaper.value)
                            navController.navigate(Routes.conversation(it))
                        },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                        onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                    )
                }

                pushComposable(Routes.CONVERSATION) { entry ->
                    ChatScreen(
                        conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                        agentUid = auth.uid,
                        repository = repository,
                        preferences = preferences,
                        onBack = { navController.popBackStack() },
                        onOpenProfile = { navController.navigate(Routes.visitorProfile(it)) },
                    )
                }

                // Phase 8.1. Pushed on top of the conversation rather than replacing it, so
                // closing a chat from the profile still leaves the thread underneath to go
                // back to and read.
                pushComposable(Routes.VISITOR_PROFILE) { entry ->
                    VisitorProfileScreen(
                        conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                        agentUid = auth.uid,
                        repository = repository,
                        onBack = { navController.popBackStack() },
                    )
                }
                pushComposable(Routes.SETTINGS) {
                    SettingsScreen(
                        preferences = preferences,
                        signedInEmail = auth.user?.email,
                        onBack = { navController.popBackStack() },
                        onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                        onOpenStorage = { navController.navigate(Routes.STORAGE) },
                        onOpenWallpaper = { navController.navigate(Routes.WALLPAPER) },
                        onOpenLinkWebsite = { navController.navigate(Routes.LINK_WEBSITE) },
                        onOpenLinkComputer = { navController.navigate(Routes.LINK_COMPUTER) },
                        onOpenHelp = { navController.navigate(Routes.HELP) },
                        onComingSoon = { navController.navigate(Routes.comingSoon(it)) },
                        // Sign out lives here now, at the bottom of Settings, rather than
                        // stranded mid-page on the Account screen.
                        onSignOut = { signOut() },
                        // Owner-only and feature-gated rows. Hiding them is a courtesy, not a
                        // control: the endpoints behind them refuse anyone else regardless.
                        showSubscription = tenant?.isOwner == true,
                        planSummary = tenant?.let { it.plan.name + " \u00b7 " + it.statusLabel },
                        // Header identity. Both are null for email-only accounts, and the
                        // header falls back to the local part of the address plus initials.
                        displayName = auth.user?.displayName,
                        photoUrl = auth.photoUrl,
                        onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                    )
                }
                pushComposable(Routes.SUBSCRIPTION) {
                    SubscriptionScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlan = { planId ->
                            navController.navigate(Routes.planDetail(planId))
                        },
                    )
                }
                /*
                 * Registered with pushComposable like every other detail route, so the plan page
                 * inherits the same right-to-left travel as the rest of the app rather than
                 * appearing with the NavHost default fade.
                 */
                pushComposable(Routes.PLAN_DETAIL) { entry ->
                    PlanDetailScreen(
                        planId = entry.arguments?.getString("planId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                pushComposable(Routes.EMAILS) {
                    EmailsScreen(onBack = { navController.popBackStack() })
                }
                pushComposable(Routes.ACCOUNT) {
                    AccountScreen(
                        authViewModel = authViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                pushComposable(Routes.HELP) {
                    HelpScreen(onBack = { navController.popBackStack() })
                }
                pushComposable(Routes.PERMISSIONS) {
                    PermissionsScreen(
                        onDone = {
                            preferences.setPermissionsDone(true)
                            navController.popBackStack()
                        },
                    )
                }
                pushComposable(Routes.LINK_WEBSITE) {
                    LinkWebsiteScreen(
                        signedInEmail = auth.user?.email,
                        onBack = { navController.popBackStack() },
                    )
                }
                pushComposable(Routes.LINK_COMPUTER) {
                    LinkComputerScreen(onBack = { navController.popBackStack() })
                }
                pushComposable(Routes.STORAGE) {
                    /*
                     * No longer takes preferences: Stay connected was the only thing on this
                     * page that read them, and that switch has gone back to Settings where the
                     * rest of the notification controls live.
                     */
                    StorageDataScreen(
                        onBack = { navController.popBackStack() },
                        onOpenCached = { navController.navigate(Routes.CACHED_CHATS) },
                        onOpenDataLocation = { navController.navigate(Routes.DATA_LOCATION) },
                        onOpenExport = { navController.navigate(Routes.EXPORT_COPY) },
                    )
                }
                pushComposable(Routes.CACHED_CHATS) {
                    CachedConversationsScreen(onBack = { navController.popBackStack() })
                }
                pushComposable(Routes.DATA_LOCATION) {
                    DataLocationScreen(onBack = { navController.popBackStack() })
                }
                pushComposable(Routes.EXPORT_COPY) {
                    ExportCopyScreen(onBack = { navController.popBackStack() })
                }
                pushComposable(Routes.WALLPAPER) {
                    WallpaperScreen(
                        preferences = preferences,
                        onBack = { navController.popBackStack() },
                    )
                }
                pushComposable(Routes.COMING_SOON) { entry ->
                    ComingSoonScreen(
                        title = entry.arguments?.getString("title").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            /*
             * Above the nav host on purpose: a toast raised by one screen must survive the
             * navigation away from it, and anything inside NavHost is torn down on a push.
             */
            ToastHost()
            }

            /*
             * The splash sits ON TOP of the live graph and fades out, instead of the graph
             * fading in. Only the overlay animates, so nothing underneath is re-composed when
             * it leaves.
             *
             * It also swallows every pointer event while visible. That is the direct answer to
             * taps landing on a half-built screen: input cannot reach the graph until the graph
             * is ready for it, so hammering the screen during launch queues nothing and janks
             * nothing.
             */
            AnimatedVisibility(
                visible = !uiReady,
                enter = EnterTransition.None,
                exit = fadeOut(tween(Motion.STANDARD_MILLIS)),
            ) {
                StartupScreen(
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Shortest time the cold-start screen stays up, so it never registers as a flicker. */
private const val STARTUP_MIN_MILLIS = 650L

/*
 * Layered slide-over, not a swap.
 *
 * Two things were wrong before. The incoming screen started at full/4 - a quarter of the way in,
 * already on top of the outgoing one - so it appeared to pop rather than travel; and every
 * transition carried a fade, which is what made a covered screen look like it was dissolving
 * instead of sitting behind the new one.
 *
 * Now the overlay enters from the full width (it genuinely starts off-screen and slides across),
 * and the screen underneath travels RIGHT TO LEFT - it slides out towards the left edge by an
 * eighth of the width and stops there, so the new page appears to push it aside rather than
 * chase it. Back is the exact mirror: the overlay slides off to the right while the background
 * walks left-to-right back to zero, revealing itself. Both halves share one duration and one
 * easing, so the pair reads as a single continuous movement.
 *
 * The sign on the offset is the whole animation. A positive offset sends the underlying page
 * right, in the same direction as the incoming overlay, which reads as both layers fleeing
 * together. Negative is what produces the layered push. Do not drop the minus.
 *
 * Everything animated here is a translation, which Compose renders on the render thread as a
 * layer transform - no re-measure or re-layout is triggered mid-animation, which is the usual
 * source of dropped frames on a transition like this.
 */
private const val PARALLAX = 8

/** The corner the incoming screen carries while it is travelling. */
private val TRAVEL_RADIUS = 22.dp
/*
 * One duration, behind one deferred start.
 *
 * Every spec below runs for Motion.PAGE_MILLIS - 300ms of movement - and every one of them waits
 * Motion.PAGE_DEFER_MILLIS - 100ms - before it starts moving. 400ms total per navigation, in both
 * directions.
 *
 * The hold is the point, not an oversight. The destination composes, measures and draws on frame
 * ONE of the transition whether or not there is a delay, because a slide is a layout offset: for
 * the length of the hold the new screen is fully built and simply translated off the edge of the
 * window. The expensive first frame is therefore spent while nothing is moving, and the movement
 * that follows has no work left to do. Build it, then slide it.
 *
 * The delayMillis argument is on all four specs rather than two, so the pairing stays visible in
 * the code: whatever that constant is, it is applied to both halves of both pairs. Applying it to
 * the entering screen alone would let the outgoing one park early and expose the bare window
 * behind it.
 *
 * These used to carry a 200ms delay in front of a 340ms slide, which made a push cost 540ms and
 * a pop cost another 540ms. The delay bought a warm first frame on the destination by pausing
 * before the movement started - sound reasoning, wrong mechanism. It taxed every navigation in
 * the app equally, whether the destination was expensive or trivial, and it did nothing about
 * the one thing that actually was expensive: the full-screen wallpaper decode. That decode is
 * now started by Prewarm at the moment of the tap, before navigate() is called, so it runs on
 * Coil's IO dispatchers alongside the transition rather than being hidden behind a pause on the
 * main thread. The work moved off the critical path instead of being concealed by it.
 *
 * Keep the number at 0. It is charged twice per round trip.
 */
private fun slideSpec() = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = Motion.PAGE_MILLIS,
    delayMillis = Motion.PAGE_DEFER_MILLIS,
    easing = FastOutSlowInEasing,
)

/*
 * The pop pair.
 *
 * These are now character-for-character identical to slideSpec and fadeSpec, and that is the
 * requirement rather than an accident: closing a page has to be the exact mirror of opening it,
 * same 300ms and same easing, or the pair reads as two different animations bolted together.
 *
 * They stayed as separate functions on purpose. This is where the two halves diverged once
 * before - the pop was left on a bare 150ms fade while the push slid for 300ms, which is what
 * the jerk on the way back actually was - and having named entry points for the pop direction
 * makes it obvious where a future divergence would go, and obvious that there is not one now.
 *
 * The old note here about seeking these against the finger no longer applies to anything.
 * Predictive back is off at the manifest level and stays off, so nothing seeks these specs;
 * they are committed animations played start to finish.
 */
private fun popSlideSpec() = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = Motion.PAGE_MILLIS,
    delayMillis = Motion.PAGE_DEFER_MILLIS,
    easing = FastOutSlowInEasing,
)

/** Pop counterpart of [parkEnter]: the screen underneath walking back to rest. */
private fun parkEnterPop() = slideInHorizontally(popSlideSpec()) { full -> -full / PARALLAX }

/**
 * The outgoing screen: a short drift to the LEFT. Translation only, no fade.
 *
 * A fifth of the way would be a shove; an eighth is enough to register as depth without the
 * screen appearing to leave under its own steam. The offset is negative so the page underneath
 * moves right to left, against the incoming overlay.
 *
 * The partial fade to alpha 0.72 that used to accompany this was removed, and it was one of the
 * two measured causes of the jank on this transition. Any alpha strictly between 0 and 1 forces
 * the whole full-screen layer to be rendered into an offscreen buffer and then blended back,
 * every frame, for the entire duration. A pure translation is a layer transform the render
 * thread applies essentially for free.
 *
 * Nothing is lost visually. The depth cue was never really the fade - it is the parallax offset,
 * which is what reads as one page sliding behind another.
 */
private fun park() = slideOutHorizontally(slideSpec()) { full -> -full / PARALLAX }

/**
 * The exact mirror, for when the overlay leaves and this one comes back to rest.
 *
 * Starts parked off to the left and walks back to zero, so on the way back the underlying page
 * travels left to right - the reverse of the push, as it must be.
 */
private fun parkEnter() = slideInHorizontally(slideSpec()) { full -> -full / PARALLAX }

/**
 * Detail routes share one slide-over spec.
 *
 * There is deliberately no PredictiveBackHandler here, and adding one back will break the
 * gesture again. navigation-compose 2.8.x drives predictive back itself: it seeks
 * popEnterTransition and popExitTransition against the swipe, so the previous screen is really
 * on screen and moving under the finger. A PredictiveBackHandler registers a higher-priority
 * callback and CONSUMES the gesture before NavHost ever sees it - which is exactly why no
 * preview appeared, and why the whole slide only ran on release: by then the gesture was over
 * and popBackStack() was starting an ordinary un-seeked pop from scratch.
 */
private fun androidx.navigation.NavGraphBuilder.pushComposable(
    route: String,
    content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(
        route = route,
        // In from the right edge, full width, no fade.
        enterTransition = { slideInHorizontally(slideSpec()) { full -> full } },
        // Pushed further back when something lands on top of this one.
        exitTransition = { park() },
        popEnterTransition = { parkEnterPop() },
        // Back: straight out to the right and dismissed. Seeked, so no deferred start.
        popExitTransition = { slideOutHorizontally(popSlideSpec()) { full -> full } },
    ) { entry ->
        /*
         * Rounded while moving, square at rest - stepped, not animated.
         *
         * A permanently rounded screen would show cut corners against the window for as long as
         * it is open, so the radius is still tied to whether the screen is travelling. What
         * changed is how OFTEN that value changes.
         *
         * This used to be transition.animateDp, interpolating the radius continuously and
         * feeding the result to both RoundedCornerShape and shadowElevation. That was the
         * primary measured cause of the jank here, and it was expensive twice over: a changing
         * corner radius forces the Surface to rebuild its clip outline every frame, and a
         * changing elevation forces the shadow to be regenerated and re-rendered offscreen every
         * frame. Both land on the render thread during the exact window the user is watching,
         * on top of the slide itself.
         *
         * Reading the transition state directly makes this a step function: it takes one of two
         * values and changes at most twice per navigation, so the outline and the shadow are
         * each built once rather than roughly forty-eight times at 120Hz.
         *
         * At these durations the step is visually indistinguishable from the interpolation,
         * because the corner is only ever seen against a moving edge - there is no frame in
         * which a stationary screen shows a half-closed corner.
         */
        val travelling = transition.currentState != EnterExitState.Visible ||
            transition.targetState != EnterExitState.Visible
        val radius = if (travelling) TRAVEL_RADIUS else 0.dp

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(radius),
            color = MaterialTheme.colorScheme.background,
            /* The shadow is what separates the two layers; it leaves with the corners. */
            shadowElevation = radius * 0.5f,
        ) {
            content(entry)
        }
    }
}
