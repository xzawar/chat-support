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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codexce.supportchat.data.api.EmailStats
import com.codexce.supportchat.data.api.EmailTemplate
import com.codexce.supportchat.data.api.Lead
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.CardListSkeleton
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.ErrorBanner
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.RowActions
import com.codexce.supportchat.ui.components.StatusPill
import com.codexce.supportchat.ui.components.SupportCard
import com.codexce.supportchat.ui.components.ThinDivider
import com.codexce.supportchat.viewmodel.EmailsUiState
import com.codexce.supportchat.viewmodel.EmailsViewModel
import com.codexce.supportchat.viewmodel.LeadSection
import com.codexce.supportchat.viewmodel.SectionState
import com.codexce.supportchat.ui.theme.supportButtonColors

/**
 * The Emails dashboard: stats, the lead book grouped by site, and templates.
 *
 * Gated on email_automation. The menu item is hidden for plans without it, but the screen still
 * handles a 403 on every section on its own, because a plan can lapse while the app is open and
 * the honest thing to show then is the server's own lock message rather than a blank page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailsScreen(
    /**
     * Null when this screen is a home-screen tab rather than a pushed page. A tab has nothing
     * to go back to, so the arrow is simply not drawn instead of leading nowhere.
     */
    onBack: (() -> Unit)? = null,
    /**
     * Opens Settings from the hamburger. Only supplied when this screen is a home-screen tab,
     * which is also the only time there is no back arrow competing for the same corner.
     */
    onOpenSettings: (() -> Unit)? = null,
    /** Clearance for the floating tab bar when hosted as a tab. Zero as a pushed page. */
    bottomPadding: Dp = 0.dp,
    viewModel: EmailsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email") },
                navigationIcon = {
                    // Only a pushed page has anywhere to go back to. As a tab this corner is
                    // deliberately empty and the title sits against the leading edge.
                    if (onBack != null) {
                        BackButton(onBack)
                    }
                },
                actions = {
                    // The menu took the trailing corner the cloud used to hold. The cloud is
                    // gone rather than moved: pull-to-refresh on the list does the same job now,
                    // and two ways to reload one screen is one too many.
                    if (onOpenSettings != null) {
                        IconButton(onClick = debounced(onOpenSettings)) {
                            Icon(
                                painter = painterResource(AppIcons.Menu),
                                contentDescription = "Settings",
                                // 26dp to match the Inbox header exactly. It was 22dp, the size
                                // it inherited from the navigation slot it used to sit in; the
                                // same glyph in the same corner has to be the same size.
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.fullyLocked) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = AppIcons.Lock,
                    title = "Not included in your plan",
                    message = state.lockMessage
                        ?: "Email automation is available on higher plans.",
                )
            }
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.refreshingLeads,
            onRefresh = viewModel::refreshLeads,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 0.dp,
                bottom = padding.calculateBottomPadding() + bottomPadding + 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.notice != null) {
                item {
                    ErrorBanner(message = state.notice.orEmpty(), onDismiss = viewModel::dismissNotice)
                }
            }

            // ---------------------------------------------------------- stats
            item { GroupGap() }
            item { StatsSection(state.stats) }

            // ---------------------------------------------------------- leads
            item {
                GroupGap()
                // The caption is gone; the count it carried is not. A bare number with no
                // heading would be meaningless, so it names itself.
                RowActions {
                    val total = state.leads.valueOrNullList().sumOf { it.count }
                    if (total > 0) Text(
                        text = "$total leads",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            leadsSection(state)

            // ------------------------------------------------------ templates
            item {
                GroupGap()
                // "New" is a function this screen owns and must keep. Only the caption above
                // it has gone.
                RowActions {
                    if (state.templates is SectionState.Ready) {
                        TextButton(onClick = debounced(viewModel::newTemplate)) {
                            Text("New template")
                        }
                    }
                }
            }
            templatesSection(state, viewModel)

            // ------------------------------------------------------- campaign
            item {
                SupportCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Send campaign",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Sending is not wired up yet. Leads and templates are being " +
                            "collected now so the first campaign has something to send to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(colors = supportButtonColors(), onClick = debounced {}, enabled = false) { Text("Coming soon") }
                }
            }
        }
        }
    }

    val draft = state.editor
    if (draft != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeEditor,
            title = { Text(if (draft.isNew) "New template" else "Edit template") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(name = value) } },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft.subject,
                        onValueChange = { value ->
                            viewModel.updateDraft { it.copy(subject = value) }
                        },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft.body,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(body = value) } },
                        label = { Text("Body") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.editorError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.editorError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(colors = supportButtonColors(), onClick = debounced(viewModel::saveTemplate), enabled = !state.saving) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = debounced(viewModel::closeEditor)) { Text("Cancel") }
            },
        )
    }
}

