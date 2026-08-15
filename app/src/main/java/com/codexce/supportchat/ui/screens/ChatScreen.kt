@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.data.SupportRepository
import com.codexce.supportchat.data.WallpaperOption
import com.codexce.supportchat.data.model.valueOrNull
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.TypingBubble
import com.codexce.supportchat.notifications.PushNotifications
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.safeClickable
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.ErrorBanner
import com.codexce.supportchat.ui.components.MessageBubble
import com.codexce.supportchat.ui.components.MessageThreadSkeleton
import com.codexce.supportchat.ui.components.PersonAvatar
import com.codexce.supportchat.viewmodel.ConversationViewModel
import com.codexce.supportchat.ui.theme.supportButtonColors

/**
 * The message thread.
 *
 * Phase 8.4 emptied this header out. Assign, Unassign, Close and Reopen all used to live in the
 * top bar; assignment is automatic now (Start Chat does it) and status moved to the visitor
 * profile, so the only action left up here is the one that opens that profile. Tapping the
 * visitor's name does the same thing, which is where people reach for it anyway.
 *
 * Phase 8.3 adds the pending gate: a conversation a visitor requested but nobody has taken shows
 * a Start Chat panel instead of a composer. Reading a pending thread deliberately does not
 * connect the visitor - only the explicit action does.
 */
