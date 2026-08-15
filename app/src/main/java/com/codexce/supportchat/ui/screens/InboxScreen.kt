@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.codexce.supportchat.data.SupportRepository
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.data.model.Conversation
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.AppToast
import com.codexce.supportchat.ui.components.ConversationListSkeleton
import com.codexce.supportchat.ui.components.ConversationRow
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.ErrorBanner
import com.codexce.supportchat.ui.components.InboxFilters
import com.codexce.supportchat.ui.components.SubscriptionGate
import com.codexce.supportchat.viewmodel.InboxViewModel
import com.codexce.supportchat.ui.theme.supportButtonColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun InboxScreen(
    agentUid: String?,
    repository: SupportRepository,
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSubscription: () -> Unit,
    bottomPadding: Dp,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Inbox",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = debounced(onOpenSettings)) {
                        Icon(
                            painter = painterResource(AppIcons.Menu),
                            contentDescription = "Menu",
                            // Phase 9: 22dp to 26dp. It is the only affordance in the header
                            // and was reading as smaller than the search glyph below it.
                            modifier = Modifier.size(26.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // Phase 9: the header separates from the list by one surface-container step
                    // instead of a rule underneath it.
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { insets ->
        if (agentUid == null) {
            EmptyState(
                icon = AppIcons.Lock,
                title = "Not signed in",
                message = "Sign in from the Account page to start receiving messages.",
                modifier = Modifier.padding(insets),
                action = { Button(colors = supportButtonColors(), onClick = debounced(onOpenAccount)) { Text("Open Account") } },
            )
            return@Scaffold
        }

        // This is read before the ViewModel so its key can include the workspace. A returning
        // user starts with no tenant for a moment; when it arrives, recreate the VM against the
        // newly keyed repository rather than leaving it attached to the loading placeholder.
        val tenant by TenantSession.tenant.collectAsStateWithLifecycle()
        val viewModel: InboxViewModel = viewModel(
            key = "inbox-$agentUid-${tenant?.tenantId.orEmpty()}",
            factory = viewModelFactory {
                initializer { InboxViewModel(repository, agentUid) }
            },
        )
        val state by viewModel.state.collectAsStateWithLifecycle()
        // "No active subscription" is a billing state, not a rules problem, and the two used to
        // land on the same generic database error. Checked before loadError on purpose: the read
        // failure is a symptom of the lapsed plan, so the plan is what we should talk about.
        val subscriptionLapsed = tenant?.subscriptionActive == false

        Column(Modifier.fillMaxSize().padding(insets)) {
            // The search bar and the filter chips share one shaded band, which is what visually
            // divides the header controls from the conversation list without a divider.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                // A filled capsule, not an outlined box. An outline plus a shaded header band
                // draws the same boundary twice, which is what made this read as a form field
                // instead of a search bar. One soft fill, no border, full bleed to the margins.
                TextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("Search", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(AppIcons.Search),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        // The default underline cuts across the bottom of a capsule. Cleared in
                        // both states so the shape stays a clean pill.
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )

                // All / Pending / Assigned / Unassigned / Closed, reading the backend's own
                // status and assignedAgentUid fields. Pending is the queue Start Chat drains.
                InboxFilters(
                    selected = state.filter,
                    counts = state.counts,
                    onSelect = viewModel::setFilter,
                )
            }

            state.actionError?.let { message ->
                ErrorBanner(message = message, onDismiss = viewModel::dismissError)
            }

            when {
                subscriptionLapsed -> SubscriptionGate(onSubscribe = onOpenSubscription)

                state.loading -> ConversationListSkeleton()

                // The old hint here pointed at a per-membership tenantId lookup and the "demo"
                // tenant. Neither exists any more - the signed-in account owns exactly one tenant
                // and chat hangs off chats/{tenantId} - so the advice was actively misleading.
                state.loadError != null -> EmptyState(
                    icon = AppIcons.Cloud,
                    title = "Cannot read the inbox",
                    message = state.loadError.orEmpty() +
                        "\n\nThis usually means the database rules have not been published yet, " +
                        "or the website widget is still writing to a different account.",
                )

                state.visible.isEmpty() -> EmptyState(
                    icon = AppIcons.ChatOutline,
                    title = "Nothing here",
                    // Kept short on purpose. The old wording wrapped onto a second line with a
                    // single word stranded on it, which looks like a typo rather than a message.
                    message = "No conversations match this filter.",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding),
                ) {
                    items(items = state.visible, key = { it.id }) { conversation ->
                        SwipeableConversationRow(
                            /*
                             * Deleting a row used to make everything below it jump up by one
                             * row height in a single frame. animateItem tweens the placement
                             * so the gap closes instead. It covers Undo too: the row is put
                             * back and the list opens up for it rather than snapping.
                             *
                             * Depends on the `key` already set on items() - without a stable
                             * key Lazy layout cannot tell a moved item from a new one.
                             */
                            modifier = Modifier.animateItem(),
                            conversation = conversation,
                            isMine = conversation.assignedAgentUid == agentUid,
                            onOpen = {
                                viewModel.markOpened(conversation)
                                onOpenConversation(conversation.id)
                            },
                            onDelete = {
                                viewModel.delete(conversation)
                                // The toast is the only thing holding the delete back from
                                // the server, so it is raised here rather than inside the
                                // ViewModel: the window and the button that cancels it have
                                // to come up together or neither is any use.
                                AppToast.show(
                                    text = "Chat deleted",
                                    actionLabel = "Undo",
                                    action = { viewModel.undoDelete(conversation.id) },
                                )
                            },
                        )
                        // Phase 9: no rule between rows. The list sits on plain `surface` while
                        // the header band above and the tab bar below are one step darker, so
                        // the list already reads as its own region without striping it.
                    }
                }
            }
        }
    }
}

