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
                ?.let { runCatching { decryptToken(cfg, it) }.getOrNull() }
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
                ?.let { runCatching { TOKEN_V2_PREFIX + KeystoreCrypto.encrypt(it, tokenAad(cfg)) }.getOrNull() }
            cfg.copy(apiKey = enc)
        }
        file.writeText(json.encodeToString(Wrapper.serializer(), Wrapper(encrypted)))
    }

    /**
     * Audit 04 (Low): MCP tokens used bare Keystore encryption — a
     * ciphertext could be transplanted between stored fields. New saves
     * are AAD-bound to the server's id + url under a `v2:` marker;
     * decryption accepts the legacy forms (AAD attempt, then plain) so
     * existing stores keep working, and the next save upgrades them.
     */
    private fun decryptToken(cfg: McpServerConfig, stored: String): String =
        if (stored.startsWith(TOKEN_V2_PREFIX)) {
            KeystoreCrypto.decrypt(stored.removePrefix(TOKEN_V2_PREFIX), tokenAad(cfg))
        } else {
            runCatching { KeystoreCrypto.decrypt(stored, tokenAad(cfg)) }
                .getOrElse { KeystoreCrypto.decrypt(stored) }
        }

    private fun tokenAad(cfg: McpServerConfig): ByteArray =
        "anvex-mcp-token-v1|${cfg.id}|${cfg.url}".toByteArray(Charsets.UTF_8)

    private companion object {
        const val TOKEN_V2_PREFIX = "v2:"
    }

    fun clientFor(config: McpServerConfig): McpClient =
        McpClient(config.url, config.apiKey, McpClient.defaultClient())
}
