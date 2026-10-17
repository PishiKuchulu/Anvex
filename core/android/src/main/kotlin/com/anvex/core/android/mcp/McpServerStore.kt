package com.anvex.core.android.mcp

import android.content.Context
import com.anvex.core.ai.mcp.McpClient
import com.anvex.core.ai.mcp.McpServerConfig
import com.anvex.core.android.KeystoreCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * User-added MCP servers. Uses the same file as the harness (harness/mcp-servers.json), so
 * servers added in either place are shared. Tokens are encrypted with the Android Keystore.
 */
class McpServerStore(context: Context) {

    private val file = File(File(context.filesDir, "harness"), "mcp-servers.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Wrapper(val items: List<McpServerConfig> = emptyList())

    @Synchronized
    fun list(): List<McpServerConfig> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString(Wrapper.serializer(), file.readText()).items.map { cfg ->
            val plain = cfg.apiKey?.takeIf { it.isNotBlank() }
                ?.let { runCatching { KeystoreCrypto.decrypt(it) }.getOrNull() }
            cfg.copy(apiKey = plain)
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(config: McpServerConfig) {
        save(list().filter { it.id != config.id } + config)
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filter { it.id != id })
    }

    private fun save(configs: List<McpServerConfig>) {
        file.parentFile?.mkdirs()
        val encrypted = configs.map { cfg ->
            val enc = cfg.apiKey?.takeIf { it.isNotBlank() }
                ?.let { runCatching { KeystoreCrypto.encrypt(it) }.getOrNull() }
            cfg.copy(apiKey = enc)
        }
        file.writeText(json.encodeToString(Wrapper.serializer(), Wrapper(encrypted)))
    }

    fun clientFor(config: McpServerConfig): McpClient =
        McpClient(config.url, config.apiKey, McpClient.defaultClient())
}
