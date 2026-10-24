package com.anvex.core.android.runtime

import com.anvex.core.engine.ActionSafetyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent interactive shell for the user terminal; AI commands remain gateway-controlled. */
class AndroidTerminalSession(
    private val projectRoot: File,
    private val approval: suspend (ActionSafetyPolicy.Assessment) -> Boolean = { false },
) : AutoCloseable {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val closed = AtomicBoolean(false)

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext true
        if (!projectRoot.isDirectory) return@withContext false
        val proc = ProcessBuilder("/system/bin/sh")
            .directory(projectRoot)
            .redirectErrorStream(true)
            .apply { environment()["PWD"] = projectRoot.absolutePath; environment()["HOME"] = projectRoot.absolutePath }
            .start()
        process = proc
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        true
    }

    suspend fun send(command: String, origin: ShellOrigin = ShellOrigin.USER): Boolean = withContext(Dispatchers.IO) {
        if (closed.get() || command.isBlank() || command.length > AndroidShellRuntime.MAX_COMMAND_CHARS) return@withContext false
        if (origin == ShellOrigin.AI) {
            val normalized = command.trim()
            val safeHead = normalized.split(Regex("\\s+"), limit = 2).first().lowercase() in setOf("pwd", "ls", "cat", "head", "tail", "find", "grep", "which", "echo", "printf", "true", "date")
            val safeSyntax = !normalized.contains("..") && !containsSensitivePath(normalized) && !normalized.contains(Regex("(^|\\s)(?:/|~[/\\s])")) && !normalized.contains(Regex("[;|&`$<>\\\r\n]")) && !normalized.contains(Regex("(?i)(^|\\s)-(?:exec|execdir|ok|okdir|delete)(?:\\s|$)"))
            val assessment = if (safeHead && safeSyntax) ActionSafetyPolicy.assess(command) else ActionSafetyPolicy.Assessment(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, "AI-originated command is outside the non-destructive terminal allowlist.")
            if (assessment.requiresApproval && !approval(assessment)) return@withContext false
        }
        if (!start()) return@withContext false
        val out = writer ?: return@withContext false
        out.write(command)
        out.write("\n")
        out.flush()
        true
    }

    suspend fun readAvailable(maxChars: Int = 50_000): String = withContext(Dispatchers.IO) {
        val input = process?.inputStream ?: return@withContext ""
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (input.available() > 0 && out.size() < maxChars * 4) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxChars * 4 - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        val text = out.toString(Charsets.UTF_8.name())
        if (text.length > maxChars) text.take(maxChars) else text
    }

    // v4.10.2 (second review #7): same drift as the shell runtime —
    // replaced the private regex with the shared SensitivePaths list.
    private fun containsSensitivePath(command: String): Boolean =
        com.anvex.core.engine.SensitivePaths.commandReferencesSensitive(command)

    override fun close() {
        closed.set(true)
        runCatching { writer?.write("exit\n"); writer?.flush() }
        process?.destroy()
        process = null
        writer = null
    }
}