// ----------------------------------------------------------------- sections

@Composable
private fun StatsSection(state: SectionState<EmailStats>) {
    when (state) {
        is SectionState.Loading -> CardListSkeleton(cards = 2, cardHeight = 76.dp)

        is SectionState.Locked -> LockedCard(state.message)

        is SectionState.Failed -> FailedCard(state.message)

        is SectionState.Ready -> {
            val stats = state.value
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Total registered", stats.totalRegistered, Modifier.weight(1f))
                    StatCard("Emails sent", stats.emailsSent, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Emails failed", stats.emailsFailed, Modifier.weight(1f))
                    StatCard("Emails clicked", stats.emailsClicked, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    SupportCard(modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.leadsSection(state: EmailsUiState) {
    when (val leads = state.leads) {
        is SectionState.Loading -> item { CardListSkeleton(cards = 3, cardHeight = 64.dp) }

        is SectionState.Locked -> item { LockedCard(leads.message) }

        is SectionState.Failed -> item { FailedCard(leads.message) }

        is SectionState.Ready -> {
            if (leads.value.isEmpty()) {
                item {
                    EmptyState(
                        icon = AppIcons.MailOpen,
                        title = "No leads yet",
                        message = "Visitors who leave an email in the widget will show up here, " +
                            "grouped by the site they came from.",
                    )
                }
            } else {
                leads.value.forEach { section -> leadGroup(section) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.leadGroup(section: LeadSection) {
    item(key = "group-" + section.domain) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.domain,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusPill(
                text = section.count.toString() +
                    if (section.count == 1) " lead" else " leads",
            )
        }
    }
    for (lead in section.leads) {
        item(key = "lead-" + lead.id) { LeadRow(lead) }
    }
    item(key = "divider-" + section.domain) { ThinDivider() }
}

@Composable
private fun LeadRow(lead: Lead) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = lead.email,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!lead.name.isNullOrBlank()) {
                Text(
                    text = lead.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusPill(text = if (lead.fromGoogle) "Google" else "Manual")
        Spacer(Modifier.width(8.dp))
        Icon(
            painter = painterResource(
                if (lead.marketingConsent) AppIcons.Check else AppIcons.Lock,
            ),
            contentDescription = if (lead.marketingConsent) {
                "Marketing consent given"
            } else {
                "No marketing consent"
            },
            modifier = Modifier.size(16.dp),
            tint = if (lead.marketingConsent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.templatesSection(
    state: EmailsUiState,
    viewModel: EmailsViewModel,
) {
    when (val templates = state.templates) {
        is SectionState.Loading -> item { CardListSkeleton(cards = 3, cardHeight = 72.dp) }

        is SectionState.Locked -> item { LockedCard(templates.message) }

        is SectionState.Failed -> item { FailedCard(templates.message) }

        is SectionState.Ready -> {
            if (templates.value.isEmpty()) {
                item {
                    EmptyState(
                        icon = AppIcons.MailOpen,
                        title = "No templates",
                        message = "Create one to reuse the same wording across campaigns.",
                        action = {
                            OutlinedButton(onClick = debounced(viewModel::newTemplate)) {
                                Text("New template")
                            }
                        },
                    )
                }
            } else {
                for (template in templates.value) {
                    item(key = "template-" + template.id) {
                        TemplateCard(
                            template = template,
                            busy = state.saving,
                            onEdit = { viewModel.editTemplate(template) },
                            onDelete = { viewModel.deleteTemplate(template) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: EmailTemplate,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    SupportCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = debounced(onEdit),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = template.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (template.seeded) StatusPill(text = "Default")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = template.subject,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = template.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = debounced(onEdit), enabled = !busy) { Text("Edit") }
            TextButton(onClick = debounced(onDelete), enabled = !busy) { Text("Delete") }
        }
    }
}

@Composable
private fun LockedCard(message: String) {
    SupportCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(AppIcons.Lock),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(text = "Locked", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FailedCard(message: String) {
    SupportCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun SectionState<List<LeadSection>>.valueOrNullList(): List<LeadSection> =
    (this as? SectionState.Ready<List<LeadSection>>)?.value ?: emptyList()
