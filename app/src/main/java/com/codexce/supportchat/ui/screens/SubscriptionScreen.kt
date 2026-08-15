package com.codexce.supportchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codexce.supportchat.data.TenantSession
import com.codexce.supportchat.data.api.PlanCard
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.CardListSkeleton
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.StatusPill
import com.codexce.supportchat.ui.components.SupportCard
import com.codexce.supportchat.viewmodel.SubscriptionViewModel
import com.codexce.supportchat.ui.theme.supportButtonColors

/**
 * Plans and coupons. Owner only.
 *
 * Every card carries its own coupon field, not one shared box at the top, because a coupon is
 * applied to a specific plan: DEMO100 on the Scale card has to activate Scale, not whatever plan
 * happened to be selected. The server enforces the same pairing, so the two cannot disagree.
 *
 * No card details are collected anywhere in this screen. A 100%-off coupon activates outright;
 * anything else hands off to the backend billing gateway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    /** Subscribe on a card opens that plan's own page, where the coupon and the commitment live. */
    onOpenPlan: (String) -> Unit = {},
    viewModel: SubscriptionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tenant by TenantSession.tenant.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscription") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        when {
            state.loading && state.plans.isEmpty() -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Spacer(Modifier.height(12.dp))
                    CardListSkeleton(cards = 4, cardHeight = 148.dp)
                }
            }

            state.locked -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = AppIcons.Lock,
                        title = "Owners only",
                        message = state.loadError
                            ?: "Only the workspace owner can manage the subscription.",
                    )
                }
            }

            state.plans.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = AppIcons.Cloud,
                        title = "No plans available",
                        message = state.loadError ?: "Nothing came back from the server.",
                        action = {
                            OutlinedButton(onClick = debounced(viewModel::load)) { Text("Try again") }
                        },
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CurrentPlanCard(
                            // "No plan" rather than a dash, which read as though the plan name
                            // had failed to load rather than as though there is no plan.
                            planName = tenant?.plan?.name ?: state.currentPlanId ?: "No plan",
                            statusLabel = tenant?.statusLabel
                                ?: state.status?.replaceFirstChar { it.uppercase() }
                                ?: "Unknown",
                            features = tenant?.features.orEmpty(),
                            renewsAt = tenant?.currentPeriodEnd,
                            active = tenant?.subscriptionActive == true,
                        )
                    }

                    item { GroupGap() }

                    items(state.plans, key = { it.id }) { plan ->
                        PlanRowCard(
                            plan = plan,
                            /*
                             * The live cache wins over the viewmodel snapshot.
                             *
                             * state.currentPlanId is read once when the plan list loads, so
                             * after subscribing on the plan page the Current tag stayed on the
                             * old row until the screen was closed and reopened. TenantSession
                             * is a StateFlow and updates the moment the plan does.
                             */
                            isCurrent = plan.id == (tenant?.plan?.id ?: state.currentPlanId),
                            busy = state.busyPlanId == plan.id,
                            anyBusy = state.busyPlanId != null,
                            message = state.messages[plan.id],
                            error = state.errors[plan.id],
                            // Subscribe no longer charges from the card. It opens the plan page.
                            onChoose = { onOpenPlan(plan.id) },
                        )
                    }

                    item {
                        Text(
                            text = "Payments are handled by our own billing service. " +
                                "Card details are never entered or stored in this app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentPlanCard(
    planName: String,
    statusLabel: String,
    features: List<String>,
    renewsAt: Long?,
    active: Boolean,
) {
    SupportCard(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Current plan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = planName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            StatusPill(text = statusLabel, emphasised = active)
        }

        /*
         * "*" is a backend wildcard meaning "this plan carries everything". It is a permission,
         * not a feature, and it has no label any more, so it must be filtered out before render
         * or featureLabel's else branch prints a bare asterisk as though it were a product.
         * Filter first, then test for emptiness: a plan holding only the wildcard has nothing
         * to list, and the old check would have opened a spacer above an empty column.
         */
        val visibleFeatures = features.filterNot { it == WILDCARD_FEATURE }
        if (visibleFeatures.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            visibleFeatures.forEach { FeatureLine(it, included = true) }
        }

        if (renewsAt != null && renewsAt > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Renews " + formatDay(renewsAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanRowCard(
    plan: PlanCard,
    isCurrent: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    message: String?,
    error: String?,
    onChoose: () -> Unit,
) {
    SupportCard(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = plan.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Tier " + plan.tier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = plan.priceLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (isCurrent) {
                    Spacer(Modifier.height(4.dp))
                    StatusPill(text = "Current", emphasised = true)
                }
            }
        }

        if (!plan.description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = plan.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        plan.features.filterNot { it == WILDCARD_FEATURE }
            .forEach { FeatureLine(featureLabel(it), included = true) }

        Spacer(Modifier.height(14.dp))

        /*
         * The card used to carry its own coupon field and an "Apply" button, plus a "Switch to
         * this plan" text button underneath. Both are gone. A card is now a summary with one
         * decision on it, and that decision is Subscribe, which opens the plan's own page. The
         * coupon lives there, next to the full plan detail, where someone has enough context to
         * know what they are discounting.
         */
        if (!isCurrent) {
            Button(
                colors = supportButtonColors(),
                onClick = debounced(onChoose),
                enabled = !anyBusy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Subscribe", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

    }
}

@Composable
private fun FeatureLine(feature: String, included: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Icon(
            painter = painterResource(if (included) AppIcons.Check else AppIcons.Lock),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (included) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = featureLabel(feature),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The backend's "carries everything" wildcard. Never shown to anyone: it is a grant, not a
 * product line. Kept as a named constant because it is filtered in four places and a loose "*"
 * literal in a filter reads like a typo.
 */
const val WILDCARD_FEATURE = "*"

/** Feature keys are backend identifiers; these are the words a human should read. */
fun featureLabel(feature: String): String = when (feature) {
    "chat" -> "Live chat"
    "email_automation" -> "Email automation"
    "social_media" -> "Social media"
    else -> feature.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun formatDay(epochMillis: Long): String {
    val formatter = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(epochMillis))
}
