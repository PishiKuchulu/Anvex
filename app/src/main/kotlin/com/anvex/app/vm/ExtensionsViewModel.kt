package com.anvex.app.vm

import com.anvex.core.ai.mcp.McpToolInfo
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.ai.mcp.McpServerConfig
import com.anvex.core.android.AppGraph
import com.anvex.core.android.db.PluginEntity
import com.anvex.core.android.harness.SkillGitInstaller
import com.anvex.core.android.mcp.McpServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Phase 6: user-facing management of skills (declarative packages) and MCP servers. */
class ExtensionsViewModel : ViewModel() {

    private val graph = AppGraph.get()
    private val store = McpServerStore(graph.context)

    val skills: StateFlow<List<PluginEntity>> = graph.reliability.observePlugins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    init {
        // Reading and decrypting the server file is I/O; keep it off the main thread.
        viewModelScope.launch(Dispatchers.IO) { _servers.value = store.list() }
    }

    private val _tools = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tools: StateFlow<Map<String, List<String>>> = _tools.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch { graph.reliability.setPluginEnabled(id, enabled) }
    }

    fun removeSkill(id: String) {
        viewModelScope.launch {
            val removed = graph.reliability.removePlugin(id)
            _status.value = if (removed) "Skill removed." else "Built-in skills cannot be removed."
        }
    }

    /** Installs a skill package (ZIP with plugin.json). The file is copied to cache first. */
    fun installSkillZip(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(graph.context.cacheDir, "skill-import").apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.zip")
                    graph.context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                    } ?: error("Cannot read the selected file.")
                    try {
                        graph.pluginInstaller.installArchive(target)
                    } finally {
                        target.delete()
                    }
                }
            }
            _status.value = result.fold(
                onSuccess = { "Installed ${it.id} ${it.version}." },
                onFailure = { "Install failed: ${it.message?.take(200)}" },
            )
            _busy.value = false
        }
    }

    /** Installs a skill from a public GitHub repository (HTTPS only; see SkillGitInstaller). */
    fun installSkillFromGitHub(url: String) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) { SkillGitInstaller.installFromGit(graph.pluginInstaller, url.trim()) }
            }
            _status.value = result.fold(
                onSuccess = { "Installed ${it.name} (${it.id} ${it.version})." },
                onFailure = { "GitHub install failed: ${it.message?.take(200)}" },
            )
            _busy.value = false
        }
    }

    fun addServer(name: String, url: String, token: String) {
        runCatching {
            McpServerConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { url.trim() },
                url = url.trim(),
                apiKey = token.trim().ifBlank { null },
            )
        }.onSuccess { config ->
            store.add(config)
            _servers.value = store.list()
            _status.value = "Server added. Test the connection to list its tools."
        }.onFailure {
            _status.value = it.message ?: "Invalid server settings."
        }
    }

    /** Lets chats call this server's tools. Only meaningful after a successful connection test. */
    fun setAllowCalls(server: McpServerConfig, allow: Boolean) {
        store.add(server.copy(allowCalls = allow))
        _servers.value = store.list()
    }

    fun removeServer(id: String) {
        store.remove(id)
        _servers.value = store.list()
        _tools.value = _tools.value - id
    }

    fun testServer(config: McpServerConfig) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = store.clientFor(config)
                    client.initialize().getOrThrow()
                    client.listTools().getOrThrow()
                }
            }
            result.fold(
                onSuccess = { list ->
                    store.add(config.copy(tools = list.map { McpToolInfo(it.name, it.description.take(200)) }))
                    _servers.value = store.list()
                    _tools.value = _tools.value + (config.id to list.map { it.name })
                    _status.value = "Connected to ${config.name}: ${list.size} tool(s)."
                },
                onFailure = { _status.value = "Connection failed: ${it.message?.take(200)}" },
            )
            _busy.value = false
        }
    }
}
