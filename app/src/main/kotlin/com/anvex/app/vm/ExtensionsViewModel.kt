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
import kotlinx.coroutines.flow.first
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

    // Per-operation busy tracking (audit 2026-11-03): one shared flag let
    // overlapping operations clear each other's spinner — the first to
    // finish set busy=false while the other still ran. `busy` stays the
    // public "any operation running" signal for the screen.
    private val activeOps = HashSet<String>()

    private fun beginOp(op: String) {
        activeOps += op
        _busy.value = true
    }

    private fun endOp(op: String) {
        activeOps -= op
        _busy.value = activeOps.isNotEmpty()
    }

    fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch {
            // Wiring gap 3 (audit 2026-11-03): toggles route through
            // PluginMarketplace (same PluginDao the list observes), so
            // enabling pulls declared dependencies in and disabling a
            // depended-upon plugin disables its dependents instead of
            // silently breaking them. Failures surface in the status
            // line; the observed list reflects the real flags either way.
            runCatching {
                val affected = if (enabled) {
                    graph.pluginMarketplace.enableWithDependencies(id)
                } else {
                    graph.pluginMarketplace.disableWithDependents(id)
                }
                // Round 2 (N6): the returned plan used to be discarded —
                // enabling silently switched on the whole dependency
                // closure (whose instructions enter every chat prompt).
                // Name the extra plugins in the status line.
                val names = graph.reliability.observePlugins().first()
                    .associate { it.id to it.name }
                val target = names[id] ?: id
                val others = affected.filter { it != id }.map { names[it] ?: it }
                _status.value = when {
                    enabled && others.isNotEmpty() ->
                        "Enabled $target and its dependencies: ${others.joinToString(", ")}."
                    enabled -> "Enabled $target."
                    others.isNotEmpty() ->
                        "Disabled $target and its dependents: ${others.joinToString(", ")}."
                    else -> "Disabled $target."
                }
            }.onFailure { e ->
                _status.value = "Skill toggle failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
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
            beginOp("skill-zip")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(graph.context.cacheDir, "skill-import").apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.zip")
                    try {
                        graph.context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { out ->
                                // Bounded copy (audit 2026-11-03): the
                                // picked file used to be copied with no
                                // size limit at all before the installer
                                // ever saw it.
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    copied += n
                                    if (copied > MAX_SKILL_ZIP_BYTES) {
                                        error("The skill package is larger than 50 MB.")
                                    }
                                    out.write(buffer, 0, n)
                                }
                            }
                        } ?: error("Cannot read the selected file.")
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
            endOp("skill-zip")
        }
    }

    /** Installs a skill from a public GitHub repository (HTTPS only; see SkillGitInstaller). */
    fun installSkillFromGitHub(url: String) {
        viewModelScope.launch {
            beginOp("skill-git")
            val result = runCatching {
                withContext(Dispatchers.IO) { SkillGitInstaller.installFromGit(graph.pluginInstaller, url.trim()) }
            }
            _status.value = result.fold(
                onSuccess = { "Installed ${it.name} (${it.id} ${it.version})" + (it.commit?.let { c -> " @ ${c.take(7)}" } ?: "") + "." },
                onFailure = { "GitHub install failed: ${it.message?.take(200)}" },
            )
            endOp("skill-git")
        }
    }

    fun addServer(name: String, url: String, token: String) {
        val config = runCatching {
            McpServerConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { url.trim() },
                url = url.trim(),
                apiKey = token.trim().ifBlank { null },
            )
        }.getOrElse {
            _status.value = it.message ?: "Invalid server settings."
            return
        }
        // Store I/O off the main thread (audit 2026-11-03): add / list
        // read and rewrite the (encrypted) server file synchronously;
        // these three functions used to do it on the caller's thread.
        viewModelScope.launch(Dispatchers.IO) {
            store.add(config)
            _servers.value = store.list()
            _status.value = "Server added. Test the connection to list its tools."
        }
    }

    /** Lets chats call this server's tools. Only meaningful after a successful connection test. */
    fun setAllowCalls(server: McpServerConfig, allow: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            store.add(server.copy(allowCalls = allow))
            _servers.value = store.list()
        }
    }

    fun removeServer(id: String) {
        _tools.value = _tools.value - id
        viewModelScope.launch(Dispatchers.IO) {
            store.remove(id)
            _servers.value = store.list()
        }
    }

    fun testServer(config: McpServerConfig) {
        viewModelScope.launch {
            beginOp("test-server")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = store.clientFor(config)
                    client.initialize().getOrThrow()
                    client.listTools().getOrThrow()
                }
            }
            result.fold(
                onSuccess = { list ->
                    _tools.value = _tools.value + (config.id to list.map { it.name })
                    _status.value = "Connected to ${config.name}: ${list.size} tool(s)."
                    // Persisting the tool list is store I/O — off main.
                    viewModelScope.launch(Dispatchers.IO) {
                        store.add(config.copy(tools = list.map { McpToolInfo(it.name, it.description.take(200)) }))
                        _servers.value = store.list()
                    }
                },
                onFailure = { _status.value = "Connection failed: ${it.message?.take(200)}" },
            )
            endOp("test-server")
        }
    }

    private companion object {
        /** Hard cap for a picked skill ZIP, enforced during the cache copy. */
        const val MAX_SKILL_ZIP_BYTES = 50L * 1024 * 1024
    }
}
