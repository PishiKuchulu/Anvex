package com.anvex.core.android.harness

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import com.anvex.core.ai.mcp.McpClient
import com.anvex.core.ai.mcp.McpServerConfig
import com.anvex.core.ai.mcp.mergeMcpConfigsForSave
import com.anvex.core.android.AppGraph
import com.anvex.core.android.KeystoreCrypto
import com.anvex.core.android.fs.ProjectFileObserver
import com.anvex.core.android.runtime.AndroidTerminalSession
import com.anvex.core.android.session.ChatSession
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.diagnostics.LightDiagnostics
import com.anvex.core.engine.vcs.GitLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.0 (GOAL 2 + GOAL 6): the JavaScript↔Kotlin harness bridge.
 *
 * The web harness (served from app assets by HarnessWebServer) runs inside the
 * system WebView and drives the *real* Android runtime through this bridge:
 *  - durable agent turns (ChatSession → ActionDispatcher → real file/shell tools)
 *  - interactive terminal (AndroidTerminalSession)
 *  - local git (GitLite)
 *  - file tree + watcher (ProjectFileObserver) + light diagnostics
 *  - skill store (marketplace/installer; install from a git tarball or enabled/removed)
 *  - MCP client (streamable HTTP, credentials Keystore-encrypted at rest)
 *  - in-app Gradle builds with streamed logs
 *  - GOAL 6: ZIP save/share — JSZip (bundled locally, offline) assembles the
 *    archive in JS and passes base64 to [saveZip]/[shareZip]; Kotlin decodes and
 *    stores it in MediaStore.Downloads via the IS_PENDING 1→0 publish flow
 *    (API 29+) or the external files dir (API 26-28, shared through FileProvider),
 *    then notifies JS and the hosting screen for the Open/Share snackbar.
 *    v4.1 (Nebula): blob:/URL.createObjectURL are never used — they are blocked
 *    by the preview WebView; the payload always travels as base64 text.
 *
 * Threading contract: @JavascriptInterface methods run on the WebView binder
 * thread; quick lookups answer synchronously with a JSON string, long work is
 * dispatched to the bridge scope and reported through `__harnessEvent` events
 * delivered on the main thread.
 */
