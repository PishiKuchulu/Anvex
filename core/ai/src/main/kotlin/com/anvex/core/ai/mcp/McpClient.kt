package com.anvex.core.ai.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v4.0 (GOAL 2): Model Context Protocol client — JSON-RPC 2.0 over streamable HTTP.
 *
 * Honest scope: remote MCP servers exposing the streamable-HTTP transport
 * (initialize / tools list / tools call). stdio-transport servers cannot be hosted
 * inside Anvex (no arbitrary process spawning for MCP servers on Android) and
 * are documented as unsupported. Responses support both the single-JSON and the
 * newline-delimited (NDJSON) stream framing; only the final result message is
 * returned for calls — progress notifications are not surfaced.
 */
class McpClient(
    private val serverUrl: String,
    private val apiKey: String? = null,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    // M-12: request ids must be unique even when initialize/list/call race
    // on different coroutines — a plain var lost increments.
    private val nextId = java.util.concurrent.atomic.AtomicLong(1L)
    // M-12: streamable-HTTP session state. The server returns
    // `Mcp-Session-Id` on initialize; later requests must echo it (plus
    // the negotiated protocol version) or stateful servers reject them.
    private var sessionId: String? = null
    private var protocolVersion: String = "2025-03-26"

    @Serializable
    data class McpServerInfo(val name: String = "", val version: String = "")

    @Serializable
    data class McpTool(
        val name: String,
        val description: String = "",
        val inputSchemaJson: String = "{}",
    )

    sealed class McpResult {
        data class Ok(val contentText: String, val raw: String) : McpResult()
        data class Err(val code: Int, val message: String) : McpResult()
    }

    data class Session(val serverInfo: McpServerInfo, val protocolVersion: String)

    private var session: Session? = null

    /** M-12: handshake once per client; later calls reuse the session. */
    suspend fun ensureInitialized(): Result<Session> {
        session?.let { return Result.success(it) }
        return initialize()
    }

    suspend fun initialize(): Result<Session> = withContext(Dispatchers.IO) {
        runCatching {
            val response = rpc(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", buildJsonObject { })
                    put("clientInfo", buildJsonObject {
                        put("name", "Anvex-Harness")
                        put("version", "4.0.0")
                    })
                },
            )
            val result = response.result ?: error(response.error ?: "Initialize failed")
            val serverInfoJson = (result as? JsonObject)?.get("serverInfo") as? JsonObject
            val name = (serverInfoJson?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val version = (serverInfoJson?.get("version") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val protocol = ((result as? JsonObject)?.get("protocolVersion") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "2025-03-26"
            protocolVersion = protocol
            // Initialized notification is sent once, right after a successful handshake.
            notify("notifications/initialized")
            Session(McpServerInfo(name, version), protocol).also { session = it }
        }
    }

    suspend fun listTools(): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = rpc("tools/list", buildJsonObject { })
            val result = response.result ?: error(response.error ?: "tools/list failed")
            val toolsJson = (result as? JsonObject)?.get("tools") as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList()
            toolsJson.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                val description = (obj["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                val schema = obj["inputSchema"]?.toString() ?: "{}"
                McpTool(name, description, schema)
            }
        }
    }

    suspend fun callTool(name: String, arguments: Map<String, JsonElement>): Result<McpToolCall> = withContext(Dispatchers.IO) {
        runCatching {
            val response = rpc(
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } })
                },
            )
            val result = response.result ?: error(response.error ?: "tools/call failed")
            val obj = result as? JsonObject
            val isError = (obj?.get("isError") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            val content = obj?.get("content") as? kotlinx.serialization.json.JsonArray
            val text = content
                ?.mapNotNull { ((it as? JsonObject)?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.joinToString("\n")
                ?: result.toString()
            McpToolCall(name, !isError, text)
        }
    }

    @Serializable
    data class McpToolCall(val tool: String, val success: Boolean, val text: String)

    // ------------------------------------------------------------------ transport

    @Serializable
    internal data class JsonRpcResponse(
        val jsonrpc: String? = null,
        val id: Long? = null,
        val result: JsonElement? = null,
        val error: JsonElement? = null,
    )

    @Serializable
    internal data class JsonRpcError(val code: Int = 0, val message: String = "")

    private fun Request.Builder.withSessionHeaders(): Request.Builder {
        header("MCP-Protocol-Version", protocolVersion)
        sessionId?.let { header("Mcp-Session-Id", it) }
        apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        return this
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonRpcResponse {
        val id = nextId.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val request = Request.Builder()
            .url(serverUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .withSessionHeaders()
            .build()
        client.newCall(request).execute().use { resp ->
            // The server may rotate the session id on any response.
            resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val contentType = resp.header("Content-Type") ?: "application/json"
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return JsonRpcResponse(id = id, error = buildJsonObject {
                put("code", -32000)
                put("message", "HTTP ${resp.code}: ${text.take(300)}")
            })
            return parseFrame(text, contentType, id)
        }
    }

    private suspend fun notify(method: String) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        val request = Request.Builder()
            .url(serverUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .withSessionHeaders()
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    /**
     * Handles plain-JSON and NDJSON/SSE-style responses. Returns ONLY the
     * message whose id matches the request. M-12: the old fallback returned
     * another request's response on mismatch — with concurrent calls that
     * attributes one tool's result to another tool. A server that does not
     * echo the id is violating JSON-RPC and gets an error, not a wrong answer.
     */
    private fun parseFrame(text: String, contentType: String, id: Long): JsonRpcResponse {
        val candidates = ArrayList<String>()
        if (contentType.contains("event-stream")) {
            text.lineSequence().filter { it.startsWith("data:") }.forEach { candidates += it.removePrefix("data:").trim() }
        } else {
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) candidates += trimmed
            }
            if (candidates.isEmpty()) candidates += text
        }
        for (raw in candidates) {
            if (raw.isBlank()) continue
            val parsed = runCatching { json.decodeFromString(JsonRpcResponse.serializer(), raw) }.getOrNull() ?: continue
            if (parsed.id == null) continue // notification without id — never a response
            if (parsed.id == id) return parsed
        }
        return JsonRpcResponse(id = id, error = buildJsonObject {
            put("code", -32603)
            put("message", "No JSON-RPC response was returned for request id $id")
        })
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

/** A tool advertised by an MCP server, cached in the config so chats can list it without a network call. */
@Serializable
data class McpToolInfo(val name: String, val description: String = "")

/** Wire configuration persisted by the harness (no credentials beyond the token). */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String? = null,
    val enabled: Boolean = true,
    /** The user must allow the assistant to call this server's tools (default: off). */
    val allowCalls: Boolean = false,
    /** Tools from the last successful connection test. */
    val tools: List<McpToolInfo> = emptyList(),
    /**
     * H-05: the stored key exists but could not be decrypted (Keystore
     * key rotated, backup restored on a new device). The key itself is
     * NOT in this object (apiKey is null); the flag only tells the UI
     * to ask for re-entry and tells saves to preserve the stored blob
     * instead of overwriting it with null.
     */
    val keyUndecryptable: Boolean = false,
) {
    init {
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
            "MCP server URLs must be HTTPS (loopback HTTP is allowed for local testing)."
        }
        require(name.length <= 120 && url.length <= 500)
    }
}

/**
 * H-05: pure merge for MCP config saves (unit-tested; the bridge only
 * supplies the lists and the encrypt function).
 *
 * [view] is the decrypted view: plaintext keys, except entries whose
 * stored key failed to decrypt, which carry `keyUndecryptable = true`
 * and a null key. [storedEncrypted] maps server id to the encrypted key
 * currently on disk. Returns the list to persist (encrypted keys).
 *
 * The old code re-encrypted the decrypted view and wrote it back, so
 * one undecryptable key silently became null on the next save of ANY
 * server — and one failed encryption silently dropped the key being
 * saved. Here an undecryptable key's stored blob is carried over
 * untouched, and a failed encryption throws instead of storing null.
 */
fun mergeMcpConfigsForSave(
    view: List<McpServerConfig>,
    storedEncrypted: Map<String, String?>,
    encrypt: (String) -> String?,
): List<McpServerConfig> = view.map { cfg ->
    when {
        cfg.keyUndecryptable && cfg.apiKey == null -> {
            val kept = storedEncrypted[cfg.id]?.takeIf { it.isNotBlank() }
            if (kept != null) cfg.copy(apiKey = kept) else cfg.copy(keyUndecryptable = false)
        }
        cfg.apiKey.isNullOrBlank() -> cfg.copy(apiKey = null, keyUndecryptable = false)
        else -> {
            val enc = encrypt(cfg.apiKey)
                ?: throw IllegalStateException("Could not encrypt API key for MCP server '${cfg.id}'")
            cfg.copy(apiKey = enc, keyUndecryptable = false)
        }
    }
}
