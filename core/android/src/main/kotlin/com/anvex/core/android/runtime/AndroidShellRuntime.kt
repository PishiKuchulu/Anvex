package com.anvex.core.android.runtime

import com.anvex.core.engine.ActionSafetyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Real process runtime for the Android terminal; bounded and approval-aware. */
enum class ShellOrigin { USER, AI }

class AndroidShellRuntime(
    private val projectRoot: File,
    private val approval: suspend (ActionSafetyPolicy.Assessment) -> Boolean = { false },
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val MAX_COMMAND_CHARS = 8_000
        const val MAX_OUTPUT_CHARS = 200_000
        private const val MAX_OUTPUT_BYTES = 800_000
    }

    suspend fun execute(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, origin: ShellOrigin = ShellOrigin.USER): ShellResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return@withContext ShellResult(false, "", "Command is empty.")
        if (trimmed.length > MAX_COMMAND_CHARS) return@withContext ShellResult(false, "", "Command exceeds the safety limit.")
        if (!projectRoot.isDirectory) return@withContext ShellResult(false, "", "Project runtime directory does not exist.")
        val assessment = ActionSafetyPolicy.assess(trimmed)
        val aiSafe = origin == ShellOrigin.USER || isAiSafeCommand(trimmed)
        val effectiveAssessment = if (aiSafe) assessment else ActionSafetyPolicy.Assessment(
            ActionSafetyPolicy.Risk.REQUIRES_APPROVAL,
            "AI-originated command is outside the non-destructive terminal allowlist."
        )
        if (effectiveAssessment.requiresApproval && !approval(effectiveAssessment)) {
            return@withContext ShellResult(false, "", "Approval required: ${effectiveAssessment.reason}", approvalRequired = true)
        }
        val safeTimeout = timeoutMs.coerceIn(1_000L, 10 * 60_000L)
        val outputFile = File(projectRoot, ".anvex-shell-${System.nanoTime()}.log")
        return@withContext try {
            val process = ProcessBuilder("/system/bin/sh", "-c", trimmed)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .apply {
                    environment().remove("LD_PRELOAD")
                    environment()["PWD"] = projectRoot.absolutePath
                    environment()["HOME"] = projectRoot.absolutePath
                }
                .start()
            val finished = process.waitFor(safeTimeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                ShellResult(false, readBounded(outputFile), "Process timed out after ${safeTimeout}ms.")
            } else {
                val exit = process.exitValue()
                ShellResult(exit == 0, readBounded(outputFile), if (exit == 0) null else "Process exited with code $exit", exitCode = exit)
            }
        } finally {
            outputFile.delete()
        }
    }

    private fun isAiSafeCommand(command: String): Boolean {
        val normalized = command.trim()
        if (ActionSafetyPolicy.assess(normalized).requiresApproval) return false
        if (normalized.contains("..")) return false
        if (containsSensitivePath(normalized)) return false
        if (normalized.contains(Regex("(^|\\s)(?:/|~[/\\s])"))) return false
        if (normalized.any { it == ';' || it == '|' || it == '&' || it == '`' || it == '$' || it == '<' || it == '>' || it == '\\' || it == '\r' || it == '\n' }) return false
        if (normalized.contains(Regex("(?i)(^|\\s)-(?:exec|execdir|ok|okdir|delete|fprint0?|fprintf|fls)(?:\\s|$)"))) return false
        // Globs and recursive greps expand to files the sensitive-path check never sees
        // (e.g. `cat *`, `grep -r token .`), which would let a prompt-injected model read
        // secrets and then send them out through the fetch tool.
        if (normalized.any { it == '*' || it == '?' || it == '[' || it == '{' || it == '!' }) return false
        if (normalized.contains(Regex("(?i)(^|\\s)(?:-[a-z]*r[a-z]*|--recursive|--dereference-recursive)(?:\\s|$)")) &&
            normalized.trimStart().lowercase().startsWith("grep")) return false
        val head = normalized.split(Regex("\\s+"), limit = 2).first().lowercase()
        return head in setOf("pwd", "ls", "cat", "head", "tail", "find", "grep", "which", "echo", "printf", "true", "date")
    }

    private fun containsSensitivePath(command: String): Boolean {
        val sensitive = Regex("(?i)(^|[ /._-])(\\.env(?:\\.[^ /]+)?|local\\.properties|gradle\\.properties|credentials(?:\\.[^ /]+)?|secrets?(?:\\.[^ /]+)?|.*(?:token|apikey|api_key|secret|password).*?)(?=$|[ /])")
        return sensitive.containsMatchIn(command) || command.contains(".git/config", ignoreCase = true)
    }

    private fun readBounded(file: File): String {
        if (!file.isFile) return ""
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < MAX_OUTPUT_BYTES) {
                val n = input.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT_BYTES - total))
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
            }
            out.toByteArray()
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        return if (text.length <= MAX_OUTPUT_CHARS) text else text.take(MAX_OUTPUT_CHARS) + "\n[output truncated]"
    }
}

data class ShellResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int? = null,
    val approvalRequired: Boolean = false,
)
