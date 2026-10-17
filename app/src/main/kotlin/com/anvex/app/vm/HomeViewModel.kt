package com.anvex.app.vm

import com.anvex.core.engine.ArtifactHygienePolicy
import com.anvex.app.share.IncomingShare
import kotlinx.coroutines.flow.first
import com.anvex.core.model.MessageRole
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.RepoResult
import com.anvex.core.model.Project
import com.anvex.core.model.ProjectType
import com.anvex.core.model.ProviderConfig
import com.anvex.core.engine.ProjectTemplates
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    companion object {
        private const val KEY_PINNED = "pinned_projects"
    }


    private val graph = AppGraph.get()
    private val projectsRepo = graph.projects
    private val providersRepo = graph.providers

    val projects: StateFlow<List<Project>> = projectsRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Phase 2.2: pinned chats, kept in SharedPreferences (no database migration). */
    private val prefs = graph.context.getSharedPreferences("anvex_home", android.content.Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow(prefs.getStringSet(KEY_PINNED, emptySet()).orEmpty().toSet())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    fun togglePin(projectId: String) {
        val current = _pinned.value
        val next = if (projectId in current) current - projectId else current + projectId
        _pinned.value = next
        prefs.edit().putStringSet(KEY_PINNED, next).apply()
    }

    /** Phase 2.3: builds a Markdown transcript of one chat and hands it to [onReady]. */
    fun buildTranscript(projectId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val name = projectsRepo.get(projectId)?.name ?: "Anvex chat"
            val messages = graph.chat.observeForProject(projectId).first()
            val text = buildString {
                appendLine("# $name")
                appendLine()
                messages.forEach { m ->
                    appendLine(if (m.role == MessageRole.USER) "### You" else "### Assistant")
                    appendLine()
                    if (m.content.isNotBlank()) appendLine(m.content) else appendLine("_(no text)_")
                    m.attachments.forEach { appendLine(); appendLine("_Attachment: ${it.name}_") }
                    m.error?.let { appendLine(); appendLine("> $it") }
                    appendLine()
                }
            }
            // Secrets pasted into a chat must not leave the device in an export (Phase 5 rule).
            onReady(ArtifactHygienePolicy.redactSecrets("chat.md", text.toByteArray()).bytes.toString(Charsets.UTF_8))
        }
    }

    /** Phase 2 search: filters by project name and by message text. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val messageMatches: StateFlow<Set<String>> = _query
        .debounce(250)
        .mapLatest { q ->
            val needle = q.trim()
            if (needle.isEmpty()) emptySet() else graph.chat.projectIdsMatching(needle).toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Filtered by search, pinned chats first (stable sort keeps the original order otherwise). */
    val visibleProjects: StateFlow<List<Project>> = combine(projects, _query, messageMatches, _pinned) { list, q, ids, pinnedSet ->
        val needle = q.trim()
        val filtered = if (needle.isEmpty()) list
        else list.filter { it.name.contains(needle, ignoreCase = true) || it.id in ids }
        filtered.sortedByDescending { it.id in pinnedSet }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(value: String) { _query.value = value }

    /** Phase 3.2: the payload waiting from the share sheet, if any. */
    val sharePending: StateFlow<IncomingShare.Payload?> = IncomingShare.pending

    fun shareInto(projectId: String) = IncomingShare.assignTo(projectId)

    fun dismissShare() = IncomingShare.dismiss()

    fun shareIntoNewChat(onOpen: (String) -> Unit) {
        viewModelScope.launch {
            when (val result = projectsRepo.create(name = "Shared chat", type = ProjectType.STATIC_HTML)) {
                is RepoResult.Ok -> {
                    IncomingShare.assignTo(result.value.id)
                    onOpen(result.value.id)
                }
                is RepoResult.Err -> _events.emit("${result.title}: ${result.detail}")
            }
        }
    }

    val providers: StateFlow<List<ProviderConfig>> = providersRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class NewProjectUi(
        val name: String = "",
        val prompt: String = "",
        val type: ProjectType = ProjectType.STATIC_HTML,
        val providerId: String? = null,
        val parentWorkspaceUri: String? = null,
        val starter: ProjectTemplates.Starter = ProjectTemplates.Starter.BLANK,
    )

    val newProjectUi = MutableStateFlow(NewProjectUi())

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    fun updateNewProject(transform: NewProjectUi.() -> NewProjectUi) {
        newProjectUi.value = newProjectUi.value.transform()
    }

    fun createAndOpen(onOpen: (String) -> Unit) {
        val ui = newProjectUi.value
        if (ui.name.isBlank()) {
            _events.tryEmit("Project name is required")
            return
        }
        viewModelScope.launch {
            try {
                when (val result = projectsRepo.create(
                    ui.name,
                    ui.type,
                    description = ui.prompt,
                    workspaceUri = ui.parentWorkspaceUri?.let(android.net.Uri::parse),
                    starter = ui.starter,
                )) {
                    is RepoResult.Ok -> {
                        graph.settings.setLastProject(result.value.id)
                        newProjectUi.value = NewProjectUi()
                        onOpen(result.value.id)
                    }
                    is RepoResult.Err -> _events.tryEmit("${result.title}: ${result.detail}")
                }
            } catch (t: Throwable) {
                _events.tryEmit("Project creation failed: ${t.message ?: "Unexpected error"}")
            }
        }
    }

    fun openExistingFolder(uri: android.net.Uri, displayName: String, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = projectsRepo.openExistingFolder(uri, displayName)) {
                is RepoResult.Ok -> onOpen(r.value.id)
                is RepoResult.Err -> _events.tryEmit("${r.title}: ${r.detail}")
            }
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch {
            when (val r = projectsRepo.delete(projectId)) {
                is RepoResult.Err -> _events.tryEmit("${r.title}: ${r.detail}")
                is RepoResult.Ok -> _events.tryEmit("Project deleted")
            }
        }
    }

    fun rename(projectId: String, newName: String) {
        viewModelScope.launch {
            when (val r = projectsRepo.rename(projectId, newName)) {
                is RepoResult.Err -> _events.tryEmit("${r.title}: ${r.detail}")
                is RepoResult.Ok -> Unit
            }
        }
    }

    /** v1.1 §23: duplicate a project. */
    fun duplicate(projectId: String) {
        viewModelScope.launch {
            when (val r = projectsRepo.duplicate(projectId)) {
                is RepoResult.Ok -> _events.tryEmit("Duplicated as \"${r.value.name}\"")
                is RepoResult.Err -> _events.tryEmit("${r.title}: ${r.detail}")
            }
        }
    }

    fun exportZip(projectId: String, uri: Uri) {
        viewModelScope.launch {
            when (val r = projectsRepo.exportZip(projectId, uri)) {
                is RepoResult.Ok -> _events.tryEmit("Exported ${r.value} files")
                is RepoResult.Err -> _events.tryEmit("${r.title}: ${r.detail}")
            }
        }
    }

    fun importZip(displayName: String, uri: Uri, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = projectsRepo.importZip(displayName, uri)) {
                is RepoResult.Ok -> onOpen(r.value.id)
                is RepoResult.Err -> _events.tryEmit("${r.title}: ${r.detail}")
            }
        }
    }

    /** Default provider selection for the new-project flow (derived, no blocking). */
    val defaultProvider: StateFlow<ProviderConfig?> =
        providersRepo.observeAll()
            .map { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