/**
 * Row-level swipe to delete, hand-rolled.
 *
 * This was a material3 SwipeToDismissBox and is not any more, because three of the things asked
 * for here are not exposed by it: the point at which it commits is fixed at half the row width,
 * rememberSwipeToDismissBoxState has no animationSpec parameter in material3 1.3.1 so the
 * settle cannot be made to spring, and there is no callback at the moment a threshold is
 * crossed to hang the snap off. Reaching for a custom gesture is the higher-risk option and was
 * chosen knowingly.
 *
 * The gesture, in order:
 *
 *  - Drag tracks the finger one-to-one up to 40% of the row width. The bin lid opens across
 *    that same 0..1, hinged at the left end of its own bar, so it tracks the finger and closes
 *    again if you pull back.
 *  - At 40% the row is armed: a haptic tick fires and the lid springs open past its resting
 *    angle and settles back, on a genuinely bouncy spring rather than a tween. That overshoot
 *    is the snap - it is the only feedback that the row will now delete if you let go, and it
 *    has to be felt as well as seen because the thumb is covering the icon.
 *  - Past 40% further travel is damped to a third, so the row resists rather than flying off
 *    the edge, and it stays obvious that the commit point is behind you, not ahead.
 *  - Release past 40% runs the row off the edge and deletes. Release before it springs the row
 *    home, with a little bounce, and nothing is deleted.
 *
 * Deleting is still soft: onDelete hides the row locally and raises the Undo toast, and the
 * server delete only happens once that toast expires.
 *
 * This gesture is a child of the tab HorizontalPager. Compose dispatches pointer events to
 * children first, so a horizontal drag starting on a row is consumed here and never turns into
 * a page change - draggable() consumes on the same terms the dismiss box did.
 */
private const val SwipeArmFraction = 0.4f

/** How much of each pixel of travel survives past the arm point. */
private const val OverdragDamping = 0.33f

/** Lid angle the finger can reach on its own, before the row arms. */
private const val LidTrackDegrees = 30f

/*
 * How far the lid opens once the row is armed.
 *
 * 34 degrees, and it stays there. There was a two-stage move here - swing up to 52, then drop
 * back to 6 - which is what read as a jump: the lid arrived, then immediately fell, so the eye
 * saw a flick rather than a bin opening. The lid now eases to one angle and holds it for as
 * long as the row is armed, which also makes the armed state legible while you decide.
 */
private const val LidOpenDegrees = 34f

