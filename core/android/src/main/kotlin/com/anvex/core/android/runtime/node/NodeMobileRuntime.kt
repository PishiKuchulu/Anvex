package com.anvex.core.android.runtime.node

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Real Node.js 24 on-device (Anvex libnode package, Termux-built), the in-app backend behind
 * Settings' NodeBackend.IN_APP.
 *
 * Model: one command at a time. Each command runs in the sacrificial ":node"
 * process ([NodeExecService]) because libnode can start only once per
 * process. The bundled preload.js (loaded via `node -r`) chdirs into the
 * project workspace and streams stdout/stderr back over a localhost socket;
 * the service reports the exit code on a control connection. npm/npx run the
 * bundled npm CLI (pure JS) through the same path.
 *
 * Limits, honestly: no native npm addons (no compiler toolchain on-device),
 * one command at a time, and a command is killed when its timeout hits —
 * including dev servers.
 */
class NodeMobileRuntime(
    private val context: Context,
    private val projectRoot: File,
) {

    data class Line(val level: String, val text: String)
    data class Result(
        val success: Boolean,
        val lines: List<Line>,
        val error: String?,
        val exitCode: Int,
    )

    // Busy state is shared across instances (the gateway creates a runtime per
    // call): the underlying :node process can only ever run one command.
    private val busy = SHARED_BUSY
    @Volatile private var activeServer: ServerSocket? = null

    /** libnode exists only for these ABIs (what nodejs-mobile ships). */
    fun isAvailable(): Boolean = isBundledAvailable()

    /** Kills the currently running command, if any. */
    fun stopCurrent() {
        runCatching { activeServer?.close() }
        runCatching { context.stopService(Intent(context, NodeExecService::class.java)) }
    }

    /**
     * Runs [tool] ("node" | "npm" | "npx") with [args] in the project
     * workspace, streaming output lines to [onLine] as they arrive.
     */
    suspend fun runCommand(
        tool: String,
        args: List<String>,
        timeoutMs: Long,
        onLine: (Line) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext Result(false, emptyList(), "Bundled Node.js is not available on this device.", 1)
        }
        if (!busy.compareAndSet(false, true)) {
            return@withContext Result(false, emptyList(), "Node runtime is busy with another command.", 1)
        }
        try {
            runCommandLocked(tool, args, timeoutMs, onLine)
        } finally {
            busy.set(false)
        }
    }

    private suspend fun runCommandLocked(
        tool: String,
        args: List<String>,
        timeoutMs: Long,
        onLine: (Line) -> Unit,
    ): Result {
        val runtimeDir = ensureRuntimeDir()
            ?: return Result(false, emptyList(), "Could not prepare the Node runtime files.", 1)
        val preload = File(runtimeDir, "preload.js")
        val argv = buildArgv(tool, args, runtimeDir, preload)
            ?: return Result(false, emptyList(), usageError(tool, args), 1)

        val token = UUID.randomUUID().toString()
        val lines = mutableListOf<Line>()
        val linesChannel = Channel<Line>(Channel.UNLIMITED)
        val exitDeferred = CompletableDeferred<Int>()

        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        activeServer = server
        val acceptThread = Thread({ acceptLoop(server, token, lines, linesChannel, exitDeferred) }, "anvex-node-accept")
        acceptThread.isDaemon = true
        acceptThread.start()

        // Forward lines to the caller on the caller's dispatcher (UI-safe).
        val forwardJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            for (line in linesChannel) onLine(line)
        }

        val env = arrayOf(
            "HOME=${context.filesDir.absolutePath}",
            "npm_config_cache=${File(runtimeDir, ".npm-cache").absolutePath}",
            "npm_config_userconfig=${File(runtimeDir, ".npmrc").absolutePath}",
            "ANVEX_NODE_PORT=${server.localPort}",
            "ANVEX_NODE_TOKEN=$token",
            "ANVEX_CWD=${projectRoot.absolutePath}",
        )
        val started = runCatching {
            context.startService(NodeExecService.intent(context, argv.toTypedArray(), env, server.localPort, token))
            true
        }.getOrDefault(false)
        if (!started) {
            closeQuietly(server)
            forwardJob.cancel()
            return Result(false, emptyList(), "Could not start the Node process.", 1)
        }

        return try {
            val code = withTimeout(timeoutMs.coerceIn(1_000L, 15 * 60_000L)) { exitDeferred.await() }
            Result(code == 0, snapshot(lines), if (code == 0) null else "Node exited with code $code", code)
        } catch (e: TimeoutCancellationException) {
            stopCurrent()
            Result(false, snapshot(lines), "Timed out after ${timeoutMs}ms — the Node process was stopped.", 124)
        } finally {
            closeQuietly(server)
            linesChannel.close()
            forwardJob.cancel()
            activeServer = null
        }
    }

    private fun acceptLoop(
        server: ServerSocket,
        token: String,
        lines: MutableList<Line>,
        channel: Channel<Line>,
        exitDeferred: CompletableDeferred<Int>,
    ) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            Thread({ handleConnection(socket, token, lines, channel, exitDeferred) }, "anvex-node-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleConnection(
        socket: Socket,
        token: String,
        lines: MutableList<Line>,
        channel: Channel<Line>,
        exitDeferred: CompletableDeferred<Int>,
    ) {
        runCatching {
            socket.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                val first = reader.readLine() ?: return
                when {
                    first == "$token CONTROL" -> {
                        val exitLine = reader.readLine().orEmpty()
                        val code = exitLine.removePrefix("EXIT ").trim().toIntOrNull() ?: -1
                        if (!exitDeferred.isCompleted) exitDeferred.complete(code)
                    }
                    first == token -> {
                        while (true) {
                            val text = reader.readLine() ?: break
                            val line = Line("log", text.take(4_000))
                            synchronized(lines) { if (lines.size < MAX_LINES) lines.add(line) }
                            channel.trySend(line)
                        }
                    }
                    else -> Unit // unknown token: drop the connection
                }
            }
        }
    }

    private fun buildArgv(tool: String, args: List<String>, runtimeDir: File, preload: File): List<String>? {
        val base = mutableListOf("node", "-r", preload.absolutePath)
        return when (tool) {
            "node" -> {
                val first = args.firstOrNull() ?: return null
                when {
                    first.startsWith("-") -> base + args
                    else -> {
                        // Absolute script paths must be honored as-is:
                        // File(parent, child) does not reliably discard
                        // the parent for absolute children on Android.
                        val asFile = File(first)
                        val script = if (asFile.isAbsolute) asFile else File(projectRoot, first)
                        if (!script.isFile) return null
                        base + script.absolutePath + args.drop(1)
                    }
                }
            }
            "npm" -> base + File(runtimeDir, "npm/bin/npm-cli.js").absolutePath + args
            "npx" -> base + File(runtimeDir, "npm/bin/npx-cli.js").absolutePath + args
            else -> null
        }
    }

    private fun usageError(tool: String, args: List<String>): String = when {
        tool == "node" && args.isEmpty() -> "Usage: node <file.js> [args] (interactive REPL is not supported)."
        tool == "node" -> "Script not found in the workspace: ${args.firstOrNull().orEmpty()}"
        else -> "Unsupported Node tool: $tool"
    }

    /**
     * Copies preload.js and extracts the bundled npm into filesDir/node-runtime
     * once per app version (marker-file check, like the nodejs-mobile samples).
     */
    private fun ensureRuntimeDir(): File? = runCatching {
        val dir = File(context.filesDir, "node-runtime")
        val marker = File(dir, ".anvex-runtime-$RUNTIME_VERSION.ok")
        if (marker.isFile) return@runCatching dir
        dir.deleteRecursively()
        dir.mkdirs()
        context.assets.open("node-runtime/preload.js").use { input ->
            File(dir, "preload.js").outputStream().use { input.copyTo(it) }
        }
        val npmDir = File(dir, "npm")
        npmDir.mkdirs()
        ZipInputStream(context.assets.open("node-runtime/npm.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(npmDir, entry.name)
                require(out.canonicalPath.startsWith(npmDir.canonicalPath)) { "Bad npm.zip entry: ${entry.name}" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        marker.writeText("ok\n")
        dir
    }.getOrNull()

    private fun snapshot(lines: MutableList<Line>): List<Line> = synchronized(lines) { lines.toList() }

    private fun closeQuietly(server: ServerSocket) {
        runCatching { server.close() }
    }

    companion object {
        private val SHARED_BUSY = AtomicBoolean(false)
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        private const val MAX_LINES = 5_000
        private const val RUNTIME_VERSION = "24.22.1-npm11.19.1"

        /**
         * v4.6.1: device-level availability of the bundled runtime — the one
         * source of truth for capability displays. (The Agent Center matrix
         * previously read the engine's static PREVIEW table, which predates
         * the bundled runtime: it showed Node.js "Unavailable" while npm —
         * which cannot exist without Node — showed "Available".)
         */
        fun isBundledAvailable(): Boolean =
            NodeBridge.loaded && Build.SUPPORTED_ABIS.firstOrNull() in SUPPORTED_ABIS
    }
}
