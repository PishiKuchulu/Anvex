package com.anvex.core.android.runtime.lsp

import android.content.Context
import androidx.core.content.ContextCompat
import com.anvex.core.android.runtime.AndroidRuntimeGateway
import com.anvex.core.engine.lsp.LspFraming
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** One published diagnostic (0-based positions, LSP severity 1..4). */
data class LspDiagnostic(
    val line: Int,
    val character: Int,
    val endLine: Int,
    val endCharacter: Int,
    val severity: Int,
    val message: String,
    val source: String?,
)

/**
 * P9 (F2c): the LSP client. One session per manager instance: a
 * project-local language server (from the project's own node_modules)
 * runs in the :lsp host process; this class speaks JSON-RPC to it
 * through the host's poll/send bridge, framed by [LspFraming].
 *
 * Supported servers (project-local installs only — nothing is
 * downloaded): typescript-language-server (.ts/.tsx/.js),
 * vscode-json-languageserver (.json), pyright (.py). When no server
 * is installed, or the host cannot spawn, [startFor] returns an honest
 * reason and the Tree-sitter features remain the intelligence layer.
 */
class LspManager(
    private val context: Context,
    private val gateway: AndroidRuntimeGateway,
) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** uri → diagnostics, updated by publishDiagnostics notifications. */
    val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())

    private var session: Session? = null
    private var lastActivityMs = 0L

    private inner class Session(val id: String, val root: File) {
        val decoder = LspFraming.Decoder()
        val pending = HashMap<Int, CompletableDeferred<JSONObject>>()
        val sendMutex = Mutex()
        var nextId = 1
        var lastSeq = -1
        var initialized = false
        val versions = HashMap<String, Int>()
    }

    /** Starts a session for [language]; returns null on success or a reason. */
    suspend fun startFor(projectId: String, language: String): String? {
        if (session?.initialized == true) return null
        val root = gateway.realRootDirectory(projectId)
            ?: return "Language servers need a real folder project."
        val argv = serverArgv(root, language)
            ?: return "No language server installed in this project (node_modules)."
        if (!ensureHost()) return "The language-server host did not start."
        val created = Session(id = "lsp", root = root)
        session = created
        val startBody = JSONObject()
            .put("id", created.id)
            .put("argv", JSONArray(argv))
            .put("cwd", root.absolutePath)
        val started = control("POST", "/start", startBody)
        if (started?.optBoolean("ok") != true) {
            session = null
            return started?.optString("error") ?: "The language server did not start."
        }
        scope.launch { pollLoop(created) }
        val params = JSONObject()
            .put("processId", JSONObject.NULL)
            .put("rootUri", fileUri(root))
            .put(
                "capabilities", JSONObject().put(
                    "textDocument", JSONObject()
                        .put("publishDiagnostics", JSONObject())
                        .put("hover", JSONObject().put("contentFormat", JSONArray(listOf("plaintext", "markdown"))))
                        .put("synchronization", JSONObject().put("didSave", true)),
                ).put("workspace", JSONObject().put("configuration", true)),
            )
            .put(
                "workspaceFolders",
                JSONArray().put(JSONObject().put("uri", fileUri(root)).put("name", root.name)),
            )
        val reply = request(created, "initialize", params)
        if (reply == null) {
            session = null
            return "The language server did not answer initialize."
        }
        notify(created, "initialized", JSONObject())
        created.initialized = true
        lastActivityMs = System.currentTimeMillis()
        scope.launch { idleWatchdog(created) }
        return null
    }

    suspend fun openDocument(path: String, languageId: String, text: String) {
        val s = session?.takeIf { it.initialized } ?: return
        val uri = fileUri(File(s.root, path))
        s.versions[uri] = 1
        notify(
            s, "textDocument/didOpen",
            JSONObject().put(
                "textDocument", JSONObject()
                    .put("uri", uri).put("languageId", languageId)
                    .put("version", 1).put("text", text),
            ),
        )
        touch()
    }

    suspend fun changeDocument(path: String, text: String) {
        val s = session?.takeIf { it.initialized } ?: return
        val uri = fileUri(File(s.root, path))
        val version = (s.versions[uri] ?: 1) + 1
        s.versions[uri] = version
        notify(
            s, "textDocument/didChange",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uri).put("version", version))
                .put("contentChanges", JSONArray().put(JSONObject().put("text", text))),
        )
        touch()
    }

    /** Hover text at a 0-based position, or null. */
    suspend fun hover(path: String, line: Int, character: Int): String? {
        val s = session?.takeIf { it.initialized } ?: return null
        val uri = fileUri(File(s.root, path))
        val reply = request(
            s, "textDocument/hover",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uri))
                .put(
                    "position",
                    JSONObject().put("line", line).put("character", character),
                ),
        ) ?: return null
        val result = reply.optJSONObject("result") ?: return null
        val contents = result.opt("contents") ?: return null
        touch()
        return when (contents) {
            is String -> contents
            is JSONObject -> contents.optString("value").ifBlank { null }
            is JSONArray -> buildString {
                for (i in 0 until contents.length()) {
                    val part = contents.optJSONObject(i) ?: continue
                    val value = part.optString("value")
                    if (value.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(value)
                    }
                }
            }.ifBlank { null }
            else -> null
        }
    }

    /** Stops the session and the host service (also the idle path). */
    fun stop() {
        val s = session ?: return
        session = null
        scope.launch {
            runCatching { request(s, "shutdown", JSONObject.NULL) }
            runCatching { notify(s, "exit", JSONObject.NULL) }
            runCatching { control("POST", "/stop", JSONObject().put("id", s.id)) }
            runCatching { appContext.stopService(LspService.intent(appContext, "", "")) }
        }
    }

    fun isReady(): Boolean = session?.initialized == true

    // ------------------------------------------------------------ transport

    private suspend fun idleWatchdog(s: Session) {
        while (session === s) {
            delay(15_000)
            if (System.currentTimeMillis() - lastActivityMs > IDLE_STOP_MS) {
                stop()
                return
            }
        }
    }

    private fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    private suspend fun pollLoop(s: Session) {
        while (session === s) {
            val reply = runCatching {
                control("GET", "/poll?id=${s.id}&after=${s.lastSeq}", null)
            }.getOrNull()
            if (reply != null) {
                s.lastSeq = reply.optInt("last", s.lastSeq)
                val chunks = reply.optJSONArray("chunks")
                if (chunks != null) {
                    for (i in 0 until chunks.length()) {
                        val dataB64 = chunks.optJSONObject(i)?.optString("dataB64") ?: continue
                        val bytes = runCatching { Base64.getDecoder().decode(dataB64) }
                            .getOrNull() ?: continue
                        for (payload in s.decoder.feed(bytes)) handleMessage(s, payload)
                    }
                }
                if (!reply.optBoolean("running", true) && session === s) {
                    session = null
                }
            }
            delay(250)
        }
    }

    private fun handleMessage(s: Session, payload: String) {
        val message = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val id = message.optInt("id", -1)
        val method = message.optString("method", "")
        when {
            id >= 0 && message.has("result") || id >= 0 && message.has("error") -> {
                s.pending.remove(id)?.complete(message)
            }
            method == "textDocument/publishDiagnostics" -> {
                val params = message.optJSONObject("params") ?: return
                val uri = params.optString("uri")
                val list = ArrayList<LspDiagnostic>()
                val array = params.optJSONArray("diagnostics")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val d = array.optJSONObject(i) ?: continue
                        val range = d.optJSONObject("range") ?: continue
                        val start = range.optJSONObject("start") ?: continue
                        val end = range.optJSONObject("end") ?: continue
                        list += LspDiagnostic(
                            line = start.optInt("line"),
                            character = start.optInt("character"),
                            endLine = end.optInt("line"),
                            endCharacter = end.optInt("character"),
                            severity = d.optInt("severity", 1),
                            message = d.optString("message"),
                            source = d.optString("source").ifBlank { null },
                        )
                    }
                }
                diagnostics.value = diagnostics.value + (uri to list)
            }
            method.isNotEmpty() && id >= 0 -> {
                // Server-to-client request: answer so the server never stalls.
                val result: Any = if (method == "workspace/configuration") {
                    val items = message.optJSONObject("params")?.optJSONArray("items")
                    val answers = JSONArray()
                    repeat(items?.length() ?: 0) { answers.put(JSONObject()) }
                    answers
                } else {
                    JSONObject.NULL
                }
                scope.launch {
                    sendRaw(
                        s, JSONObject()
                            .put("jsonrpc", "2.0").put("id", id).put("result", result)
                            .toString(),
                    )
                }
            }
        }
    }

    private suspend fun request(s: Session, method: String, params: Any): JSONObject? {
        val id = s.nextId++
        val deferred = CompletableDeferred<JSONObject>()
        s.pending[id] = deferred
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        sendRaw(s, body.toString())
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
            .also { s.pending.remove(id) }
    }

    private suspend fun notify(s: Session, method: String, params: Any) {
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("method", method).put("params", params)
        sendRaw(s, body.toString())
    }

    private suspend fun sendRaw(s: Session, json: String) {
        val framed = LspFraming.encode(json)
        val body = JSONObject()
            .put("id", s.id)
            .put("dataB64", Base64.getEncoder().encodeToString(framed))
        s.sendMutex.withLock {
            runCatching { control("POST", "/send", body) }
        }
    }

    // ------------------------------------------------------------ host control

    private fun tokenFile(): File {
        val dir = File(appContext.filesDir, "lsp").apply { mkdirs() }
        val file = File(dir, "token.txt")
        if (!file.exists()) file.writeText(UUID.randomUUID().toString())
        return file
    }

    private fun ping(): JSONObject? = control("GET", "/ping", null)

    private fun ensureHost(): Boolean {
        if (ping()?.optBoolean("ok") == true) return true
        val runtimeDir = File(appContext.filesDir, "node-runtime")
        val intent = LspService.intent(
            appContext, tokenFile().absolutePath, runtimeDir.absolutePath,
        )
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
        var attempts = 0
        while (attempts < 24) {
            Thread.sleep(500)
            if (ping()?.optBoolean("ok") == true) return true
            attempts++
        }
        return false
    }

    private fun control(method: String, path: String, body: JSONObject?): JSONObject? {
        val builder = Request.Builder()
            .url("http://127.0.0.1:$CONTROL_PORT$path")
            .header("x-anvex-token", tokenFile().readText().trim())
        when (method) {
            "POST" -> builder.post(
                (body?.toString() ?: "{}").toRequestBody(JSON_MEDIA),
            )
            else -> builder.get()
        }
        return runCatching {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) null else JSONObject(text)
            }
        }.getOrNull()
    }

    companion object {
        const val CONTROL_PORT = 17322
        const val IDLE_STOP_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
        private val JSON_MEDIA = "application/json".toMediaType()

        fun fileUri(file: File): String =
            "file://" + file.absolutePath.replace(" ", "%20")

        /** The project-local server command for [language], or null. */
        fun serverArgv(root: File, language: String): List<String>? {
            val bin = File(root, "node_modules/.bin")
            return when (language) {
                "typescript", "tsx", "javascript" -> {
                    val entry = File(bin, "typescript-language-server")
                    if (entry.exists()) listOf(entry.absolutePath, "--stdio") else null
                }
                "json" -> {
                    val entry = File(bin, "vscode-json-languageserver")
                    if (entry.exists()) listOf(entry.absolutePath, "--stdio") else null
                }
                "python" -> {
                    val entry = File(bin, "pyright-langserver")
                    if (entry.exists()) listOf(entry.absolutePath, "--stdio") else null
                }
                else -> null
            }
        }
    }
}