@Composable
private fun SwipeableConversationRow(
    conversation: Conversation,
    isMine: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var rowWidth by remember { mutableStateOf(0f) }
    val offsetX = remember { Animatable(0f) }
    /*
     * Lid angle in degrees, and it is the whole animation now.
     *
     * The old version scaled the lid up and back down on arming, which read as a pulse rather
     * than a bin opening. What the reference actually does is angular: the lid swings up past
     * where it settles, hangs for an instant, then DROPS - falling further and faster than it
     * rose, with a small bounce as it lands. So the overshoot and the fall are both rotations,
     * and the scale pop is gone entirely.
     */
    val lidAngle = remember { Animatable(0f) }

    val armAt = rowWidth * SwipeArmFraction
    val travel = if (armAt > 0f) (-offsetX.value / armAt).coerceIn(0f, 1f) else 0f
    val armed = travel >= 1f

    /*
     * The snap. Keyed on the armed flag rather than on the raw offset, so it fires once on the
     * way past 40% and once more only if you pull back below it and cross again.
     */
    LaunchedEffect(armed) {
        if (armed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Start from wherever the finger had already dragged it, so arming continues the
            // movement instead of teleporting the lid back to the closed angle first.
            lidAngle.snapTo(LidTrackDegrees)
            lidAngle.animateTo(
                targetValue = LidOpenDegrees,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            )
        } else {
            lidAngle.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            )
        }
    }

    /*
     * Before arming the lid tracks the finger one-to-one, so pulling back closes it again.
     * After arming the spring owns it. Two sources, one value, and the handover happens at the
     * exact instant the spring is seeded above.
     */
    /*
     * The handover, and the source of the jerk.
     *
     * LaunchedEffect is asynchronous: for one frame after arming, this read switched to
     * lidAngle, which was still 0 from the closing animation, so the lid slammed shut and then
     * sprang open again. Flooring the armed value at the angle the finger had already reached
     * means the lid can never travel backwards across the handover - worst case it holds still
     * for a frame until the animation takes over.
     */
    val lidRotation =
        if (armed) maxOf(lidAngle.value, LidTrackDegrees) else LidTrackDegrees * travel

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width.toFloat() },
    ) {
        /*
         * The red panel exists only while the row is actually off its mark. Drawing it at rest
         * would let Delete show through underneath every row in the list.
         */
        if (offsetX.value < -0.5f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = travel
                        translationX = (1f - travel) * 36.dp.toPx()
                    },
                ) {
                    /*
                     * Lid and body are separate drawables on one shared 24x24 grid, so at rest
                     * the join is invisible. The lid hinges about the left end of its own bar,
                     * not the centre of the icon: TransformOrigin is a fraction of the drawable
                     * and 4.5/24 by 6.6/24 is where that bar starts. Rotating about the centre
                     * would swing the lid through the body. -34 degrees is as far as it goes
                     * before the handle clips the top edge.
                     */
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(AppIcons.DeleteBody),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Icon(
                            painter = painterResource(AppIcons.DeleteLid),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    /*
                                     * Hinged at the RIGHT end of the lid bar, not the left.
                                     *
                                     * The panel is revealed on the right of the row, so the
                                     * chat content is to the lid's left. Hinging on the left
                                     * threw the lid open away from the chat, which is the
                                     * complaint. Pivoting on the outer end instead means the
                                     * free end rises over the conversation it is about to eat.
                                     * 19.5/26 is where that bar ends on the shared grid.
                                     */
                                    transformOrigin = TransformOrigin(0.8f, 0.3833f)
                                    rotationZ = lidRotation
                                },
                        )
                    }
                    // Caption under the glyph, not beside it, as in the reference.
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }

        ConversationRow(
            conversation = conversation,
            isMine = isMine,
            onOpen = onOpen,
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            /*
                             * Damping applies to outward travel only. Pulling back towards home
                             * stays one-to-one, otherwise changing your mind feels like the row
                             * is stuck to the finger.
                             */
                            val resist = armed && delta < 0f
                            val step = if (resist) delta * OverdragDamping else delta
                            offsetX.snapTo(
                                (offsetX.value + step).coerceIn(-rowWidth, 0f),
                            )
                        }
                    },
                    onDragStopped = {
                        if (armAt > 0f && -offsetX.value >= armAt) {
                            // Off the edge first, then delete, so the list closes the gap behind
                            // a row that has already left rather than one vanishing in place.
                            offsetX.animateTo(-rowWidth, tween(180))
                            onDelete()
                        } else {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                    },
                ),
        )
    }
}
