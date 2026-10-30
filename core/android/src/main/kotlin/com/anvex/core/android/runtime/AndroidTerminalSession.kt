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
            // L-04: same scrub as the shell runtime — a preloaded library
            // in the app's environment must not leak into project shells.
            .apply { environment().remove("LD_PRELOAD"); environment()["PWD"] = projectRoot.absolutePath; environment()["HOME"] = projectRoot.absolutePath }
            .start()
        process = proc
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        true
    }

    suspend fun send(command: String, origin: ShellOrigin = ShellOrigin.USER): Boolean = withContext(Dispatchers.IO) {
        if (closed.get() || command.isBlank() || command.length > AndroidShellRuntime.MAX_COMMAND_CHARS) return@withContext false
        if (origin == ShellOrigin.AI) {
            val normalized = command.trim()
            val safeHead = normalized.split(Regex("\\s+"), limit = 2).first().lowercase() in setOf("pwd", "ls", "cat", "head", "tail", "find", "grep", "which", "echo", "printf", "true", "date") &&
                !isStdinHangingCommand(normalized)
            // H-02: the shell runtime already rejected globs; the terminal
            // did not, so `cat .e?v` (and `cat *`) slipped through here.
            // Globs expand to files the sensitive-path check never sees.
            val safeSyntax = !normalized.contains("..") && !containsSensitivePath(normalized) && !normalized.contains(Regex("(^|\\s)(?:/|~[/\\s])")) && !normalized.contains(Regex("[;|&`$<>\\\r\n]")) && !normalized.contains(Regex("(?i)(^|\\s)-(?:exec|execdir|ok|okdir|delete)(?:\\s|$)")) && !normalized.any { it == '*' || it == '?' || it == '[' || it == '{' || it == '!' }
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

    // L-04: bytes of a multi-byte character split across two readAvailable
    // calls (e.g. a 2-byte Persian letter) used to decode as replacement
    // characters on both sides. The incomplete tail is held for the next
    // call instead of being decoded.
    private var pendingTail = ByteArray(0)

    suspend fun readAvailable(maxChars: Int = 50_000): String = withContext(Dispatchers.IO) {
        val input = process?.inputStream ?: return@withContext ""
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (input.available() > 0 && out.size() < maxChars * 4) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxChars * 4 - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        val combined = pendingTail + out.toByteArray()
        val (complete, tail) = splitUtf8Tail(combined)
        pendingTail = tail
        val text = String(complete, Charsets.UTF_8)
        if (text.length > maxChars) {
            // Truncation must not leave a lone high surrogate behind.
            text.take(maxChars).let { cut ->
                if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
            }
        } else text
    }

    /**
     * Splits [bytes] into (complete prefix, incomplete trailing UTF-8
     * sequence). Only the last 3 bytes can start an incomplete sequence;
     * anything else decodes (with replacement for genuinely invalid bytes).
     */
    private fun splitUtf8Tail(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        if (bytes.isEmpty()) return bytes to ByteArray(0)
        var i = bytes.size - 1
        var continuations = 0
        while (i >= 0 && continuations < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
            continuations++
            i--
        }
        if (i < 0) return ByteArray(0) to bytes // all continuation bytes — hold all
        val lead = bytes[i].toInt() and 0xFF
        val needed = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1 // invalid lead byte — decoder replaces it; do not hold
        }
        val have = bytes.size - i
        return if (have < needed) {
            bytes.copyOfRange(0, i) to bytes.copyOfRange(i, bytes.size)
        } else {
            bytes to ByteArray(0)
        }
    }

    // v4.10.2 (second review #7): same drift as the shell runtime —
    // replaced the private regex with the shared SensitivePaths list.
    private fun containsSensitivePath(command: String): Boolean =
        com.anvex.core.engine.SensitivePaths.commandReferencesSensitive(command)

    override fun close() {
        closed.set(true)
        // L-04: Process.destroy() only kills the shell, leaving children (e.g. a dev
        // server) orphaned. Ask the shell to reap its direct children first.
        runCatching { writer?.write("pkill -P \$\$ 2>/dev/null\nexit\n"); writer?.flush() }
        process?.destroy()
        process = null
        writer = null
    }
}
