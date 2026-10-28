package com.anvex.core.android.runtime.pi

import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.SensitivePaths
import java.io.File
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
     * v4.10.2 (second review #3): calls PiEngine must NEVER let
     * through, even with user approval — Pi runs its own tools in its
     * own process, so unlike the native engine there is no second
     * allowlist behind the approval gateway. Anything the native
     * `read` tool refuses (SensitivePaths) or that escapes the project
     * is denied outright here, before CommandApprovalService is even
     * consulted:
     *  - read/ls/grep: a sensitive or workspace-escaping path;
     *  - find: a sensitive pattern (`.env`, `*.pem`, …);
     *  - write/edit: a workspace-escaping path (sensitive NAMES are
     *    still writable — editing your own `.env` is legitimate work;
     *    only exfiltration reads are hard-denied);
     *  - bash: a command that references a sensitive file, or an
     *    absolute file argument outside the project (Pi works inside
     *    the project; `cat /etc/hosts` from Pi is not project work).
     * This does NOT make Pi bash equal to the native allowlist — Pi
     * bash is still a general shell, and a user-approved non-sensitive
     * command still runs. It closes the silent exfiltration hole:
     * previously `read .env` and `cat .env` through Pi were both SAFE
     * with no prompt at all, because ToolAction("read") always assesses
     * SAFE and bash went through ActionSafetyPolicy alone.
     */
    fun isHardDenied(toolName: String, input: JSONObject, projectRoot: File?): Boolean {
        return when (toolName) {
            "read", "ls" -> pathDenied(input.optString("path"), projectRoot, sensitiveDenied = true)
            "grep" -> pathDenied(input.optString("path"), projectRoot, sensitiveDenied = true)
            "find" -> {
                val pattern = input.optString("pattern")
                pattern.isNotBlank() && ((SensitivePaths.isSensitive(pattern) && !SensitivePaths.isReadableExtractedAttachment(pattern)) || pathDenied(pattern, projectRoot, sensitiveDenied = false))
            }
            "write", "edit" -> pathDenied(input.optString("path"), projectRoot, sensitiveDenied = false)
            "bash" -> bashDenied(input.optString("command"), projectRoot)
            else -> false
        }
    }

    /** True when [path] must not be touched: blank is not denied (Pi fails itself), otherwise sensitive or escaping. */
    private fun pathDenied(path: String, projectRoot: File?, sensitiveDenied: Boolean): Boolean {
        if (path.isBlank()) return false
        // v4.10.5 follow-up: extracted zip attachments are readable review
        // sources (unless themselves secret files) — see SensitivePaths.
        if (sensitiveDenied && SensitivePaths.isSensitive(path) && !SensitivePaths.isReadableExtractedAttachment(path)) return true
        if (path.startsWith("/") || path.matches(Regex("^[A-Za-z]:.*"))) {
            val root = projectRoot?.canonicalFile ?: return true
            val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return true
            return !(target == root || target.path.startsWith(root.path + File.separator))
        }
        // Relative: PathSanitizer rejects traversal that escapes the root
        // ("../x" -> null); "a/../b" normalizes inside and is fine.
        return PathSanitizer.sanitizeOrNull(path) == null
    }

    private fun bashDenied(command: String, projectRoot: File?): Boolean {
        if (command.isBlank()) return false
        if (SensitivePaths.commandReferencesSensitiveOutsideExtracted(command)) return true
        val root = projectRoot?.canonicalFile
        val tokens = command.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.trim('\'', '"') }
        // Skip the command word itself (an absolute binary path is fine);
        // every later absolute file argument must stay in the project
        // (including --flag=/abs/path shapes).
        for (token in tokens.drop(1)) {
            for (piece in token.split('=')) {
                if (!piece.startsWith("/")) continue
                if (root == null) return true
                val target = runCatching { File(piece).canonicalFile }.getOrNull() ?: return true
                if (!(target == root || target.path.startsWith(root.path + File.separator))) return true
            }
        }
        return false
    }

    /**
     * Parses the gateway extension's confirm payload:
     *   line 1: `tool=<name>`, line 2: `input=<full JSON>`, then prose.
     * Returns null when the payload is not a gateway message (other
     * extensions' confirms are denied by the caller — fail-closed).
     * v4.10.2: line 2 used to be a 4000-char TRUNCATED compact JSON,
     * so any write/edit/bash over ~4 KB failed to parse and was
     * denied with no useful reason. The gateway now sends the full
     * JSON on line 2 (the 300-char compact form stays only in the
     * human-readable prose below it).
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
