@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.SupportCard
import com.codexce.supportchat.ui.theme.supportButtonColors
import com.codexce.supportchat.viewmodel.SubscriptionViewModel

/**
 * One plan, in full, on its own page.
 *
 * This is where the Subscribe button on a plan card lands. The card is now only a summary, so
 * everything that used to be crammed into it lives here instead: the full feature list, what the
 * price actually covers, the coupon box, and the commitment itself.
 *
 * The two actions are deliberately kept apart and in order. Apply redeems a coupon against this
 * plan and can activate it outright when the discount is total, which is the DEMO100 path.
 * Subscribe is the ordinary route and hands off to the billing service. Neither one collects card
 * details in the app.
 *
 * The coupon field is a pill rather than the default boxed text field. That is the iOS-flavoured
 * treatment asked for: fully rounded, no hard corners anywhere in the pair, and the Apply button
 * matched to the same radius so the two read as one control.
 */
@Composable
fun PlanDetailScreen(
    planId: String,
    onBack: () -> Unit,
    viewModel: SubscriptionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tenant by TenantSession.tenant.collectAsStateWithLifecycle()

    val plan = state.plans.firstOrNull { it.id == planId }
    val isCurrent = planId == (state.currentPlanId ?: tenant?.plan?.id)
    val busy = state.busyPlanId == planId
    val anyBusy = state.busyPlanId != null
    val coupon = state.coupons[planId].orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plan?.name ?: "Plan") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (plan == null) {
            /*
             * Reached by deep link, or after the catalog was dropped from memory. There is no
             * point guessing at a plan we do not hold, so this says so plainly rather than
             * rendering an empty shell that looks like a loading state forever.
             */
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = AppIcons.Cloud,
                    title = "Plan not found",
                    message = "Go back to Subscription and open the plan again.",
                )
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 32.dp,
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Headline. Price gets the visual weight because it is the thing being decided on.
            SupportCard(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = plan.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Tier " + plan.tier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = plan.priceLabel,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (plan.priceCents > 0L) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "per month",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                if (!plan.description.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = plan.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Everything the tier includes, spelled out rather than summarised.
            SupportCard(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "What is included",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                plan.features.filterNot { it == WILDCARD_FEATURE }.forEach { feature ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Icon(
                            painter = painterResource(AppIcons.Check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = featureLabel(feature),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // The coupon pair. Both halves share a 26dp radius so they read as one iOS-style
            // control rather than a box next to a button.
            SupportCard(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Have a coupon?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = coupon,
                        onValueChange = { viewModel.setCoupon(planId, it) },
                        singleLine = true,
                        enabled = !anyBusy,
                        placeholder = { Text("Coupon code") },
                        // Fully rounded, and the outline softened so the pill shape is what the
                        // eye lands on instead of the border.
                        shape = RoundedCornerShape(26.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.weight(1f).height(54.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        colors = supportButtonColors(),
                        onClick = debounced { viewModel.applyCoupon(planId) },
                        enabled = !anyBusy && coupon.isNotBlank(),
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier.height(50.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Apply")
                        }
                    }
                }

                state.errors[planId]?.let { error ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.messages[planId]?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // The commitment, last, after the detail and after the coupon.
            Column(Modifier.padding(horizontal = 16.dp)) {
                Button(
                    colors = supportButtonColors(),
                    onClick = debounced { viewModel.checkout(planId) },
                    enabled = !anyBusy && !isCurrent,
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            text = if (isCurrent) "Current plan" else "Subscribe",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Payments are handled by our own billing service. Card details are " +
                        "never entered or stored in this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