class HarnessBridge(
    val projectId: String,
    private val onZipSaved: (fileName: String, uri: String, shareIntent: Intent?) -> Unit,
) {
    private val graph = AppGraph.get()
    private val context: Context = graph.context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webView: android.webkit.WebView? = null
    private var session: ChatSession? = null
    // H-04: the session itself as a flow, so the watcher follows the
    // CURRENT session instead of the one captured at watcher start.
    private val sessionFlow = MutableStateFlow<ChatSession?>(null)
    // Fingerprint of the session inputs (id, model, URL, key hash —
    // never the key). A provider-table change that leaves these alone
    // (renaming an inactive provider, editing a name) must NOT rebuild
    // the session mid-stream.
    private var lastSessionKey: String? = null
    private var terminal: AndroidTerminalSession? = null
    private var terminalJob: Job? = null
    private var observer: ProjectFileObserver? = null

    @Volatile private var providerPresent: Boolean = false
    @Volatile private var sessionReady: Boolean = false

    /** Observable by the hosting Compose screen. */
    val buildRunning = MutableStateFlow(false)

    private val running = AtomicBoolean(false)

    // ------------------------------------------------------------------ lifecycle

    fun attach(webView: android.webkit.WebView) {
        this.webView = webView
    }

    fun start() {
        scope.launch {
            // Provider presence cache for the synchronous hasProvider() probe.
            graph.providers.observeAll().collect { providerPresent = it.isNotEmpty() }
        }
        scope.launch {
            // Build (and re-build) the ChatSession only when the effective
            // session inputs change — not on every provider-table edit.
            graph.providers.observeAll().collectLatest { list ->
                val config = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
                if (config == null) {
                    session?.close(); session = null; sessionFlow.value = null
                    lastSessionKey = null; sessionReady = false
                    return@collectLatest
                }
                val runtime = runCatching { graph.providers.runtimeConfig(config.id) }.getOrNull()
                if (runtime == null) {
                    // Key undecryptable (or config gone): the old session
                    // would run with a stale key — close it, fail closed.
                    session?.close(); session = null; sessionFlow.value = null
                    lastSessionKey = null; sessionReady = false
                    return@collectLatest
                }
                val sessionKey = listOf(
                    config.id, config.model, config.baseUrl,
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(runtime.apiKey.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) },
                ).joinToString("|")
                if (session != null && sessionKey == lastSessionKey) {
                    // Irrelevant edit (inactive provider renamed, name
                    // changed): keep the live session, do not cancel a
                    // stream in flight.
                    return@collectLatest
                }
                val client = graph.fallbackAiClient()
                    ?: runCatching { com.anvex.core.ai.ProviderClientFactory.create(runtime) }.getOrNull()
                if (client == null) {
                    session?.close(); session = null; sessionFlow.value = null
                    lastSessionKey = null; sessionReady = false
                    return@collectLatest
                }
                session?.close()
                val fs = graph.projects.fileSystemForAsync(projectId)
                val thinking = graph.settings.settings.first().thinkingLevel
                session = ChatSession(
                    projectId = projectId,
                    fs = fs,
                    chatRepo = graph.chat,
                    mutationRepo = graph.mutations,
                    artifactManager = graph.artifactManager,
                    provider = config,
                    client = client,
                    thinkingLevel = thinking,
                    onProjectFilesChanged = { graph.projects.refreshType(projectId) },
                    extras = graph.sessionExtras(),
                ).also { s ->
                    s.historyProvider = { graph.chat.observeForProject(projectId).first() }
                    s.thinkingLevel = thinking
                }
                sessionFlow.value = session
                lastSessionKey = sessionKey
                sessionReady = true
            }
        }
        scope.launch {
            val root = graph.projects.fileSystemForAsync(projectId).let { fs ->
                (fs as? com.anvex.core.android.fs.RealProjectFileSystem)?.rootDirectory()
            }
            if (root != null) {
                val fileObserver = ProjectFileObserver(root) { path, kind ->
                    emitEvent("file_event") {
                        put("path", path)
                        put("kind", kind.name)
                    }
                }
                observer = fileObserver
                fileObserver.start()
                scope.launch {
                    fileObserver.events.collect { (path, _) ->
                        val interesting = path.endsWith(".json") || path.endsWith(".js") || path.endsWith(".html") ||
                            path.endsWith(".css") || path.endsWith(".jsx") || path.endsWith(".ts") || path.endsWith(".tsx")
                        if (interesting) runDiagnostics(path)
                    }
                }
            }
        }
        startSessionWatcher()
    }

    fun stop() {
        terminalJob?.cancel(); terminalJob = null
        terminal?.close(); terminal = null
        observer?.close(); observer = null
        session?.close(); session = null; sessionFlow.value = null
        lastSessionKey = null
        sessionReady = false
        // Release the WebView reference so a stopped bridge can no longer push
        // JS events and does not keep the view (and its Activity context) alive.
        webView = null
        scope.cancel()
    }

    private var sessionWatcher: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startSessionWatcher() {
        if (sessionWatcher != null) return
        sessionWatcher = scope.launch {
            var lastStreaming = false
            var lastTextLen = 0
            var lastArtifactJson: String? = null
            var lastRevision = 0
            var lastText = ""
            var deltaCount = 0
            // H-04: the old watcher captured ONE session and collected its
            // StateFlow forever (StateFlows never complete), so after a
            // provider change the UI kept receiving the CLOSED session's
            // events. Follow the session itself: when the session object
            // changes, drop the old stream and follow the new one, with
            // the edge detectors reset so the new session's initial state
            // cannot emit a spurious turn_end.
            sessionFlow.flatMapLatest { s ->
                if (s == null) emptyFlow()
                else flow {
                    lastStreaming = false
                    lastTextLen = 0
                    lastText = ""
                    deltaCount = 0
                    lastArtifactJson = null
                    lastRevision = 0
                    emitAll(s.state)
                }
            }.collect { state ->
                    if (state.streaming && !lastStreaming) emitEvent("turn_start") { put("streaming", true) }
                    if (state.streamText.length != lastTextLen) {
                        // L-02: send only the new suffix (O(n) instead of O(n²) over the
                        // JS bridge); resend the full text when it was rewritten or every
                        // 40 events so the UI can never drift.
                        val text = state.streamText
                        deltaCount++
                        val appendOnly = lastText.isNotEmpty() && text.startsWith(lastText) && deltaCount % 40 != 0
                        val offset = if (appendOnly) lastText.length else 0
                        val payload = if (appendOnly) text.substring(offset) else text
                        lastTextLen = text.length
                        lastText = text
                        emitEvent("delta") { put("text", payload); put("offset", offset) }
                    }
                    state.currentArtifact?.let { artifact ->
                        val payload = json.encodeToString(ArtifactUiDto.from(artifact))
                        if (lastArtifactJson != payload) {
                            lastArtifactJson = payload
                            emitRaw("artifact", payload)
                        }
                    }
                    if (state.filesRevision != lastRevision) {
                        lastRevision = state.filesRevision
                        emitEvent("files_changed") { put("revision", state.filesRevision) }
                    }
                    if (!state.streaming && lastStreaming) {
                        lastStreaming = false
                        lastArtifactJson = null
                        lastTextLen = 0
                        lastText = ""
                        val errorObj: JsonObject? = state.error?.let { e ->
                            buildJsonObject {
                                put("title", e.userTitle)
                                put("message", e.message)
                            }
                        }
                        emitEvent("turn_end") {
                            put("success", state.error == null && state.engineError == null)
                            state.engineError?.let { put("engineError", it) }
                            errorObj?.let { put("error", it) }
                        }
                    }
                    lastStreaming = state.streaming
                }
        }
    }

    @Serializable
    internal data class ArtifactUiDto(
        val id: String,
        val title: String,
        val actions: List<ActionDto> = emptyList(),
    ) {
        @Serializable
        data class ActionDto(
            val actionId: String,
            val type: String,
            val filePath: String? = null,
            val command: String? = null,
            val status: String,
            val note: String? = null,
        )

        companion object {
            fun from(artifact: ChatSession.ArtifactUi): ArtifactUiDto = ArtifactUiDto(
                id = artifact.id,
                title = artifact.title,
                actions = artifact.actions.map { a ->
                    ActionDto(a.actionId, a.type, a.filePath, a.command, a.status.name, a.note)
                },
            )
        }
    }

    // ------------------------------------------------------------------ JS interface

    /**
     * v4.4 lint: the JS surface must not be typed `Any` (JavascriptInterface rule).
     * Every method on the object below is annotated @JavascriptInterface; this marker
     * gives the expression a named static type without changing the runtime surface.
     */
    interface JsSurface
    val jsInterface: JsSurface = object : JsSurface {
        @JavascriptInterface
        fun version(): String = ok {
            // v4.10.1 (BUG-14): was a hardcoded "4.4.1" long after it went stale.
            put("version", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown")
        }

        @JavascriptInterface
        fun state(): String = runCatching {
            val project = kotlinx.coroutines.runBlocking { graph.projects.get(projectId) }
            ok {
                put("projectId", projectId)
                put("projectName", project?.name ?: "")
                put("projectType", project?.type?.name ?: "UNKNOWN")
                put("hasProvider", providerPresent)
                put("sessionReady", sessionReady)
                put("toolchain", graph.toolchain.isInstalled())
            }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun hasProvider(): String = ok { put("present", providerPresent) }

        @JavascriptInterface
        fun listFiles(): String = runCatching {
            val fs = kotlinx.coroutines.runBlocking { graph.projects.fileSystemForAsync(projectId) }
            ok {
                put("files", buildJsonArray { fs.walk().forEach { add(JsonPrimitive(it)) } })
            }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun readFile(path: String): String = runCatching {
            val safe = PathSanitizer.sanitizeOrNull(path) ?: return bridgeError("Unsafe path")
            val fs = kotlinx.coroutines.runBlocking { graph.projects.fileSystemForAsync(projectId) }
            // M-07: the old code read the whole file into memory and only
            // then checked the size — a multi-MB file OOM'd first. Check
            // the size before reading (the post-read check stays as the
            // exact guard for encodings whose byte count differs).
            val size = fs.fileSize(safe)
            if (size != null && size > LightDiagnostics.MAX_FILE_BYTES) return bridgeError("File is too large to load.")
            val content = fs.readFile(safe) ?: return bridgeError("File not found: $safe")
            if (content.toByteArray(Charsets.UTF_8).size > LightDiagnostics.MAX_FILE_BYTES) return bridgeError("File is too large to load.")
            ok { put("path", safe); put("content", content) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun writeFile(path: String, content: String): String = runCatching {
            val safe = PathSanitizer.sanitizeOrNull(path) ?: return bridgeError("Unsafe path")
            if (content.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_WRITE_BYTES) return bridgeError("Content too large for a bridge write.")
            val fs = kotlinx.coroutines.runBlocking { graph.projects.fileSystemForAsync(projectId) }
            val before = fs.readFile(safe)
            fs.writeFile(safe, content)
            kotlinx.coroutines.runBlocking { graph.recordUserEdit(projectId, safe, before, content) }
            emitEvent("file_event") { put("path", safe); put("kind", "MODIFIED") }
            scope.launch { runDiagnostics(safe) }
            ok { put("path", safe) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun deleteFile(path: String): String = runCatching {
            val safe = PathSanitizer.sanitizeOrNull(path) ?: return bridgeError("Unsafe path")
            val fs = kotlinx.coroutines.runBlocking { graph.projects.fileSystemForAsync(projectId) }
            if (!fs.delete(safe, true)) return bridgeError("Could not delete $safe")
            emitEvent("file_event") { put("path", safe); put("kind", "DELETED") }
            ok { put("deleted", safe) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun analyzeFile(path: String): String = runCatching {
            val safe = PathSanitizer.sanitizeOrNull(path) ?: return bridgeError("Unsafe path")
            val fs = kotlinx.coroutines.runBlocking { graph.projects.fileSystemForAsync(projectId) }
            val content = fs.readFile(safe) ?: return bridgeError("File not found: $safe")
            val result = LightDiagnostics.analyze(safe, content)
            ok {
                put("path", safe)
                put("diagnostics", buildJsonArray {
                    result.diagnostics.forEach { d ->
                        add(buildJsonObject {
                            put("severity", d.severity.name)
                            put("line", d.line)
                            put("column", d.column)
                            put("message", d.message)
                            put("source", d.source)
                        })
                    }
                })
            }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun messages(): String = runCatching {
            val history = kotlinx.coroutines.runBlocking { graph.chat.observeForProject(projectId).first() }
            ok {
                put("messages", buildJsonArray {
                    history.forEach { m ->
                        add(buildJsonObject {
                            put("id", m.id)
                            put("role", m.role.name)
                            put("content", m.content)
                            m.error?.let { put("error", it) }
                            m.artifact?.let { a ->
                                put("artifact", buildJsonObject {
                                    put("title", a.title)
                                    put("id", a.id)
                                    put("actionCount", a.actions.size)
                                })
                            }
                            put("createdAt", m.createdAt)
                        })
                    }
                })
            }
        }.getOrElse { err(it) }

        // ------------------------------------------------------- agent loop

        @JavascriptInterface
        fun sendPrompt(text: String): String = runCatching {
            if (text.isBlank()) return bridgeError("Prompt is empty")
            val s = session
            if (s == null || !sessionReady) {
                return bridgeError(
                    if (!providerPresent) "No AI provider configured — add one in Settings first."
                    else "The provider could not be initialized (missing key or invalid configuration).",
                )
            }
            if (s.state.value.streaming) return bridgeError("A turn is already streaming")
            s.send(text)
            ok { put("started", true) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun cancelStream(): String {
            session?.cancel()
            return ok { put("cancelled", true) }
        }

        @JavascriptInterface
        fun revertChange(path: String): String = runCatching {
            val s = session ?: return bridgeError("No active session")
            val done = kotlinx.coroutines.runBlocking { s.revertChange(path) }
            if (!done) return bridgeError("Nothing to revert for $path")
            ok { put("reverted", path) }
        }.getOrElse { err(it) }

        // ------------------------------------------------------- git (GitLite)

        @JavascriptInterface
        fun git(op: String, arg: String): String = runCatching {
            val root = realRootSync() ?: return bridgeError("Local git requires an app-private workspace (this project uses SAF storage).")
            val existing = GitLite.open(root)
            // M-05: a read-only "status" must not silently create `.git` inside the user's folder.
            if (existing == null && op == "status") {
                return ok {
                    put("branch", ""); put("head", ""); put("clean", true)
                    put("staged", buildJsonArray { }); put("unstaged", buildJsonArray { }); put("untracked", buildJsonArray { })
                    put("initialized", false)
                }
            }
            val repo = existing ?: GitLite.init(root)
            when (op) {
                "status" -> {
                    val status = repo.status()
                    ok {
                        put("branch", status.branch)
                        put("head", status.headCommit ?: "")
                        put("clean", status.isClean)
                        put("staged", encodeEntries(status.staged))
                        put("unstaged", encodeEntries(status.unstaged))
                        put("untracked", buildJsonArray { status.untracked.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                "log" -> ok {
                    put("commits", buildJsonArray {
                        repo.log(50).forEach { c ->
                            add(buildJsonObject {
                                put("sha", c.sha)
                                put("message", c.message)
                                put("author", c.author)
                                put("timestamp", c.timestamp)
                            })
                        }
                    })
                }
                "branches" -> ok {
                    put("current", repo.currentBranch())
                    put("branches", buildJsonArray { repo.branches().forEach { add(JsonPrimitive(it)) } })
                    put("tags", buildJsonArray { repo.tags().forEach { add(JsonPrimitive(it)) } })
                }
                "add" -> {
                    val paths = json.decodeFromString<List<String>>(arg)
                    ok { put("staged", repo.add(paths)) }
                }
                "addAll" -> ok { put("staged", repo.addAll()) }
                "rm" -> {
                    val paths = json.decodeFromString<List<String>>(arg)
                    ok { put("removed", repo.rm(paths)) }
                }
                "commit" -> {
                    val message = arg.trim()
                    if (message.isBlank()) return bridgeError("Commit message is empty")
                    val commit = repo.commit(message) ?: return bridgeError("Nothing staged to commit")
                    ok { put("sha", commit.sha); put("message", commit.message) }
                }
                "branch" -> {
                    if (!repo.createBranch(arg)) return bridgeError("Could not create branch (exists or invalid name)")
                    ok { put("created", arg) }
                }
                "checkout" -> {
                    if (!repo.checkout(arg)) return bridgeError("Checkout failed (branch missing or dirty worktree)")
                    emitEvent("files_changed") { put("revision", -1) }
                    ok { put("branch", arg) }
                }
                "tag" -> {
                    if (!repo.createTag(arg)) return bridgeError("Could not create tag")
                    ok { put("tag", arg) }
                }
                "diff" -> {
                    val diff = repo.diff(arg) ?: return bridgeError("No diff available for $arg")
                    ok { put("path", arg); put("diff", diff) }
                }
                "fileAtHead" -> {
                    val content = repo.fileAtHead(arg) ?: return bridgeError("File is not present at HEAD")
                    ok { put("path", arg); put("content", content) }
                }
                else -> bridgeError("Unknown git op: $op")
            }
        }.getOrElse { err(it) }

        // ------------------------------------------------------- terminal (PTY session)

        @JavascriptInterface
        fun terminalStart(): String = runCatching {
            if (terminal != null) return ok { put("alreadyRunning", true) }
            val root = realRootSync() ?: return bridgeError("The terminal requires an app-private workspace (this project uses SAF storage).")
            val t = AndroidTerminalSession(root)
            val started = kotlinx.coroutines.runBlocking { t.start() }
            if (!started) return bridgeError("Could not start the shell process")
            terminal = t
            val terminalRef = t
            terminalJob = scope.launch {
                while (terminal === terminalRef) {
                    delay(400)
                    val text = kotlinx.coroutines.runBlocking { terminalRef.readAvailable(20_000) }
                    if (text.isNotEmpty()) emitEvent("terminal_output") { put("text", text) }
                }
            }
            ok { put("started", true) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun terminalSend(command: String): String = runCatching {
            val t = terminal ?: return bridgeError("Terminal is not running")
            val sent = kotlinx.coroutines.runBlocking { t.send(command) }
            if (!sent) return bridgeError("Command rejected (empty, too long, or outside the allowlist without approval)")
            ok { put("sent", command.take(200)) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun terminalStop(): String {
            terminalJob?.cancel(); terminalJob = null
            terminal?.close(); terminal = null
            return ok { put("stopped", true) }
        }

        // ------------------------------------------------------- skills

        @JavascriptInterface
        fun skills(): String = runCatching {
            val plugins = kotlinx.coroutines.runBlocking { graph.reliability.observePlugins().first() }
            ok {
                put("skills", buildJsonArray {
                    plugins.forEach { p ->
                        add(buildJsonObject {
                            put("id", p.id)
                            put("name", p.name)
                            put("description", p.description)
                            put("version", p.version)
                            put("source", p.source)
                            put("enabled", p.enabled)
                            put("builtIn", p.builtIn)
                        })
                    }
                })
            }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun setSkillEnabled(id: String, enabled: Boolean): String = runCatching {
            kotlinx.coroutines.runBlocking { graph.reliability.setPluginEnabled(id, enabled) }
            ok { put("id", id); put("enabled", enabled) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun removeSkill(id: String): String = runCatching {
            val removed = kotlinx.coroutines.runBlocking { graph.reliability.removePlugin(id) }
            if (!removed) return bridgeError("Built-in skills cannot be removed")
            ok { put("removed", id) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun installSkillFromGit(gitUrl: String): String = runCatching {
            if (!gitUrl.startsWith("https://github.com/")) return bridgeError("Only HTTPS GitHub repository URLs are supported.")
            scope.launch {
                val result = runCatching { SkillGitInstaller.installFromGit(graph.pluginInstaller, gitUrl) }
                result.fold(
                    onSuccess = { installed ->
                        emitEvent("skill_installed") {
                            put("id", installed.id)
                            put("version", installed.version)
                            put("name", installed.name)
                            installed.commit?.let { put("commit", it) }
                            installed.archiveSha256?.let { put("archiveSha256", it) }
                        }
                    },
                    onFailure = { emitEvent("skill_install_error") { put("message", it.message ?: "install failed") } },
                )
            }
            ok { put("accepted", true); put("url", gitUrl) }
        }.getOrElse { err(it) }

        // ------------------------------------------------------- MCP

        @JavascriptInterface
        fun mcpServers(): String = runCatching {
            ok {
                put("servers", buildJsonArray {
                    mcpConfigs().forEach { cfg ->
                        add(buildJsonObject {
                            put("id", cfg.id)
                            put("name", cfg.name)
                            put("url", cfg.url)
                            put("enabled", cfg.enabled)
                            put("hasKey", !cfg.apiKey.isNullOrBlank() || cfg.keyUndecryptable)
                            put("keyNeedsReentry", cfg.keyUndecryptable)
                        })
                    }
                })
            }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun mcpAdd(configJson: String): String = runCatching {
            val config = json.decodeFromString(McpServerConfig.serializer(), configJson)
            synchronized(mcpLock) {
                val current = mcpConfigs()
                val existing = current.firstOrNull { it.id == config.id }
                // H-05: a blank incoming key means "keep the existing
                // key" (an edit that only changes name/URL must not wipe
                // it), and an undecryptable stored key is preserved by
                // the save merge instead of being overwritten with null.
                // A NEW key that fails to encrypt fails the whole add
                // loudly — the old code stored null and returned ok.
                val toStore = when {
                    !config.apiKey.isNullOrBlank() ->
                        config.copy(apiKey = config.apiKey, keyUndecryptable = false)
                    existing?.keyUndecryptable == true ->
                        config.copy(apiKey = null, keyUndecryptable = true)
                    existing?.apiKey != null ->
                        config.copy(apiKey = existing.apiKey, keyUndecryptable = false)
                    else -> config.copy(apiKey = null, keyUndecryptable = false)
                }
                saveMcpConfigs(current.filter { it.id != config.id } + toStore)
                mcpClients.remove(config.id)
            }
            ok { put("id", config.id); put("name", config.name) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun mcpRemove(id: String): String = runCatching {
            synchronized(mcpLock) {
                saveMcpConfigs(mcpConfigs().filter { it.id != id })
                mcpClients.remove(id)
            }
            ok { put("removed", id) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun mcpTools(id: String): String = runCatching {
            val config = mcpConfigs().firstOrNull { it.id == id } ?: return bridgeError("Unknown MCP server: $id")
            if (config.keyUndecryptable) return bridgeError("The API key for this server could not be decrypted; re-enter it in settings.")
            val client = cachedMcpClient(config)
            kotlinx.coroutines.runBlocking {
                client.ensureInitialized().fold(
                    onSuccess = { },
                    onFailure = { return@runBlocking bridgeError("MCP initialize failed: ${it.message}") },
                )
                client.listTools().fold(
                    onSuccess = { tools ->
                        ok {
                            put("tools", buildJsonArray {
                                tools.forEach { t ->
                                    add(buildJsonObject {
                                        put("name", t.name)
                                        put("description", t.description)
                                        put("inputSchema", json.parseToJsonElement(t.inputSchemaJson))
                                    })
                                }
                            })
                        }
                    },
                    onFailure = { bridgeError("tools/list failed: ${it.message}") },
                )
            }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun mcpCall(id: String, tool: String, argsJson: String): String = runCatching {
            val config = mcpConfigs().firstOrNull { it.id == id } ?: return bridgeError("Unknown MCP server: $id")
            if (config.keyUndecryptable) return bridgeError("The API key for this server could not be decrypted; re-enter it in settings.")
            val client = cachedMcpClient(config)
            val args = (json.parseToJsonElement(argsJson) as? JsonObject) ?: JsonObject(emptyMap())
            kotlinx.coroutines.runBlocking {
                // M-12: the old code called tools/call on a fresh client
                // with no initialize — stateful servers rejected it.
                client.ensureInitialized().fold(
                    onSuccess = { },
                    onFailure = { return@runBlocking bridgeError("MCP initialize failed: ${it.message}") },
                )
                client.callTool(tool, args).fold(
                    onSuccess = { call -> ok { put("tool", tool); put("success", call.success); put("text", call.text) } },
                    onFailure = { bridgeError("tools/call failed: ${it.message}") },
                )
            }
        }.getOrElse { err(it) }

        // ------------------------------------------------------- in-app build

        @JavascriptInterface
        fun buildStatus(): String = runCatching {
            val status = graph.toolchain.status()
            ok {
                put("installed", status.installed)
                put("valid", status.valid)
                put("problems", buildJsonArray { status.problems.forEach { add(JsonPrimitive(it)) } })
                status.manifest?.let { m ->
                    put("jdk", m.jdkVersion); put("gradle", m.gradleVersion); put("abi", m.abi)
                }
                put("running", buildRunning.value)
            }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun buildStart(variant: String): String = runCatching {
            if (!running.compareAndSet(false, true)) return bridgeError("A build is already running")
            if (!graph.toolchain.isInstalled()) {
                running.set(false)
                return bridgeError("Android toolchain is not installed — import a bundle on the Build screen first.")
            }
            val safeVariant = variant.lowercase().takeIf { it == "release" } ?: "debug"
            scope.launch {
                try {
                    runBuild(safeVariant)
                } finally {
                    running.set(false)
                }
            }
            ok { put("started", safeVariant) }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun buildCancel(): String {
            graph.androidBuildEngine.requestCancellation()
            return ok { put("cancelRequested", true) }
        }

        // ------------------------------------------------------- GOAL 6: ZIP save/share

        /**
         * v4.1 (Nebula): primary ZIP export path, rewritten to the canonical
         * MediaStore.Downloads contract.
         *
         * JS calls this fire-and-forget with a base64 payload (JSZip
         * `generateAsync({type:"base64"})`); Kotlin decodes it, inserts a pending
         * row into MediaStore.Downloads (IS_PENDING=1), streams the bytes through
         * openOutputStream, then flips IS_PENDING=0 so the archive becomes visible
         * in the system Downloads collection — no storage permission, no blob:
         * URL, no URL.createObjectURL (both blocked by the preview WebView).
         *
         * Keeps the typed String JSON return so callers can optionally verify the
         * outcome synchronously; the authoritative UI feedback still arrives via
         * the `zip_saved` / `zip_error` events and the hosting screen snackbar.
         */
        @JavascriptInterface
        fun saveZip(base64: String, fileName: String): String = runCatching {
            if (base64.length > MAX_ZIP_BASE64_CHARS) return bridgeError("ZIP payload too large for the bridge")
            val safeName = sanitizeZipName(fileName)
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return bridgeError("ZIP payload is not valid base64")
            }
            if (bytes.isEmpty()) return bridgeError("ZIP payload is empty")
            if (bytes.size > MAX_ZIP_BYTES) return bridgeError("ZIP is larger than ${MAX_ZIP_BYTES / (1024 * 1024)} MB")
            // ZIP magic check: PK header signature (catches misrouted payloads early).
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
                return bridgeError("Payload is not a ZIP archive")
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveZipViaMediaStoreDownloads(bytes, safeName)
            } else {
                saveZipViaExternalFiles(bytes, safeName)
            }
            emitEvent("zip_saved") { put("fileName", safeName); put("uri", uri.toString()) }
            main.post { onZipSaved(safeName, uri.toString(), null) }
            ok { put("saved", true); put("fileName", safeName); put("uri", uri.toString()) }
        }.getOrElse { e ->
            android.util.Log.e("HarnessBridge", "saveZip failed", e)
            emitEvent("zip_error") { put("fileName", fileName); put("message", e.message ?: "save failed") }
            err(e)
        }

        @JavascriptInterface
        fun shareZip(base64: String, fileName: String): String = runCatching {
            if (base64.length > MAX_ZIP_BASE64_CHARS) return bridgeError("ZIP payload too large for the bridge")
            val safeName = sanitizeZipName(fileName)
            scope.launch {
                runCatching { decodeAndStoreZip(base64, safeName) }.fold(
                    onSuccess = { uri ->
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        emitEvent("zip_saved") { put("fileName", safeName); put("uri", uri.toString()) }
                        main.post { onZipSaved(safeName, uri.toString(), share) }
                    },
                    onFailure = { emitEvent("zip_error") { put("fileName", safeName); put("message", it.message ?: "share failed") } },
                )
            }
            ok { put("accepted", true); put("fileName", safeName) }
        }.getOrElse { err(it) }

        // ------------------------------------------------------- source export (GOAL 6 payload)

        @JavascriptInterface
        fun sourceManifest(): String = runCatching {
            context.assets.open(SOURCE_MANIFEST).bufferedReader().use { it.readText() }
        }.getOrElse { err(it) }

        @JavascriptInterface
        fun readSourceFile(path: String, offset: Long, length: Int): String = runCatching {
            if (!pathSourceSafe(path)) return bridgeError("Unsafe source path")
            if (length <= 0 || length > MAX_SOURCE_CHUNK) return bridgeError("Chunk size out of range")
            val safePath = path.removePrefix("source/")
            val bytes = context.assets.open("$SOURCE_DIR/$safePath").use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val n = input.skip(offset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                if (skipped < offset) return bridgeError("Offset beyond file")
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
            ok {
                put("path", path)
                put("offset", offset)
                put("length", bytes.size)
                put("encoding", "base64")
                put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }.getOrElse { err(it) }
    }

    // ------------------------------------------------------------------ build runner

    private suspend fun runBuild(variant: String) {
        buildRunning.value = true
        emitEvent("build_log") { put("line", "Build started ($variant)…") }
        var workspace: File? = null
        try {
            val dir = graph.projects.materializeForBuild(projectId)
            workspace = dir
            val request = com.anvex.core.android.build.AndroidBuildEngine.BuildRequest(
                projectDir = dir,
                variant = variant,
                useProjectWrapper = false,
            )
            val result = graph.androidBuildEngine.build(request) { line ->
                emitEvent("build_log") { put("line", line.take(2_000)) }
            }
            if (result.success && result.apk != null) {
                val artifact = graph.projects.persistBuildArtifact(projectId, variant, result.apk)
                emitEvent("build_end") {
                    put("success", true)
                    put("apk", artifact.file.name)
                    put("bytes", artifact.file.length())
                }
            } else {
                emitEvent("build_end") { put("success", false); put("error", result.error ?: "build failed") }
            }
        } catch (e: Exception) {
            emitEvent("build_end") { put("success", false); put("error", e.message ?: e::class.simpleName ?: "build failed") }
        } finally {
            workspace?.let { runCatching { graph.projects.cleanupBuildWorkspace(it) } }
            buildRunning.value = false
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private suspend fun runDiagnostics(path: String) {
        runCatching {
            val fs = graph.projects.fileSystemForAsync(projectId)
            val content = fs.readFile(path) ?: return
            if (content.toByteArray(Charsets.UTF_8).size > LightDiagnostics.MAX_FILE_BYTES) return
            val result = LightDiagnostics.analyze(path, content)
            emitEvent("diagnostics") {
                put("path", path)
                put("items", buildJsonArray {
                    result.diagnostics.forEach { d ->
                        add(buildJsonObject {
                            put("severity", d.severity.name)
                            put("line", d.line)
                            put("column", d.column)
                            put("message", d.message)
                        })
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------------ MCP storage

    // H-05: mcpAdd/mcpRemove are read-modify-write over one file and
    // are called from the JS thread; without a lock two overlapping
    // saves could each drop the other's server. Callers must hold it.
    private val mcpLock = Any()

    private fun mcpFile(): File = File(File(context.filesDir, "harness"), "mcp-servers.json")

    private fun mcpBackupFile(): File = File(File(context.filesDir, "harness"), "mcp-servers.json.bak")

    /** Stored view: keys exactly as on disk (encrypted blobs), never decrypted. */
    private fun rawMcpConfigs(): List<McpServerConfig> {
        for (file in listOf(mcpFile(), mcpBackupFile())) {
            val parsed = runCatching {
                if (!file.isFile) return@runCatching null
                json.decodeFromString(ListSerializerWrapper.serializer(), file.readText()).items
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return emptyList()
    }

    /**
     * Decrypted view for the UI and clients. H-05: a key that fails to
     * decrypt is flagged, NOT nulled — the old code nulled it here, so
     * the next save of any server permanently erased that key.
     */
    private fun mcpConfigs(): List<McpServerConfig> = rawMcpConfigs().map { cfg ->
        val stored = cfg.apiKey?.takeIf { it.isNotBlank() } ?: return@map cfg.copy(apiKey = null)
        val plain = runCatching { KeystoreCrypto.decrypt(stored) }.getOrNull()
        if (plain != null) cfg.copy(apiKey = plain) else cfg.copy(apiKey = null, keyUndecryptable = true)
    }

    @Serializable
    private data class ListSerializerWrapper(val items: List<McpServerConfig> = emptyList())

    /**
     * H-05: callers must hold [mcpLock]. Publishes atomically (tmp +
     * fsync + rename, same pattern as RealProjectFileSystem) and keeps
     * the previous good file as `.bak`, so a corrupt primary can no
     * longer wipe the whole list on the next save. A failed encryption
     * throws — the caller reports it instead of storing a null key.
     */
    private fun saveMcpConfigs(configs: List<McpServerConfig>) {
        val file = mcpFile()
        file.parentFile?.mkdirs()
        val storedMap = rawMcpConfigs().associate { it.id to it.apiKey }
        val toStore = mergeMcpConfigsForSave(configs, storedMap) { plain ->
            runCatching { KeystoreCrypto.encrypt(plain) }.getOrNull()
        }
        val payload = json.encodeToString(ListSerializerWrapper.serializer(), ListSerializerWrapper(toStore))
            .toByteArray(Charsets.UTF_8)
        // Previous good copy first, so a crash mid-publish still leaves
        // a recoverable file behind — but only when the primary itself
        // parses: backing up a corrupt primary would destroy the good
        // .bak that rawMcpConfigs falls back to.
        val primaryOk = file.isFile && runCatching {
            json.decodeFromString(ListSerializerWrapper.serializer(), file.readText())
        }.isSuccess
        if (primaryOk) writeAtomically(mcpBackupFile(), file.readBytes())
        writeAtomically(file, payload)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${java.util.UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            require(tmp.renameTo(target)) { "Could not atomically publish ${target.name}" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    // M-12: one client per server, kept across calls. The old code built a
    // fresh client for every call and mcpCall never ran initialize, so
    // stateful (session-id) servers rejected tools/call outright.
    // Invalidated on mcpAdd/mcpRemove, when the URL or key may change.
    private val mcpClients = mutableMapOf<String, McpClient>()

    private fun cachedMcpClient(config: McpServerConfig): McpClient = synchronized(mcpLock) {
        mcpClients.getOrPut(config.id) { McpClient(config.url, config.apiKey, McpClient.defaultClient()) }
    }

    // ------------------------------------------------------------------ GOAL 6 storage

    /**
     * Decodes the base64 ZIP and writes it to public Downloads (share path):
     * API 29+ via MediaStore.Downloads (no permission needed), API 26-28 via the
     * app's external files dir exposed through FileProvider. Returns the Uri.
     */
    private fun decodeAndStoreZip(base64: String, fileName: String): Uri {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        if (bytes.isEmpty()) error("ZIP payload is empty")
        if (bytes.size > MAX_ZIP_BYTES) error("ZIP is larger than ${MAX_ZIP_BYTES / (1024 * 1024)} MB")
        // ZIP magic check: PK header signature.
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            error("Payload is not a ZIP archive")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveZipViaMediaStoreDownloads(bytes, fileName)
        } else {
            saveZipViaExternalFiles(bytes, fileName)
        }
    }

    /**
     * API 29+: MediaStore.Downloads — no storage permission required.
     *
     * Canonical IS_PENDING publish flow: insert with IS_PENDING=1 (the row is
     * invisible to other apps until completed), write the bytes, then update
     * IS_PENDING=0. On any write failure the pending row is deleted so no empty
     * husk lingers in Downloads.
     */
    // v4.10.1: MediaStore.Downloads is API 29+; both call sites already
    // guard with SDK_INT >= Q — the annotation is what lets lint verify
    // that contract instead of flagging the body (NewApi).
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveZipViaMediaStoreDownloads(bytes: ByteArray, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore rejected the download")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: error("Could not open the MediaStore output stream")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    /** API 26-28: app-scoped external dir exposed through FileProvider. */
    private fun saveZipViaExternalFiles(bytes: ByteArray, fileName: String): Uri {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Anvex")
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
    }

    private fun sanitizeZipName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.')
        val base = cleaned.ifBlank { "Anvex-export" }.take(76)
        return if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
    }

    // ------------------------------------------------------------------ misc helpers

    private fun realRootSync(): File? = kotlinx.coroutines.runBlocking {
        val fs = graph.projects.fileSystemForAsync(projectId)
        (fs as? com.anvex.core.android.fs.RealProjectFileSystem)?.rootDirectory()
    }

    private fun encodeEntries(entries: List<GitLite.Repository.Status.Entry>): JsonElement = buildJsonArray {
        entries.forEach { e ->
            add(buildJsonObject { put("path", e.path); put("change", e.change) })
        }
    }

    private fun pathSourceSafe(path: String): Boolean =
        path.startsWith("source/") &&
            path.length < 300 &&
            !path.contains("..") &&
            path.removePrefix("source/").split('/').none { it.isBlank() }

    private fun emitEvent(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val payload = buildJsonObject(build)
        pushToJs("""{"type":"$type","payload":${payload.toString()}}""")
    }

    private fun emitRaw(type: String, payloadJson: String) {
        pushToJs("""{"type":"$type","payload":$payloadJson}""")
    }

    private fun pushToJs(jsonText: String) {
        val escaped = jsonText.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        val script = "window.__harnessEvent && window.__harnessEvent('$escaped');"
        val view = webView ?: return
        main.post { runCatching { view.evaluateJavascript(script, null) } }
    }

    // ------------------------------------------------------------------ response helpers

    private fun ok(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject { put("ok", true); build() }.toString()

    private fun bridgeError(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    private fun err(t: Throwable): String =
        bridgeError(t.message ?: t::class.simpleName ?: "bridge error")

    companion object {
        const val SOURCE_DIR = "source"
        const val SOURCE_MANIFEST = "source/source-manifest.json"
        const val MAX_ZIP_BASE64_CHARS: Int = 24_000_000 // ~18 MB decoded
        const val MAX_ZIP_BYTES: Int = 20 * 1024 * 1024
        const val MAX_BRIDGE_WRITE_BYTES: Int = 2 * 1024 * 1024
        const val MAX_SOURCE_CHUNK: Int = 512 * 1024
    }
}
