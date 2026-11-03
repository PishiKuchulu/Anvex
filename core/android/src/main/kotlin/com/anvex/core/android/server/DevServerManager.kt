package com.anvex.core.android.server

import android.content.Context
import androidx.core.content.ContextCompat
import com.anvex.core.android.runtime.AndroidRuntimeGateway
import com.anvex.core.android.runtime.server.NodeServerService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Dev-server lifecycle as the Preview UI renders it (F5a, P5). */
sealed interface DevServerState {
    data object Idle : DevServerState
    data class Starting(val projectId: String, val lines: List<String>) : DevServerState
    data class Running(
        val projectId: String,
        val url: String,
        val mode: String,
        val lines: List<String>,
    ) : DevServerState

    data class Failed(val projectId: String, val message: String, val lines: List<String>) : DevServerState
}

/**
 * Drives the persistent Node host in the ":devserver" process
 * ([NodeServerService] + assets/node-runtime/devserver-host.js) over its
 * token-guarded loopback control API.
 *
 * Rules from the plan: servers are loopback-only; the manager never
 * kills foreign processes (a busy control port surfaces as an error);
 * a running server auto-stops after [AUTO_STOP_MS] without a connected
 * preview ([notePreviewAlive] is the heartbeat).
 */
class DevServerManager(
    private val context: Context,
    private val gateway: AndroidRuntimeGateway,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DevServerState>(DevServerState.Idle)
    val state: StateFlow<DevServerState> = _state

    @Volatile
    private var lastAliveAt: Long = 0L
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null

    /** Called by the Preview while the dev origin is on screen. */
    fun notePreviewAlive() {
        lastAliveAt = System.currentTimeMillis()
    }

    suspend fun start(projectId: String, command: String? = null) {
        stopInternal(keepService = true)
        val root = gateway.realRootDirectory(projectId)
        if (root == null) {
            _state.value = DevServerState.Failed(
                projectId,
                "Dev servers need a project stored on the device filesystem (not a cloud/SAF folder).",
                emptyList(),
            )
            return
        }
        val script = command?.takeIf { it.isNotBlank() } ?: detectScript(root)
        if (script == null) {
            _state.value = DevServerState.Failed(
                projectId,
                "No dev script found: add a \"dev\" or \"start\" script to package.json, or a server.js.",
                emptyList(),
            )
            return
        }
        _state.value = DevServerState.Starting(projectId, emptyList())
        lastAliveAt = System.currentTimeMillis()
        val ensured = ensureHost()
        if (!ensured) {
            _state.value = DevServerState.Failed(
                projectId,
                "The dev-server host could not be started (Node runtime unavailable or the control port is busy).",
                emptyList(),
            )
            return
        }
        val response = apiPost(
            "/start",
            JSONObject()
                .put("id", SERVER_ID)
                .put("cwd", root.absolutePath)
                .put("script", script)
                .put(
                    "env",
                    JSONObject()
                        .put("HOME", context.filesDir.absolutePath)
                        .put("npm_config_cache", File(runtimeDir(), ".npm-cache").absolutePath),
                ),
        )
        val error = response?.optString("error")?.takeIf { it.isNotEmpty() }
        if (response == null || error != null) {
            _state.value = DevServerState.Failed(
                projectId,
                error ?: "The dev-server host did not accept the start request.",
                emptyList(),
            )
            return
        }
        pollLoop(projectId)
        startWatchdog()
    }

    suspend fun stop() {
        stopInternal(keepService = false)
        _state.value = DevServerState.Idle
    }

    private suspend fun stopInternal(keepService: Boolean) {
        pollJob?.cancel()
        pollJob = null
        if (!keepService) watchdogJob?.cancel()
        runCatching { apiPost("/stop", JSONObject().put("id", SERVER_ID)) }
        if (!keepService) {
            runCatching { context.stopService(NodeServerService.intent(context, "", "")) }
        }
    }

    /** The package.json dev/start script text, or a plain server.js entry. */
    private fun detectScript(root: File): String? {
        val pkg = File(root, "package.json")
        if (pkg.isFile) {
            val scripts = runCatching {
                JSONObject(pkg.readText()).optJSONObject("scripts")
            }.getOrNull()
            val dev = scripts?.optString("dev")?.takeIf { it.isNotBlank() }
            val start = scripts?.optString("start")?.takeIf { it.isNotBlank() }
            when {
                dev != null -> return "npm run dev"
                start != null -> return "npm start"
            }
        }
        if (File(root, "server.js").isFile) return "node server.js"
        if (File(root, "index.js").isFile) return "node index.js"
        return null
    }

    private fun pollLoop(projectId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var healthChecked = false
            while (true) {
                delay(1_000)
                val status = apiGet("/status?id=$SERVER_ID") ?: continue
                if (status.optBoolean("gone", false)) {
                    _state.value = DevServerState.Idle
                    return@launch
                }
                val lines = buildList {
                    val arr = status.optJSONArray("lines")
                    if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
                }
                val running = status.optBoolean("running", false)
                val url = status.optString("url").takeIf { it.isNotEmpty() }
                val mode = status.optString("mode")
                when {
                    !running -> {
                        _state.value = DevServerState.Failed(
                            projectId,
                            "The dev server exited (code ${status.optInt("exitCode", -1)}).",
                            lines,
                        )
                        return@launch
                    }
                    url != null -> {
                        if (!healthChecked) {
                            healthChecked = healthCheck(url)
                        }
                        _state.value = DevServerState.Running(projectId, url, mode, lines)
                    }
                    else -> _state.value = DevServerState.Starting(projectId, lines)
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(60_000)
                val active = _state.value is DevServerState.Running ||
                    _state.value is DevServerState.Starting
                if (active && System.currentTimeMillis() - lastAliveAt > AUTO_STOP_MS) {
                    stop()
                    return@launch
                }
                if (!active) return@launch
            }
        }
    }

    /** Any HTTP response at all counts as "up" for a dev server. */
    private fun healthCheck(url: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        conn.instanceFollowRedirects = false
        conn.responseCode > 0
    }.getOrDefault(false)

    // ---- host lifecycle + control API --------------------------------

    private fun runtimeDir(): File = File(context.filesDir, "node-runtime")

    private fun tokenFile(): File {
        val dir = File(context.filesDir, "devserver").apply { mkdirs() }
        val file = File(dir, "token.txt")
        if (!file.isFile) file.writeText(UUID.randomUUID().toString())
        return file
    }

    private suspend fun ensureHost(): Boolean {
        if (apiGet("/ping")?.optBoolean("ok") == true) return true
        val started = withContext(Dispatchers.IO) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    NodeServerService.intent(
                        context,
                        tokenFile().absolutePath,
                        runtimeDir().absolutePath,
                    ),
                )
            }.isSuccess
        }
        if (!started) return false
        repeat(20) {
            delay(500)
            if (apiGet("/ping")?.optBoolean("ok") == true) return true
        }
        return false
    }

    private suspend fun apiGet(path: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("http://127.0.0.1:$CONTROL_PORT$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.setRequestProperty("x-anvex-token", tokenFile().readText().trim())
            if (conn.responseCode != 200) return@runCatching null
            JSONObject(conn.inputStream.bufferedReader().readText())
        }.getOrNull()
    }

    private suspend fun apiPost(path: String, body: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL("http://127.0.0.1:$CONTROL_PORT$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-anvex-token", tokenFile().readText().trim())
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) return@runCatching null
                JSONObject(conn.inputStream.bufferedReader().readText())
            }.getOrNull()
        }

    companion object {
        const val CONTROL_PORT = 17321
        private const val SERVER_ID = "dev"
        const val AUTO_STOP_MS = 30 * 60_000L
    }
}