@Composable
fun ChatScreen(
    conversationId: String,
    agentUid: String?,
    repository: SupportRepository,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    if (agentUid == null) {
        EmptyState(
            icon = AppIcons.Lock,
            title = "Signed out",
            message = "Sign in again to open this conversation.",
        )
        return
    }

    val viewModel: ConversationViewModel = viewModel(
        key = "conversation-$conversationId-$agentUid",
        factory = viewModelFactory {
            initializer { ConversationViewModel(repository, conversationId, agentUid) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wallpaper by preferences.wallpaper.collectAsStateWithLifecycle()
    val conversation = state.conversation
    val context = LocalContext.current

    // Opening the thread clears its notification and stops new ones being posted for it. The
    // unread count is already cleared by ConversationViewModel.init, so the shade and the inbox
    // badge go quiet together rather than one lagging the other.
    //
    // Clearing the marker in onDispose matters as much as setting it: miss that and this
    // conversation would be silenced for the rest of the process.
    DisposableEffect(conversationId) {
        PushNotifications.setActiveConversation(conversationId)
        PushNotifications.clearFor(context, conversationId)
        onDispose { PushNotifications.setActiveConversation(null) }
    }

    /*
     * Layer 1 of 3: the wallpaper, outside the Scaffold.
     *
     * It used to live inside the content slot, which meant it stopped exactly where the list
     * stopped and the composer sat on bare white below it. Painting it behind the whole Scaffold
     * instead lets it run under the composer and the navigation bar, so there is one continuous
     * image and the reply box is part of it rather than a lid on top of it. The Scaffold itself
     * is transparent from here on; every bar that needs a backing paints its own.
     */
    Box(Modifier.fillMaxSize()) {
        ChatWallpaper(option = wallpaper.option, customUri = wallpaper.customUri)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.safeClickable { onOpenProfile(conversationId) },
                    ) {
                        PersonAvatar(
                            name = conversation?.visitorName,
                            email = conversation?.visitorEmail,
                            seed = conversation?.id ?: conversationId,
                            size = 40.dp,
                            loading = conversation == null,
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(
                                text = conversation?.visitorName ?: "Loading\u2026",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    conversation == null -> ""
                                    conversation.isPending -> "Waiting for support"
                                    conversation.isClosed -> "Closed"
                                    conversation.assignedAgentUid == agentUid -> "Assigned to you"
                                    conversation.assignedAgentUid != null -> "Assigned"
                                    else -> "Active"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // The whole header is one action now: open the visitor. Status lives there.
                    IconButton(onClick = debounced { onOpenProfile(conversationId) }) {
                        /*
                         * 21dp inside a 48dp touch target read as a stray mark rather than a
                         * button. 26dp fills the target properly, and tinting it with the
                         * primary colour is what gives it the weight - the asset itself is a
                         * fixed-stroke vector, so scale and colour are the only two levers.
                         */
                        Icon(
                            painter = painterResource(AppIcons.Person),
                            contentDescription = "Visitor profile",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // Fully opaque, like the reference. A translucent header let bubbles ghost
                    // through it as they scrolled past, which is the smeared look you saw at the
                    // top of the thread. The bar is now its own solid band.
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            when {
                conversation == null -> Unit

                conversation.isPending -> StartChatBar(
                    working = state.working,
                    onStart = viewModel::startChat,
                    wallpaperOption = wallpaper.option,
                    wallpaperUri = wallpaper.customUri,
                )

                else -> Composer(
                    draft = state.draft,
                    enabled = !conversation.isClosed,
                    canSend = state.canSend,
                    onDraftChange = viewModel::setDraft,
                    onSend = viewModel::send,
                    wallpaperOption = wallpaper.option,
                    wallpaperUri = wallpaper.customUri,
                )
            }
        },
    ) { insets ->
        // Layer 2 of 3: the conversation surface. Transparent by design so layer 1 shows through;
        // legibility comes from the bubbles themselves, not from a sheet over the wallpaper.
        Box(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxSize()) {
                state.error?.let { message ->
                    ErrorBanner(message = message, onDismiss = viewModel::dismissError)
                }

                val messages = state.messages.valueOrNull()
                when {
                    state.loading -> MessageThreadSkeleton()

                    messages.isNullOrEmpty() -> EmptyState(
                        icon = AppIcons.ChatOutline,
                        title = if (conversation?.isPending == true) {
                            "Support requested"
                        } else {
                            "No messages"
                        },
                        message = if (conversation?.isPending == true) {
                            "This visitor is waiting. Start the chat to connect them."
                        } else {
                            "Nothing has been sent in this conversation yet."
                        },
                    )

                    else -> {
                        val listState = rememberLazyListState()
                        val reversed = remember(messages) { messages.asReversed() }

                        /*
                         * Scroll performance.
                         *
                         * The list itself was never the problem - it has always been a
                         * LazyColumn, so only the visible window is composed. The jank came
                         * from this derivedStateOf: it was keyed on reversed.size, so every
                         * single new message threw away the derived state and allocated a
                         * fresh one, and while it lived it read layoutInfo, which changes on
                         * every frame of a fling. Keyed on listState alone it survives the
                         * whole session, and the size is read through a snapshot holder so a
                         * new message updates the value instead of rebuilding the object.
                         */
                        val itemCount by rememberUpdatedState(reversed.size)
                        val nearOldest by remember(listState) {
                            derivedStateOf {
                                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                                last != null && last.index >= itemCount - 5
                            }
                        }
                        LaunchedEffect(nearOldest) {
                            if (nearOldest) viewModel.loadOlder()
                        }

                        /*
                         * Jump to the newest message when one arrives.
                         *
                         * reverseLayout = true means index 0 is the bottom of the screen, so
                         * "scroll to the latest" is animateScrollToItem(0), not scrolling to
                         * the end. Guarded on the id of the newest message rather than the
                         * count so an edit or a status change does not yank the list, and
                         * skipped when the agent has deliberately scrolled up to read history
                         * - being dragged away from what you are reading is worse than
                         * missing the jump.
                         */
                        val newestId = reversed.firstOrNull()?.id
                        LaunchedEffect(newestId) {
                            if (newestId != null && listState.firstVisibleItemIndex <= 2) {
                                listState.animateScrollToItem(0)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true,
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            /*
                             * reverseLayout puts index 0 at the bottom, so the indicator has
                             * to come before the messages to sit under them on screen.
                             */
                            if (state.working) {
                                item(key = "typing") { TypingBubble() }
                            }
                            /*
                             * contentType lets Compose reuse a sender's bubble subcomposition
                             * instead of tearing it down and rebuilding it every time a row
                             * scrolls into view, which is the other half of the fling cost.
                             */
                            items(
                                items = reversed,
                                key = { it.id },
                                contentType = { it.sender },
                            ) { message ->
                                MessageBubble(message)
                            }
                            if (state.loadingOlder) {
                                item(key = "older-spinner") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    } // end of the wallpaper Box that wraps the Scaffold
}

/**
 * The chat wallpaper.
 *
 * Opening a conversation used to jerk once, and logcat said why:
 *
 *     HWUI ... com.codexce.supportchat image decoding logging dropped
 *
 * That line is the renderer reporting that a bitmap was decoded on the main thread during a
 * frame. painterResource() is a synchronous decode: the full wallpaper PNG was being unpacked
 * inside composition, on the very first frame of the screen, so the first frame missed its
 * deadline and the entry animation started a beat late. That late start is the jerk.
 *
 * Both branches now go through Coil, which decodes on a background dispatcher and hands back a
 * placeholder until the bitmap is ready. The screen therefore composes immediately and the
 * wallpaper fades in a frame or two later, which nobody can see but which costs no dropped
 * frames. Crossfade is off deliberately: an animating background behind an animating page
 * transition is exactly the sort of thing that reintroduces jank.
 */
@Composable
private fun ChatWallpaper(
    option: WallpaperOption,
    customUri: String?,
    // The composer draws its own crop of the same image, and it cannot use fillMaxSize there:
    // inside a wrap-content Box a fillMaxSize child would stretch the bar to the full screen.
    // It passes matchParentSize instead, which paints without driving the parent's height.
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val drawable = option.drawable
    val model: Any? = when {
        option == WallpaperOption.Custom && !customUri.isNullOrBlank() -> customUri
        drawable != null -> drawable
        else -> null
    }
    if (model == null) return

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * The composer's backdrop, and the reason the reply box is no longer a white slab.
 *
 * There are three layers on this screen, in this order back to front:
 *
 *   1. the wallpaper, which now runs edge to edge behind everything, composer included
 *   2. the conversation surface, a translucent wash that keeps bubbles legible over any image
 *   3. the composer itself, which floats on top and lets the list scroll under it
 *
 * WhatsApp gets its composer to match by cropping the same wallpaper behind the input row. This
 * does the equivalent without a second decode: the wallpaper is already painted behind the whole
 * Scaffold, so the composer only needs to be see-through. A flat translucent scrim over it keeps
 * the text field and the send button readable no matter how busy the image is, and the hairline
 * along the top edge is what stops the bar dissolving into the picture.
 */
@Composable
private fun ComposerBackdrop(
    @Suppress("UNUSED_PARAMETER") option: WallpaperOption,
    @Suppress("UNUSED_PARAMETER") customUri: String?,
    content: @Composable () -> Unit,
) {
    /*
     * Nothing is painted here. That is the whole point.
     *
     * The previous version drew a second cropped copy of the wallpaper behind the bar. Two
     * copies of one image can never line up: the crop starts its own scale and offset, so the
     * pattern visibly breaks at the seam where the bar begins. What is wanted is one wallpaper,
     * continuing uninterrupted behind the composer.
     *
     * The full-screen wallpaper already sits behind the whole Scaffold, and the Scaffold's
     * container is transparent, so leaving this bar unpainted lets that single image show
     * through in place, perfectly aligned, because it is literally the same image. The thread
     * scrolls underneath, which is the overlap that was asked for. Contrast comes from the
     * input pill and the send button, both of which carry their own solid colour.
     */
    Box {
        content()
    }
}

/**
 * Phase 8.3 - the gate. This replaces the composer entirely rather than sitting above it: a
 * half-available input on a chat the visitor has not been connected to invites an agent to type
 * a reply that would arrive with no context.
 */
@Composable
private fun StartChatBar(
    working: Boolean,
    onStart: () -> Unit,
    wallpaperOption: WallpaperOption,
    wallpaperUri: String?,
) {
    // Same backdrop as the composer it stands in for, so swapping between the two does not
    // flash a different colour over the wallpaper.
    ComposerBackdrop(option = wallpaperOption, customUri = wallpaperUri) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "This visitor asked for support and is waiting to be connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                colors = supportButtonColors(),
                onClick = debounced(onStart),
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text("Start chat")
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    enabled: Boolean,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    wallpaperOption: WallpaperOption,
    wallpaperUri: String?,
) {
    // Not a Surface any more. A Surface paints an opaque colour, and that opaque colour was the
    // white slab under the reply box: it covered the wallpaper instead of continuing it. The
    // backdrop below is translucent, so the wallpaper shows through the composer and the two
    // finally match, with the thread scrolling underneath.
    ComposerBackdrop(option = wallpaperOption, customUri = wallpaperUri) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // Phase 9: 12dp/8dp down to 10dp/5dp, and the field itself is single-line
                // until it needs to grow, so the resting bar is meaningfully shorter.
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A solid pill on top of the wallpaper crop, with no outline at all. The outlined
            // field was the other half of what made this bar look like a form rather than a
            // messenger: an outline needs a flat background behind it to read cleanly, and
            // there is not one here any more.
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (enabled) "Message" else "Conversation is closed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                enabled = enabled,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    // TextField draws an underline by default. On a pill that reads as a
                    // rendering artefact, so all three states are cleared.
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            // Filled circle, not an outlined glyph. This is the single change that makes the
            // bar read as a messenger: one solid accent disc carrying the only action.
            FilledIconButton(
                onClick = debounced(onSend),
                enabled = canSend,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    painter = painterResource(AppIcons.Send),
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
