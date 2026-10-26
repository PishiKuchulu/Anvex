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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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

    val messages: StateFlow<List<ChatMessage>> = _activeId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else graph.chat.observeForProject(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _session = MutableStateFlow<ChatSession?>(null)

    val sessionState: StateFlow<ChatSession.UiState> = _session
        .flatMapLatest { s -> s?.state ?: flowOf(ChatSession.UiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatSession.UiState())

    /** Lightweight stream snapshot for the thread chrome (mirrors ChatViewModel). */
    val streamActive: StateFlow<Boolean> = sessionState
        .map { s -> s.streaming || s.streamText.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()
    fun clearAttachmentError() { _attachmentError.value = null }

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
                    old?.id == new?.id && old?.apiKeyEnc == new?.apiKeyEnc &&
                        old?.baseUrl == new?.baseUrl && old?.model == new?.model
                }
            kotlinx.coroutines.flow.combine(_activeId, providerFlow) { id, config -> id to config }
                .collectLatest { (id, config) ->
                    _session.value?.close()
                    _session.value = null
                    if (id != null && config != null) {
                        val runtime = graph.providers.runtimeConfig(config.id)
                        if (runtime != null) {
                            val client = graph.fallbackAiClient() ?: ProviderClientFactory.create(runtime)
                            val thinking = graph.settings.settings.first().thinkingLevel
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
                            s.historyProvider = { graph.chat.observeForProject(id).first() }
                            s.webSearchEnabledProvider = { _webOn.value && webSearchAvailable.value }
                            s.projectContextProvider = { "" }
                            _session.value = s
                        }
                    }
                }
        }
        viewModelScope.launch {
            messages.collectLatest { refreshConversations() }
        }
        viewModelScope.launch {
            // Ensure there is always an active conversation to type into —
            // the most recent one, or a fresh id when there are none.
            refreshConversations()
            if (_activeId.value == null) {
                _activeId.value = _conversations.value.firstOrNull()?.id ?: newId()
            }
        }
    }

    private fun conversationDir(id: String): File =
        File(graph.context.filesDir, "direct-chat/${id.removePrefix(PREFIX)}").apply { mkdirs() }

    private suspend fun refreshConversations() {
        val ids = graph.chat.directConversationIds()
        val metas = graph.conversationMeta.all()
        val rows = ids.mapNotNull { id ->
            runCatching {
                val (first, count) = graph.chat.conversationSummary(id)
                val lastAt = graph.chat.lastMessageTime(id)
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
                    lastAt,
                    count,
                    pinned = meta?.pinned == true,
                    archived = meta?.archived == true,
                )
            }.getOrNull()
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
            val current = graph.conversationMeta.all()[id]
                ?: com.anvex.core.android.repo.ConversationMeta(id, null, false, false)
            graph.conversationMeta.upsert(transform(current))
            withContext(Dispatchers.Main) { refreshConversations() }
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
        _activeId.value = id
    }

    override fun onCleared() {
        _session.value?.close()
        _session.value = null
        super.onCleared()
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.chat.deleteForProject(id)
            graph.conversationMeta.delete(id)
            runCatching { conversationDir(id).deleteRecursively() }
            withContext(Dispatchers.Main) {
                refreshConversations()
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
            } ?: return@launch
            val prepared = prepareAttachments(uris)
            if (prepared.error != null) {
                _attachmentError.value = prepared.error
                return@launch
            }
            session.send(text, prepared.payloads, prepared.metadata)
        }
    }

    fun cancel() {
        _session.value?.cancel()
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
                sb.append("\n$entries entries listed.\n")
                sb.toString().take(MAX_ATTACHMENT_CONTEXT_CHARS)
            }
        } catch (_: Exception) {
            "Could not inspect the ZIP archive.\n"
        }
    }
}
