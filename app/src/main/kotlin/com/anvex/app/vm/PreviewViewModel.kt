package com.anvex.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.android.AppGraph
import com.anvex.core.android.server.StaticPreviewServer
import com.anvex.core.engine.PreviewHarness
import com.anvex.core.model.Project
import com.anvex.core.model.ProjectType
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Preview runtime lifecycle (brief §15): starts the localhost-only server
 * bound to the project filesystem, exposes the entry URL, and collects
 * console/error events forwarded from the WebView bridge.
 */
class PreviewViewModel(
    val projectId: String,
) : ViewModel() {

    data class ConsoleLine(val level: String, val text: String, val ts: Long = System.currentTimeMillis())

    private val graph = AppGraph.get()

    val project: StateFlow<Project?> = graph.projects.observeAll()
        .map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url

    /** Server root (base for per-file preview URLs), exposed for quick-open. */
    private val _rootUrl = MutableStateFlow<String?>(null)
    val rootUrl: StateFlow<String?> = _rootUrl

    /**
     * Previewable files for the quick-open chip row (architect item 3): entry HTML
     * files plus every mermaid/SVG diagram in the project, bounded to keep the
     * row readable.
     */
    private val _previewFiles = MutableStateFlow<List<String>>(emptyList())
    val previewFiles: StateFlow<List<String>> = _previewFiles

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    val refreshTick = MutableStateFlow(0)

    private var startJob: kotlinx.coroutines.Job? = null

    private val _pageErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pageErrors: SharedFlow<String> = _pageErrors

    /** Start (or restart after project-type change) the preview server. */
    fun start(type: ProjectType) {
        stop()
        startJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val fs = runCatching { graph.projects.fileSystemForAsync(projectId) }.getOrNull() ?: return@launch
            val server = StaticPreviewServer(fs, type, graph.runtimeAssetLoader)
            server.start()
            if (!isActive) {
                server.stop()
                return@launch
            }
            graph.previewServer?.stop()
            graph.previewServer = server
            _rootUrl.value = server.rootUrl
            _url.value = server.entryUrl
            loadPreviewFiles(fs)
        }
    }

    /** Quick-open a specific project file (e.g. a .mmd diagram) in the preview WebView. */
    fun openFile(path: String) {
        val root = _rootUrl.value ?: return
        val safe = path.trim().trimStart('/')
        if (safe.isEmpty()) return
        _url.value = root + android.net.Uri.encode(safe, "/")
    }

    /** Reloads the quick-open file list (called with the server start and on refresh). */
    private fun loadPreviewFiles(fs: com.anvex.core.engine.ProjectFileSystem) {
        val files = runCatching { fs.walk() }.getOrDefault(emptyList())
        val html = files.filter { it.lowercase().endsWith(".html") }
        val diagrams = files.filter { PreviewHarness.isDiagramPath(it) }
        val ordered = buildList {
            addAll(html.filter { it.equals("index.html", ignoreCase = true) })
            addAll(html.filterNot { it.equals("index.html", ignoreCase = true) }.take(4))
            addAll(diagrams)
        }.distinct().take(12)
        _previewFiles.value = ordered
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        graph.previewServer?.stop()
        graph.previewServer = null
        _url.value = null
        _rootUrl.value = null
    }

    fun reload() {
        refreshTick.value++
        // The quick-open list follows the filesystem (files may have been written
        // by the chat agent since the last refresh).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val fs = runCatching { graph.projects.fileSystemForAsync(projectId) }.getOrNull() ?: return@launch
            loadPreviewFiles(fs)
        }
    }

    fun onBridgeMessage(json: String) {
        // {"type":"console"|"error"|"ready","payload":{...}}
        try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
                as? kotlinx.serialization.json.JsonObject ?: return
            when (obj["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }) {
                "console" -> {
                    val payload = obj["payload"] as? kotlinx.serialization.json.JsonObject ?: return
                    val level = (payload["level"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "log"
                    val text = (payload["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    appendLine(ConsoleLine(level, text))
                }
                "error" -> {
                    val payload = obj["payload"] as? kotlinx.serialization.json.JsonObject ?: return
                    val msg = (payload["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: "Unknown preview error"
                    appendLine(ConsoleLine("error", msg))
                    _lastError.value = msg
                    _pageErrors.tryEmit(msg)
                }
                "ready" -> appendLine(ConsoleLine("info", "Preview ready"))
            }
        } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e);
        }
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    private fun appendLine(line: ConsoleLine) {
        _console.value = (_console.value + line).takeLast(300) // bounded ring buffer
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
