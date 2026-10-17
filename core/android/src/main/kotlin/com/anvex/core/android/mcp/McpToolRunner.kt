package com.anvex.core.android.mcp

import com.anvex.core.android.tools.AgentToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Runs an MCP tool call requested by the model. A call runs only when the server is enabled,
 * the user allowed tool calls for it, and the tool is in the server's cached tool list.
 * Results are untrusted data; ChatSession wraps them like the other tool results.
 */
class McpToolRunner(private val store: McpServerStore) {

    suspend fun call(serverRef: String, toolName: String, argumentsJson: String): AgentToolExecutor.ToolResult =
        withContext(Dispatchers.IO) {
            try {
                execute(serverRef, toolName, argumentsJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentToolExecutor.ToolResult(false, "MCP call failed: ${e.message?.take(200) ?: e::class.simpleName}")
            }
        }

    private suspend fun execute(serverRef: String, toolName: String, argumentsJson: String): AgentToolExecutor.ToolResult {
        val ref = serverRef.trim()
        val server = store.list().firstOrNull { it.id == ref || it.name.equals(ref, ignoreCase = true) }
            ?: return AgentToolExecutor.ToolResult(false, "Unknown MCP server: $ref")
        if (!server.enabled || !server.allowCalls) {
            return AgentToolExecutor.ToolResult(
                false,
                "Tool calls are not allowed for ${server.name}. The user must allow them in Skills & MCP.",
            )
        }
        if (server.tools.none { it.name == toolName }) {
            return AgentToolExecutor.ToolResult(false, "Unknown tool '$toolName' on ${server.name}. Use only the listed tools.")
        }
        val parsed = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) }.getOrNull()
        val args = parsed as? JsonObject
            ?: return AgentToolExecutor.ToolResult(false, "Tool arguments must be a JSON object.")

        val client = store.clientFor(server)
        client.initialize().getOrThrow()
        val call = client.callTool(toolName, args).getOrThrow()
        return AgentToolExecutor.ToolResult(call.success, call.text.take(MAX_RESULT_CHARS))
    }

    companion object {
        const val MAX_RESULT_CHARS = 8_000
    }
}
