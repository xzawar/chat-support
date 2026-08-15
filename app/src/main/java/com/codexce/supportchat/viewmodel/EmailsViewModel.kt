package com.codexce.supportchat.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codexce.supportchat.data.api.ApiException
import com.codexce.supportchat.data.api.EmailStats
import com.codexce.supportchat.data.api.EmailTemplate
import com.codexce.supportchat.data.api.Lead
import com.codexce.supportchat.data.api.SupportApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One section of the Emails screen.
 *
 * Locked is kept apart from Failed on purpose: a plan that does not include email automation is
 * a normal state with a sentence worth showing, not an error.
 */
sealed class SectionState<out T> {
    object Loading : SectionState<Nothing>()
    data class Locked(val message: String) : SectionState<Nothing>()
    data class Failed(val message: String) : SectionState<Nothing>()
    data class Ready<T>(val value: T) : SectionState<T>()
}

/** Leads for one website, as the screen draws them. */
data class LeadSection(
    val domain: String,
    val count: Int,
    val leads: List<Lead>,
)

/** The template being edited in the dialog. */
data class TemplateDraft(
    val id: String? = null,
    val name: String = "",
    val subject: String = "",
    val body: String = "",
) {
    val isNew: Boolean get() = id == null
}

data class EmailsUiState(
    val stats: SectionState<EmailStats> = SectionState.Loading,
    val leads: SectionState<List<LeadSection>> = SectionState.Loading,
    val templates: SectionState<List<EmailTemplate>> = SectionState.Loading,
    val editor: TemplateDraft? = null,
    val editorError: String? = null,
    val saving: Boolean = false,
    val notice: String? = null,
    /** Pull-to-refresh is running on the lead list. Stats and templates are untouched. */
    val refreshingLeads: Boolean = false,
) {

    /** Every section refused for the same reason, so the whole screen is one locked message. */
    val fullyLocked: Boolean
        get() = stats is SectionState.Locked &&
            leads is SectionState.Locked &&
            templates is SectionState.Locked

    val lockMessage: String?
        get() = (stats as? SectionState.Locked)?.message
            ?: (leads as? SectionState.Locked)?.message
            ?: (templates as? SectionState.Locked)?.message
}

/**
 * Backs the Emails dashboard.
 *
 * The three sections load in parallel and fail independently, because they are three separate
 * reads and one of them being locked or unreachable is no reason to blank the other two.
 */
class EmailsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EmailsUiState())
    val state: StateFlow<EmailsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(
            stats = SectionState.Loading,
            leads = SectionState.Loading,
            templates = SectionState.Loading,
        )

        viewModelScope.launch {
            coroutineScope {
                val stats = async { section { SupportApi.emailStats() } }
                val leads = async { section { loadLeadSections() } }
                val templates = async { section { SupportApi.emailTemplates() } }

                _state.value = _state.value.copy(
                    stats = stats.await(),
                    leads = leads.await(),
                    templates = templates.await(),
                )
            }
        }
    }

    /**
     * Reload the lead book only.
     *
     * Deliberately not [load]: a pull on the lead list should not blank the stats and templates
     * above it, and the leads are the only part of this screen that changes minute to minute.
     */
    fun refreshLeads() {
        if (_state.value.refreshingLeads) return
        _state.value = _state.value.copy(refreshingLeads = true)

        viewModelScope.launch {
            val leads = section { loadLeadSections() }
            _state.value = _state.value.copy(leads = leads, refreshingLeads = false)
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun newTemplate() {
        _state.value = _state.value.copy(editor = TemplateDraft(), editorError = null)
    }

    fun editTemplate(template: EmailTemplate) {
        _state.value = _state.value.copy(
            editor = TemplateDraft(
                id = template.id,
                name = template.name,
                subject = template.subject,
                body = template.body,
            ),
            editorError = null,
        )
    }

    fun updateDraft(block: (TemplateDraft) -> TemplateDraft) {
        val current = _state.value.editor ?: return
        _state.value = _state.value.copy(editor = block(current))
    }

    fun closeEditor() {
        _state.value = _state.value.copy(editor = null, editorError = null)
    }

    fun saveTemplate() {
        val draft = _state.value.editor ?: return

        if (draft.name.isBlank() || draft.subject.isBlank() || draft.body.isBlank()) {
            _state.value = _state.value.copy(editorError = "Name, subject and body are all required.")
            return
        }

        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true, editorError = null)

        viewModelScope.launch {
            try {
                if (draft.id == null) {
                    SupportApi.createEmailTemplate(draft.name, draft.subject, draft.body)
                } else {
                    SupportApi.updateEmailTemplate(draft.id, draft.name, draft.subject, draft.body)
                }
                _state.value = _state.value.copy(saving = false, editor = null)
                refreshTemplates()
            } catch (error: ApiException) {
                // The dialog stays open with the reason in it, so nothing typed is lost.
                _state.value = _state.value.copy(saving = false, editorError = error.message)
            }
        }
    }

    fun deleteTemplate(template: EmailTemplate) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true)

        viewModelScope.launch {
            try {
                SupportApi.deleteEmailTemplate(template.id)
                _state.value = _state.value.copy(saving = false)
                refreshTemplates()
            } catch (error: ApiException) {
                _state.value = _state.value.copy(saving = false, notice = error.message)
            }
        }
    }

    /** Reload only the template list, leaving stats and leads as they are. */
    private suspend fun refreshTemplates() {
        _state.value = _state.value.copy(templates = section { SupportApi.emailTemplates() })
    }

    /**
     * Leads grouped by the site they came from.
     *
     * The counts come from the groups call rather than from the page, so a site with more leads
     * than one page holds still reports its real total instead of the number that fitted.
     */
    private suspend fun loadLeadSections(): List<LeadSection> {
        val page = SupportApi.leads()
        val totals = runCatching { SupportApi.leadGroups() }.getOrDefault(emptyList())
            .associate { it.websiteDomain to it.leadCount }

        return page.leads
            .groupBy { it.websiteDomain }
            .map { (domain, leads) ->
                LeadSection(
                    domain = domain,
                    count = totals[domain] ?: leads.size,
                    leads = leads,
                )
            }
            .sortedByDescending { it.count }
    }

    /** Runs one section's load and turns the two expected failures into states, not crashes. */
    private suspend fun <T> section(block: suspend () -> T): SectionState<T> = try {
        SectionState.Ready(block())
    } catch (error: ApiException) {
        if (error.featureLocked) {
            SectionState.Locked(error.message)
        } else {
            SectionState.Failed(error.message)
        }
    }
}
