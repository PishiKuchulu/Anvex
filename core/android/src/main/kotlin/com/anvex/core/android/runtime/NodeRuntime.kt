package com.anvex.core.android.runtime

import com.anvex.core.engine.ActionSafetyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Executes an already-installed Node.js binary without pretending Node is bundled. */
class NodeRuntime(
    private val projectRoot: File,
    private val approval: suspend (ActionSafetyPolicy.Assessment) -> Boolean = { false },
    private val nodeCandidates: List<File> = listOf(
        // Deliberately exclude projectRoot/node: project files are untrusted input
        // and must never become an executable search path.
        File("/data/data/com.termux/files/usr/bin/node"),
        File("/system/bin/node"),
    ),
) {
    suspend fun detect(): NodeInstallation = withContext(Dispatchers.IO) {
        nodeCandidates.firstOrNull { it.isFile && it.canExecute() }?.let {
            NodeInstallation(it.absolutePath, true, null)
        } ?: NodeInstallation(null, false, "Node.js is not installed or not executable in a supported location.")
    }

    suspend fun execute(
        arguments: List<String>,
        timeoutMs: Long = 120_000L,
        origin: ShellOrigin = ShellOrigin.USER,
    ): NodeResult = withContext(Dispatchers.IO) {
        val node = detect()
        if (!node.available) return@withContext NodeResult(false, "", node.error)
        val nodePath = node.path ?: return@withContext NodeResult(false, "", "Node binary path is missing.")
        if (arguments.size > 64 || arguments.any { it.length > 8_000 }) {
            return@withContext NodeResult(false, "", "Node arguments exceed safety limits.")
        }
        val command = (listOf("node") + arguments).joinToString(" ")
        val assessment = ActionSafetyPolicy.assess(command)
        if (origin == ShellOrigin.AI && !isAiSafe(arguments)) {
            val forced = ActionSafetyPolicy.Assessment(
                ActionSafetyPolicy.Risk.REQUIRES_APPROVAL,
                "AI-originated Node execution requires approval.",
            )
            if (!approval(forced)) return@withContext NodeResult(false, "", "Approval required: ${forced.reason}", approvalRequired = true)
        } else if (assessment.requiresApproval && !approval(assessment)) {
            return@withContext NodeResult(false, "", "Approval required: ${assessment.reason}", approvalRequired = true)
        }
        val timeout = timeoutMs.coerceIn(1_000L, 10 * 60_000L)
        val outputFile = File(projectRoot, ".anvex-node-${System.nanoTime()}.log")
        return@withContext try {
            val process = ProcessBuilder(listOf(nodePath) + arguments)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .apply {
                    environment().remove("LD_PRELOAD")
                    environment()["PWD"] = projectRoot.absolutePath
                    environment()["HOME"] = projectRoot.absolutePath
                }
                .start()
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                NodeResult(false, readBounded(outputFile), "Node process timed out after ${timeout}ms.")
            } else {
                val code = process.exitValue()
                NodeResult(code == 0, readBounded(outputFile), if (code == 0) null else "Node exited with code $code", code)
            }
        } catch (e: Exception) {
            NodeResult(false, "", e.message ?: e.javaClass.simpleName)
        } finally {
            outputFile.delete()
        }
    }

    private fun isAiSafe(args: List<String>): Boolean {
        if (args.isEmpty()) return false
        val first = args.first()
        if (first in setOf("--version", "-v", "--help")) return true
        // A JavaScript file is arbitrary code with filesystem/network access;
        // it must never be treated as an AI-safe command.
        return false
    }

    private fun readBounded(file: File): String {
        if (!file.isFile) return ""
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < 800_000) {
                val n = input.read(buffer, 0, minOf(buffer.size, 800_000 - total))
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
            }
            out.toByteArray()
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        return if (text.length <= 200_000) text else text.take(200_000) + "\n[output truncated]"
    }
}

data class NodeInstallation(val path: String?, val available: Boolean, val error: String?)
data class NodeResult(val success: Boolean, val output: String, val error: String? = null, val exitCode: Int? = null, val approvalRequired: Boolean = false)
