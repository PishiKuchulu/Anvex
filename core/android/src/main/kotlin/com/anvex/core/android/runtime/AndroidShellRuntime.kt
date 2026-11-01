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
        val shellToken = "anvex-shell-${System.nanoTime()}"
        val outputFile = File(projectRoot, ".anvex/logs").apply { mkdirs() }.let { File(it, "shell-${System.nanoTime()}.log") }
        return@withContext try {
            val process = ProcessBuilder("/system/bin/sh", "-c", trimmed)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .apply {
                    environment().remove("LD_PRELOAD")
                    environment()["PWD"] = projectRoot.absolutePath
                    environment()["HOME"] = projectRoot.absolutePath
                    // Review B8: unique marker so the timeout path can
                    // find this shell's pid in /proc (Android's
                    // java.lang.Process has no pid() accessor).
                    environment()["ANVEX_SHELL_TOKEN"] = shellToken
                }
                .start()
            val finished = process.waitFor(safeTimeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                // Review B8: destroy() alone killed only the `sh`, leaving
                // its children (node/npm dev servers) orphaned — the
                // terminal session already reaps its tree on close. Kill
                // the descendant tree first, then the shell.
                reapProcessTree(shellToken)
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

    /**
     * Review B8: SIGKILL every descendant of the timed-out shell
     * (deepest first). The shell is identified by the unique
     * ANVEX_SHELL_TOKEN in its environment — Android's
     * java.lang.Process exposes no pid() — and descendants are
     * discovered through /proc parent links. Best-effort: on any
     * failure the caller still destroys the shell itself, which is
     * the previous behavior.
     */
    private fun reapProcessTree(shellToken: String) {
        runCatching {
            val procDirs = File("/proc").listFiles().orEmpty()
            val marker = "ANVEX_SHELL_TOKEN=$shellToken"
            var rootPid: Long? = null
            for (dir in procDirs) {
                val pid = dir.name.toLongOrNull() ?: continue
                val environ = runCatching { File(dir, "environ").readBytes().toString(Charsets.UTF_8) }.getOrNull() ?: continue
                if (environ.contains(marker)) {
                    rootPid = pid
                    break
                }
            }
            val root = rootPid ?: return
            val childrenOf = HashMap<Long, MutableList<Long>>()
            procDirs.forEach { dir ->
                val pid = dir.name.toLongOrNull() ?: return@forEach
                val ppid = runCatching {
                    File(dir, "stat").readText().substringAfterLast(") ")
                        .split(" ").getOrNull(1)?.toLongOrNull()
                }.getOrNull() ?: return@forEach
                childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
            }
            val descendants = ArrayList<Long>()
            val queue = ArrayDeque<Long>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                childrenOf[current]?.forEach { child ->
                    descendants.add(child)
                    queue.add(child)
                }
            }
            descendants.asReversed().forEach { pid ->
                runCatching { android.os.Process.killProcess(pid.toInt()) }
            }
        }
    }

    private fun isAiSafeCommand(command: String): Boolean {
        val normalized = command.trim()
        if (isStdinHangingCommand(normalized)) return false
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

    // v4.10.2 (second review #7): this used to be a private regex that
    // missed id_rsa, *.pem/*.jks/*.p12/*.keystore, .npmrc and .pypirc
    // even though SensitivePaths already listed them — the two lists
    // had drifted apart. It now IS the shared list, so the shell, the
    // agent read tool and both servers agree on what is sensitive.
    private fun containsSensitivePath(command: String): Boolean =
        com.anvex.core.engine.SensitivePaths.commandReferencesSensitive(command)

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

/**
 * L-04: commands that read stdin or follow forever hang the AI terminal
 * until timeout — the AI path has no stdin to give them. Bare `cat` /
 * `head` read stdin; bare `grep` with only a pattern reads stdin;
 * `tail -f` never terminates. Shared by the shell runtime and the
 * interactive terminal session so the two gates cannot drift apart.
 */
internal fun isStdinHangingCommand(normalized: String): Boolean {
    val parts = normalized.split(Regex("\\s+"))
    if (parts.isEmpty()) return false
    val head = parts.first().lowercase()
    val args = parts.drop(1)
    val operands = args.filter { !it.startsWith("-") }
    return when (head) {
        "cat", "head" -> operands.isEmpty()
        "grep" -> operands.size < 2 // pattern + at least one file
        "tail" -> args.any {
            it == "-f" || it == "--follow" ||
                (it.startsWith("-") && !it.startsWith("--") && 'f' in it)
        }
        else -> false
    }
}

data class ShellResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int? = null,
    val approvalRequired: Boolean = false,
)
