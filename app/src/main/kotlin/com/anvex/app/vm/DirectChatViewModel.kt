package com.anvex.app.vm

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.ai.ProviderCapabilities
import com.anvex.core.ai.ProviderClientFactory
import com.anvex.core.android.AppGraph
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.android.session.ChatSession
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.model.AttachmentKind
import com.anvex.core.model.AttachmentMeta
import com.anvex.core.model.ChatMessage
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ThinkingLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * v4.4.6: project-less direct chat. Conversations are message threads stored
 * under synthetic ids ("direct/<uuid>") — no folder, no project. The
 * [ChatSession] runs in direct-chat mode (conversational prompt, no file
 * tools) against an app-private sandbox directory per conversation, so
 * attachments have somewhere real to live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectChatViewModel : ViewModel() {

    data class Conversation(
        val id: String,
        val title: String,
        val lastAt: Long,
        val count: Int,
        val pinned: Boolean = false,
        val archived: Boolean = false,
    )

    data class PreparedAttachments(
        val payloads: List<com.anvex.core.ai.ChatAttachment>,
        val metadata: List<AttachmentMeta>,
        val error: String? = null,
    )

    companion object {
        const val PREFIX = "direct/"
        private const val MAX_ATTACHMENTS = 5
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_FILE_BYTES = 10L * 1024 * 1024
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 20L * 1024 * 1024
        private const val MAX_TEXT_CHARS = 1_000_000
        private const val MAX_ATTACHMENT_CONTEXT_CHARS = 120_000
        private const val MAX_PDF_PAGES = 6
        private const val MAX_PDF_PAGE_WIDTH_PX = 1200

        fun newId(): String = PREFIX + UUID.randomUUID().toString()
    }

    private val graph = AppGraph.get()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    private val _archivedConversations = MutableStateFlow<List<Conversation>>(emptyList())
    val archivedConversations: StateFlow<List<Conversation>> = _archivedConversations.asStateFlow()

    // P-01 (audit 2026-11-03): windowed like the project chat — only
    // the newest UI_WINDOW messages of the active conversation are
    // resident; older pages prepend via [loadOlderMessages].
    private val _olderMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var olderConversationId: String? = null
    private var olderExhausted = false
    private var loadingOlder = false
    private val _hasOlderMessages = MutableStateFlow(false)
    /** True while a [loadOlderMessages] call can still page in history. */
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _session = MutableStateFlow<ChatSession?>(null)

    val sessionState: StateFlow<ChatSession.UiState> = _session
        .flatMapLatest { s -> s?.state ?: flowOf(ChatSession.UiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatSession.UiState())

    /** Lightweight stream snapshot for the thread chrome (mirrors ChatViewModel). */
    val streamActive: StateFlow<Boolean> = sessionState
        .map { s -> s.streaming || s.streamText.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // P-05 (audit round 2): narrow chrome slices for the screen root.
    // The root used to collect the FULL sessionState, so every 80 ms
    // stream publish recomposed the whole screen even though the root
    // only reads slow-changing fields. Each slice below emits only when
    // its own field changes; the fast-moving stream text/reasoning are
    // collected locally by the streaming bubble instead (the same split
    // ChatScreen already uses).
    val uiStreaming: StateFlow<Boolean> = sessionState
        .map { it.streaming }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val uiStatusLine: StateFlow<String?> = sessionState
        .map { it.statusLine }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val uiError: StateFlow<com.anvex.core.ai.AiError?> = sessionState
        .map { it.error }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val uiContextWarning: StateFlow<String?> = sessionState
        .map { it.contextWarning }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val uiQuickActions: StateFlow<List<com.anvex.core.engine.QuickAction>> = sessionState
        .map { it.quickActions }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True while the streaming bubble has text or reasoning to show. */
    val uiHasStreamContent: StateFlow<Boolean> = sessionState
        .map { it.streamText.isNotEmpty() || it.streamReasoning.isNotBlank() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()
    fun clearAttachmentError() { _attachmentError.value = null }

    // Audit fix (2026-11-03): session-build failures (undecryptable key,
    // settings read failure) used to vanish — the session simply never
    // appeared and sends silently timed out. They surface here now.
    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    // Native-engine action approvals, mirroring ChatViewModel: direct
    // chats disable file tools, but the session still routes tool calls
    // (fetch/web) through the same dispatcher.
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null
    private val _pendingApproval = MutableStateFlow<ChatSession.ApprovalPrompt?>(null)
    val pendingApproval: StateFlow<ChatSession.ApprovalPrompt?> = _pendingApproval.asStateFlow()

    fun answerApproval(approved: Boolean) {
        pendingApprovalDeferred?.complete(approved)
        pendingApprovalDeferred = null
        _pendingApproval.value = null
    }

    private fun denyPendingApproval() {
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        _pendingApproval.value = null
    }

    private val _webOn = MutableStateFlow(false)
    val webSearchOn: StateFlow<Boolean> = _webOn.asStateFlow()
    fun setWebSearch(on: Boolean) { _webOn.value = on }

    val defaultProvider: StateFlow<ProviderConfig?> = graph.providers.observeAll()
        .map { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val noProviderConfigured: StateFlow<Boolean> = graph.providers.observeAll()
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val activeModel: StateFlow<String?> = graph.providers.observeAll()
        .map { list -> (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.model }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** All configured providers, for the composer's model switcher. */
    val providerChoices: StateFlow<List<ProviderConfig>> = graph.providers.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The provider the live session is bound to (the default, else first). */
    val activeProviderId: StateFlow<String?> = graph.providers.observeAll()
        .map { list -> (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Composer model switch: making a provider the default is the switch —
     * the init collector rebuilds the session on the new default, and the
     * composer label follows via [activeModel].
     */
    fun switchProvider(id: String) {
        viewModelScope.launch { graph.providers.setDefault(id) }
    }

    /** Multi-model switcher: same expansion as the project chat. */
    val modelChoices: StateFlow<List<ChatViewModel.ModelChoice>> =
        combine(graph.providers.observeAll(), graph.settings.providerModelSets) { providers, sets ->
            expandModelChoices(providers, sets)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Switch to a specific model of a provider (see ChatViewModel). */
    fun switchProviderModel(id: String, model: String) {
        viewModelScope.launch {
            graph.providers.setDefault(id)
            val config = graph.providers.get(id) ?: return@launch
            if (config.model != model) {
                graph.providers.save(config.copy(model = model), null)
            }
        }
    }

    // ---- Drawer search: title match locally, message-content match via
    // the repository's LIKE search (same one Home's project search uses).
    private val _chatQuery = MutableStateFlow("")
    val chatQuery: StateFlow<String> = _chatQuery.asStateFlow()
    private val _contentMatchIds = MutableStateFlow<Set<String>>(emptySet())

    fun setChatQuery(q: String) {
        _chatQuery.value = q
        viewModelScope.launch {
            val needle = q.trim()
            _contentMatchIds.value = if (needle.isEmpty()) {
                emptySet()
            } else {
                runCatching { graph.chat.projectIdsMatching(needle).toSet() }
                    .getOrDefault(emptySet())
            }
        }
    }

    private fun filterConversations(
        list: List<Conversation>,
        q: String,
        ids: Set<String>,
    ): List<Conversation> {
        val needle = q.trim()
        if (needle.isEmpty()) return list
        return list.filter {
            it.title.contains(needle, ignoreCase = true) || it.id in ids
        }
    }

    val visibleConversations: StateFlow<List<Conversation>> =
        kotlinx.coroutines.flow.combine(_conversations, _chatQuery, _contentMatchIds) { list, q, ids ->
            filterConversations(list, q, ids)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val visibleArchivedConversations: StateFlow<List<Conversation>> =
        kotlinx.coroutines.flow.combine(_archivedConversations, _chatQuery, _contentMatchIds) { list, q, ids ->
            filterConversations(list, q, ids)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val webSearchAvailable: StateFlow<Boolean> = defaultProvider
        .map { p -> p != null && ProviderCapabilities.webSearch(p.kind) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        // The session follows (active conversation, default provider): any change
        // closes the old session and builds a fresh one. This also covers the
        // init race where the provider list arrives before the active id.
        viewModelScope.launch {
            val providerFlow = graph.providers.observeAll()
                .map { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
                .distinctUntilChanged { old, new ->
                    // Same rule as ChatViewModel: every field feeding the
                    // built client must participate, or edits to it are
                    // silently ignored until another field changes.
                    old?.id == new?.id && old?.apiKeyEnc == new?.apiKeyEnc &&
                        old?.baseUrl == new?.baseUrl && old?.model == new?.model &&
                        old?.temperature == new?.temperature &&
                        old?.maxTokens == new?.maxTokens &&
                        old?.extraHeaders == new?.extraHeaders
                }
            kotlinx.coroutines.flow.combine(_activeId, providerFlow) { id, config -> id to config }
                .collectLatest { (id, config) ->
                    denyPendingApproval()
                    _session.value?.close()
                    _session.value = null
                    if (id != null && config != null) {
                        try {
                            val runtime = graph.providers.runtimeConfig(config.id)
                            if (runtime == null) {
                                // Was a silent dead end: the provider
                                // exists but its key cannot be read.
                                _sessionError.value =
                                    "The provider could not be started (its saved key could not be read). Open Providers and re-save it."
                            } else {
                                _sessionError.value = null
                                val client = graph.fallbackAiClient() ?: ProviderClientFactory.create(runtime)
                                val thinking = runCatching { graph.settings.settings.first().thinkingLevel }
                                    .getOrDefault(ThinkingLevel.NORMAL)
                                val s = ChatSession(
                                    projectId = id,
                                    fs = RealProjectFileSystem(conversationDir(id)),
                                    chatRepo = graph.chat,
                                    mutationRepo = null,
                                    artifactManager = null,
                                    provider = config,
                                    client = client,
                                    thinkingLevel = thinking,
                                    directChat = true,
                                )
                                s.historyProvider = { graph.chat.historyWindow(id) }
                                s.providerConfigLookup = { providerId -> graph.providers.get(providerId) }
                                s.webSearchEnabledProvider = { _webOn.value && webSearchAvailable.value }
                                s.projectContextProvider = { "" }
                                s.approvalRequester = { prompt ->
                                    val deferred = CompletableDeferred<Boolean>()
                                    pendingApprovalDeferred = deferred
                                    _pendingApproval.value = prompt
                                    deferred.await()
                                }
                                _session.value = s
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A throwing build used to cancel this
                            // collector — the session never appeared and
                            // every later send silently timed out.
                            _sessionError.value =
                                "Chat could not be started: ${e.message?.take(160) ?: "unknown error"}"
                        }
                    }
                }
        }
        viewModelScope.launch {
            _activeId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList())
                    else graph.chat.observeRecentForProject(id)
                }
                .collect { recent ->
                    // Older pages belong to one conversation only; a
                    // switch drops them (activate() resets the state,
                    // this guard covers the init-time activation too).
                    val prefix = if (olderConversationId == _activeId.value) _olderMessages.value else emptyList()
                    _messages.value = (prefix + recent).distinctBy { it.id }
                    if (!olderExhausted) {
                        _hasOlderMessages.value =
                            recent.size >= com.anvex.core.android.repo.ChatRepository.UI_WINDOW || prefix.isNotEmpty()
                    }
                }
        }
        viewModelScope.launch {
            messages.collectLatest { refreshConversations() }
        }
        viewModelScope.launch {
            // Ensure there is always an active conversation to type into —
            // the most recent one, or a fresh id when there are none.
            refreshConversations(force = true)
            if (_activeId.value == null) {
                _activeId.value = _conversations.value.firstOrNull()?.id ?: newId()
            }
        }
    }

    private fun conversationDir(id: String): File =
        File(graph.context.filesDir, "direct-chat/${id.removePrefix(PREFIX)}").apply { mkdirs() }

    private var lastRefreshSignature: String? = null

    private suspend fun refreshConversations(force: Boolean = false) {
        if (!force) {
            // P-06: every message-list emission used to rerun the full
            // 3-queries-per-conversation rebuild even when nothing the
            // list shows had changed. The cheap signature (active
            // conversation + its message tail) skips those no-op
            // rebuilds; meta/delete/init paths force a rebuild. The
            // rebuild itself is now the aggregate query below.
            val msgs = messages.value
            val signature = "${_activeId.value}|${msgs.size}|${msgs.lastOrNull()?.id}|${msgs.lastOrNull()?.createdAt}"
            if (signature == lastRefreshSignature) return
            lastRefreshSignature = signature
        }
        // P-06 (audit 2026-11-03): one aggregate pass + one batch fetch
        // (ChatRepository.directConversationSummaries) replaces the old
        // per-conversation summary/count/lastTime fan-out (3 queries × N).
        val metas = graph.conversationMeta.all()
        val rows = graph.chat.directConversationSummaries().map { summary ->
            val id = summary.projectId
            val first = summary.firstMessage
            val meta = metas[id]
            val derived = first
                ?.takeIf { it.role == com.anvex.core.model.MessageRole.USER }
                ?.content?.trim()?.take(42)
                ?.ifBlank { null }
                ?: first?.content?.trim()?.take(42)?.ifBlank { null }
                ?: "New chat"
            Conversation(
                id,
                meta?.title?.takeIf { it.isNotBlank() } ?: derived,
                summary.lastTime,
                summary.messageCount,
                pinned = meta?.pinned == true,
                archived = meta?.archived == true,
            )
        }.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastAt })
        _conversations.value = rows.filter { !it.archived }
        _archivedConversations.value = rows.filter { it.archived }
    }

    /**
     * Updates one conversation's list metadata, preserving the other flags.
     * Plain (non-receiver) transform: a receiver lambda would let the meta's
     * own pinned/archived/title properties shadow the caller's arguments.
     */
    private fun updateMeta(id: String, transform: (com.anvex.core.android.repo.ConversationMeta) -> com.anvex.core.android.repo.ConversationMeta) {
        viewModelScope.launch(Dispatchers.IO) {
            // Atomic (audit 2026-11-03): the transform runs INSIDE the
            // store's DataStore edit (ConversationMetaStore.update), so
            // two rapid meta updates (pin, then archive) can no longer
            // race a stale read-modify-write and lose each other's flags.
            graph.conversationMeta.update(id) { current ->
                transform(current ?: com.anvex.core.android.repo.ConversationMeta(id, null, false, false))
            }
            withContext(Dispatchers.Main) { refreshConversations(force = true) }
        }
    }

    fun setPinned(id: String, pinned: Boolean) =
        updateMeta(id) { it.copy(pinned = pinned) }

    fun setArchived(id: String, archived: Boolean) =
        updateMeta(id) { it.copy(archived = archived) }

    /** Blank renames clear the override, restoring the derived first-message title. */
    fun renameConversation(id: String, title: String) =
        updateMeta(id) { it.copy(title = title.trim().takeIf { it.isNotBlank() }) }

    fun selectConversation(id: String) {
        if (_activeId.value == id && _session.value != null) return
        activate(id)
    }

    fun newConversation() {
        activate(newId())
    }

    /** Point at [id]; the init collector closes the old session and rebuilds. */
    private fun activate(id: String) {
        if (_activeId.value != id) {
            // Paged-in older messages belong to the previous conversation.
            _olderMessages.value = emptyList()
            olderConversationId = null
            olderExhausted = false
            _hasOlderMessages.value = false
        }
        _activeId.value = id
    }

    /** Page one window of older messages in front of the loaded ones. */
    fun loadOlderMessages() {
        if (loadingOlder || olderExhausted) return
        val id = _activeId.value ?: return
        val oldest = _messages.value.firstOrNull() ?: return
        loadingOlder = true
        viewModelScope.launch {
            try {
                val older = graph.chat.loadOlder(id, oldest.createdAt, oldest.id)
                // Round 2 (NEW-3): the page belongs to the conversation it
                // was requested for. If the user switched while it was in
                // flight, discard it — the completion used to prepend the
                // old conversation's messages into the newly opened one,
                // bypassing the merge collector's ownership guard.
                if (_activeId.value != id) return@launch
                if (older.size < com.anvex.core.android.repo.ChatRepository.UI_WINDOW) olderExhausted = true
                if (olderExhausted) _hasOlderMessages.value = false
                if (older.isEmpty()) {
                    _hasOlderMessages.value = false
                } else {
                    olderConversationId = id
                    _olderMessages.value = older + _olderMessages.value
                    _messages.value = (older + _messages.value).distinctBy { it.id }
                }
            } finally {
                loadingOlder = false
            }
        }
    }

    override fun onCleared() {
        denyPendingApproval()
        _session.value?.close()
        _session.value = null
        super.onCleared()
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_activeId.value == id) {
                // Deleting the ACTIVE conversation mid-turn let the
                // in-flight turn's finalize re-insert its assistant
                // message afterwards — the deleted conversation came
                // back as a one-message ghost (audit 2026-11-03).
                // Cancel the turn and wait (bounded) for it to stop
                // before removing anything.
                val session = _session.value
                session?.cancel()
                if (session != null) {
                    kotlinx.coroutines.withTimeoutOrNull(2_000) {
                        session.state.first { !it.streaming }
                    }
                }
            }
            graph.chat.deleteForProject(id)
            graph.conversationMeta.delete(id)
            runCatching { conversationDir(id).deleteRecursively() }
            withContext(Dispatchers.Main) {
                refreshConversations(force = true)
                if (_activeId.value == id) {
                    // Activate the most recent remaining conversation, or a fresh one.
                    activate(_conversations.value.firstOrNull()?.id ?: newId())
                }
            }
        }
    }

    fun send(text: String, uris: List<Uri>) {
        viewModelScope.launch {
            // The session rebuilds briefly on conversation switch; wait for it
            // instead of dropping the message. Null after the timeout means no
            // provider is configured (the UI disables send in that state).
            val session = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                _session.first { it != null }
            } ?: run {
                // Was a silent drop after a 10 s wait. Say why.
                _attachmentError.value = _sessionError.value
                    ?: "No provider is available yet — add or fix a provider in Settings, then try again."
                return@launch
            }
            val prepared = prepareAttachments(uris)
            if (prepared.error != null) {
                _attachmentError.value = prepared.error
                return@launch
            }
            session.send(text, prepared.payloads, prepared.metadata)
        }
    }

    fun cancel() {
        // Stop revokes a parked approval as well (audit round 2, NEW-2,
        // mirroring ChatViewModel.cancel).
        denyPendingApproval()
        _session.value?.cancel()
    }

    /**
     * True retry (audit 2026-11-03): resends the last user turn WITH its
     * original attachments and drops the failed turn's persisted error
     * stub first — the screen's old resend lost the attachments and
     * left the error row in the thread (and in the model's history).
     * Mirrors ChatViewModel.retryLast.
     */
    fun retryLast() {
        if (sessionState.value.streaming) return
        val lastUser = messages.value.lastOrNull { it.role == com.anvex.core.model.MessageRole.USER } ?: return
        viewModelScope.launch {
            val id = _activeId.value ?: return@launch
            val history = graph.chat.observeRecentForProject(id).first()
            history.filter { it.createdAt > lastUser.createdAt && it.role == com.anvex.core.model.MessageRole.ASSISTANT && it.error != null }
                .forEach { runCatching { graph.chat.delete(it.id) } }
            val uris = lastUser.attachments.mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }
            send(lastUser.content, uris)
        }
    }

    /** Clear every error surface this VM owns (session + attachment + build). */
    fun clearError() {
        _session.value?.clearError()
        _attachmentError.value = null
        _sessionError.value = null
    }

    /**
     * v4.4.6: "create project from conversation" handoff. Creates a blank
     * project and hands the (capped) transcript to the project's Agent tab
     * through the existing [NewProjectHandoff] mechanism.
     */
    private val _creatingProject = MutableStateFlow(false)

    fun createProjectFromActive(title: String, onOpen: (projectId: String) -> Unit) {
        if (_creatingProject.value) return
        val id = _activeId.value ?: return
        _creatingProject.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
            val transcript = runCatching {
                graph.chat.observeForProject(id).first()
            }.getOrDefault(emptyList())
            val handoff = buildHandoffPrompt(title, transcript)
            when (val result = graph.projects.create(
                name = title.trim().take(60).ifBlank { "From chat" },
                type = com.anvex.core.model.ProjectType.UNKNOWN,
                description = "Created from a direct-chat conversation.",
            )) {
                is com.anvex.core.android.repo.RepoResult.Ok -> {
                    com.anvex.app.ui.screens.home.NewProjectHandoff.pendingPrompt = handoff
                    graph.settings.setLastProject(result.value.id)
                    withContext(Dispatchers.Main) { onOpen(result.value.id) }
                }
                is com.anvex.core.android.repo.RepoResult.Err -> {
                    // Surface via the attachment error banner — a generic notice channel.
                    withContext(Dispatchers.Main) {
                        _attachmentError.value = "${result.title}: ${result.detail}"
                    }
                }
            }
            } finally {
                _creatingProject.value = false
            }
        }
    }

    private fun buildHandoffPrompt(title: String, transcript: List<ChatMessage>): String = buildString {
        append("The user discussed the following with me in a direct chat")
            .append(" (no project). Continue helping with it in this project.\n\n")
        append("--- conversation: ").append(title.trim().take(80)).append(" ---\n")
        var used = length
        for (msg in transcript) {
            if (used >= 6_000) {
                append("\n[earlier messages trimmed]\n")
                break
            }
            val who = if (msg.role == com.anvex.core.model.MessageRole.USER) "User" else "Assistant"
            val line = "$who: ${msg.content.trim().take(1_500)}\n\n"
            append(line)
            used += line.length
        }
    }

    // ---- attachments (adapted from ChatViewModel; persists into the
    // conversation's app-private sandbox instead of a project workspace) ----

    private suspend fun prepareAttachments(uris: List<Uri>): PreparedAttachments = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext PreparedAttachments(emptyList(), emptyList())
        val id = _activeId.value ?: return@withContext PreparedAttachments(emptyList(), emptyList(), "No active conversation.")
        val fs = RealProjectFileSystem(conversationDir(id))
        val resolver = graph.context.contentResolver
        val payloads = ArrayList<com.anvex.core.ai.ChatAttachment>()
        val metadata = ArrayList<AttachmentMeta>()
        val uniqueUris = uris.distinct().take(MAX_ATTACHMENTS)
        var totalBytes = 0L

        for (uri in uniqueUris) {
            val mime = resolver.getType(uri)?.lowercase() ?: "application/octet-stream"
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val safeName = sanitizeAttachmentName(name)
            val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
            val image = mime.startsWith("image/")
            val isPdf = mime == "application/pdf" || name.lowercase().endsWith(".pdf")
            val max = if (image) MAX_IMAGE_BYTES else MAX_FILE_BYTES
            if (size > max) {
                return@withContext PreparedAttachments(emptyList(), emptyList(),
                    "$name is too large. Maximum is ${max / (1024 * 1024)} MB.")
            }
            if (totalBytes + size > MAX_TOTAL_ATTACHMENT_BYTES) {
                return@withContext PreparedAttachments(emptyList(), emptyList(),
                    "Attachments are too large together. Maximum total is ${MAX_TOTAL_ATTACHMENT_BYTES / (1024 * 1024)} MB.")
            }
            val bytes = resolver.openInputStream(uri)?.use { input -> readBounded(input, max) }
                ?: return@withContext PreparedAttachments(emptyList(), emptyList(), "Cannot read $name")
            if (bytes.size.toLong() > max) {
                return@withContext PreparedAttachments(emptyList(), emptyList(),
                    "$name is too large. Maximum is ${max / (1024 * 1024)} MB.")
            }
            totalBytes += bytes.size

            val metadataItem = AttachmentMeta(
                UUID.randomUUID().toString(), uri.toString(), name, mime, size,
                if (image) AttachmentKind.IMAGE else AttachmentKind.FILE
            )

            when {
                isPdf -> {
                    val pages = runCatching {
                        com.anvex.core.android.pdf.PdfPageRenderer.render(
                            graph.context, bytes, MAX_PDF_PAGES, MAX_PDF_PAGE_WIDTH_PX)
                    }.getOrDefault(emptyList())
                    if (pages.isEmpty()) {
                        return@withContext PreparedAttachments(emptyList(), emptyList(),
                            "Could not read pages from $name. The file may be encrypted or damaged.")
                    }
                    pages.forEachIndexed { index, jpeg ->
                        payloads += com.anvex.core.ai.ChatAttachment(
                            "$name (page ${index + 1})", "image/jpeg",
                            Base64.encodeToString(jpeg, Base64.NO_WRAP), null, true)
                    }
                }
                image -> {
                    payloads += com.anvex.core.ai.ChatAttachment(
                        name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP), null, true)
                }
                mime.startsWith("text/") || isTextExtension(name) -> {
                    val text = bytes.toString(Charsets.UTF_8)
                    payloads += com.anvex.core.ai.ChatAttachment(
                        name, mime, text = text.take(MAX_TEXT_CHARS), isImage = false)
                }
                isZip(name, mime) -> {
                    val storedPath = persistAttachment(fs, safeName, bytes)
                        ?: return@withContext PreparedAttachments(emptyList(), emptyList(),
                            "Cannot save $name to the attachment store.")
                    val inspection = inspectZip(bytes)
                    payloads += com.anvex.core.ai.ChatAttachment(
                        name = name,
                        mimeType = mime,
                        text = buildString {
                            append("The user attached a binary ZIP archive named ").append(name).append(".\n")
                            append("The original archive was saved in the chat attachment store at: ").append(storedPath).append("\n")
                            append(inspection)
                        }.take(MAX_ATTACHMENT_CONTEXT_CHARS),
                        isImage = false,
                    )
                }
                else -> {
                    val storedPath = persistAttachment(fs, safeName, bytes)
                        ?: return@withContext PreparedAttachments(emptyList(), emptyList(),
                            "Cannot save $name to the attachment store.")
                    payloads += com.anvex.core.ai.ChatAttachment(
                        name = name,
                        mimeType = mime,
                        text = "The user attached a binary file named $name (${mime}, ${size} bytes). " +
                            "It was saved in the chat attachment store at $storedPath. " +
                            "The provider cannot inspect its binary bytes directly; do not claim to have read them.",
                        isImage = false,
                    )
                }
            }
            metadata += metadataItem
        }
        PreparedAttachments(payloads, metadata)
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes.toInt(), 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= maxBytes) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxBytes) {
                out.write(buffer, 0, (maxBytes - out.size()).toInt())
                break
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun persistAttachment(fs: RealProjectFileSystem, safeName: String, bytes: ByteArray): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val path = "attachments/$digest-$safeName"
        return runCatching {
            fs.mkdirs("attachments")
            if (!fs.exists(path)) fs.writeFileBytes(path, bytes)
            path
        }.getOrNull()
    }

    private fun sanitizeAttachmentName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_', '-')
        return cleaned.take(120).ifBlank { "attachment.bin" }
    }

    private fun isTextExtension(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "markdown", "json", "xml", "csv", "html", "htm", "css", "js", "mjs", "cjs",
        "ts", "tsx", "jsx", "kt", "java", "py", "rb", "go", "rs", "yaml", "yml", "toml",
        "gradle", "kts", "properties", "log", "srt", "vtt", "ass" -> true
        else -> false
    }

    private fun isZip(name: String, mime: String): Boolean =
        mime == "application/zip" || mime == "application/x-zip-compressed" ||
            name.substringAfterLast('.', "").equals("zip", ignoreCase = true)

    private fun inspectZip(bytes: ByteArray): String {
        val maxEntries = 200
        val maxExtractedBytes = 2L * 1024 * 1024
        val maxSnippetChars = 80_000
        var entries = 0
        var extracted = 0L
        val textExtensions = setOf(
            "txt", "md", "markdown", "json", "xml", "csv", "html", "htm", "css",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "java", "py", "rb",
            "go", "rs", "yaml", "yml", "toml", "gradle", "kts", "properties"
        )
        return try {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
                val sb = StringBuilder("ZIP contents (bounded inspection):\n")
                var entry = zip.nextEntry
                while (entry != null && entries < maxEntries && extracted < maxExtractedBytes) {
                    entries++
                    val raw = entry.name
                    val safe = runCatching { PathSanitizer.sanitize(raw) }.getOrNull()
                    if (safe == null) {
                        sb.append("- [blocked unsafe path] ").append(raw.take(180)).append('\n')
                    } else if (entry.isDirectory) {
                        sb.append("- ").append(safe).append("/\n")
                    } else {
                        sb.append("- ").append(safe).append('\n')
                        val ext = safe.substringAfterLast('.', "").lowercase()
                        if (ext in textExtensions) {
                            val remaining = (maxExtractedBytes - extracted).coerceAtMost(512 * 1024L).toInt()
                            val data = readBounded(zip, remaining.toLong())
                            extracted += data.size
                            val text = data.toString(Charsets.UTF_8)
                            if (sb.length < maxSnippetChars && text.isNotBlank()) {
                                sb.append("### ").append(safe).append('\n')
                                sb.append(text.take((maxSnippetChars - sb.length).coerceAtLeast(0))).append("\n\n")
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
                if (entry != null) {
                    sb.append("...[inspection limit reached — the archive contains more entries than are listed here; absence from this list is not evidence of absence from the archive]\n")
                }
                sb.append("\n$entries entries listed.\n")
                sb.toString().take(MAX_ATTACHMENT_CONTEXT_CHARS)
            }
        } catch (_: Exception) {
            "Could not inspect the ZIP archive.\n"
        }
    }
}
