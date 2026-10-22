package com.anvex.core.android.runtime.pi

import com.anvex.core.engine.BoltAction
import org.json.JSONObject

/**
 * Maps Pi tool calls onto Anvex [BoltAction]s so the Pi engine inherits
 * the native harness's exact safety semantics through
 * CommandApprovalService + ActionSafetyPolicy:
 *
 *  - `write` / `edit`  -> FileAction (safe under the 2 MB budget)
 *  - `bash`            -> ShellAction (pattern-assessed like native shell)
 *  - `read`/`grep`/`find`/`ls` -> read-only ToolAction (safe)
 *  - MCP tools (`mcp__*`)      -> ToolAction("mcp") (approval required)
 *  - anything else             -> UnknownAction (approval required, fail-closed)
 *
 * Pure and deterministic — unit-tested on the JVM.
 */
object PiToolMapping {

    /** A user-facing approval question derived from a gateway confirm. */
    data class ApprovalAsk(
        val toolName: String,
        val action: BoltAction,
        val summary: String,
    )

    fun toBoltAction(toolName: String, input: JSONObject): BoltAction {
        val inputJson = input.toString()
        return when {
            toolName == "write" -> BoltAction.FileAction(
                content = input.optString("content"),
                filePath = input.optString("path"),
            )
            toolName == "edit" -> BoltAction.FileAction(
                // The fingerprint must cover the mutation; the edits array
                // stands in for the resulting content (Pi applies old->new
                // replacements itself after approval).
                content = input.optJSONArray("edits")?.toString() ?: inputJson,
                filePath = input.optString("path"),
            )
            toolName == "bash" -> BoltAction.ShellAction(content = input.optString("command"))
            toolName == "read" -> BoltAction.ToolAction("read", input.optString("path"))
            toolName == "grep" -> BoltAction.ToolAction(
                "search",
                listOf(input.optString("pattern"), input.optString("path")).filter { it.isNotBlank() }.joinToString(" in "),
            )
            toolName == "find" -> BoltAction.ToolAction("read", input.optString("pattern"))
            toolName == "ls" -> BoltAction.ToolAction("read", input.optString("path"))
            toolName == "mcp" || toolName.startsWith("mcp__") -> BoltAction.ToolAction("mcp", toolName)
            else -> BoltAction.UnknownAction(toolName, inputJson)
        }
    }

    /**
     * Parses the gateway extension's confirm payload:
     *   line 1: `tool=<name>`, line 2: `input=<compact JSON>`, then prose.
     * Returns null when the payload is not a gateway message (other
     * extensions' confirms are denied by the caller — fail-closed).
     */
    fun parseGatewayConfirm(message: String): Pair<String, JSONObject>? {
        val lines = message.lineSequence().toList()
        val tool = lines.getOrNull(0)?.takeIf { it.startsWith("tool=") }?.removePrefix("tool=")?.trim()
            ?: return null
        val inputRaw = lines.getOrNull(1)?.takeIf { it.startsWith("input=") }?.removePrefix("input=")
            ?: return null
        val input = runCatching { JSONObject(inputRaw) }.getOrNull() ?: return null
        return tool to input
    }

    /** Short human-readable rendering for the approval dialog. */
    fun summarize(toolName: String, input: JSONObject): String {
        val detail = when (toolName) {
            "bash" -> input.optString("command")
            "write", "edit", "read", "ls" -> input.optString("path")
            "grep", "find" -> input.optString("pattern")
            else -> input.toString()
        }
        val trimmed = if (detail.length > 300) detail.take(300) + "…" else detail
        return if (trimmed.isBlank()) toolName else "$toolName: $trimmed"
    }
}
