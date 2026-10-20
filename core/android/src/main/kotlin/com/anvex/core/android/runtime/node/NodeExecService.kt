package com.anvex.core.android.runtime.node

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.system.Os
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Runs one Node command in a dedicated ":node" process (see the manifest).
 *
 * A separate process is required because libnode can start only once per
 * process and blocks until it exits; killing this process is also the stop
 * mechanism. The NodeManager (main process) starts this service with the full
 * argv + environment, the bundled preload.js streams stdio back over a
 * localhost socket, and when node::Start returns, this service reports the
 * exit code over a second ("CONTROL") connection before dying.
 */
class NodeExecService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val argv = intent?.getStringArrayExtra(EXTRA_ARGV) ?: emptyArray()
        val env = intent?.getStringArrayExtra(EXTRA_ENV) ?: emptyArray()
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (!RUNNING.compareAndSet(false, true)) {
            // A Node is already running in this process (it can only start
            // once) — refuse cleanly so the caller unblocks with an error.
            thread(name = "anvex-node-busy") { reportExit(port, token, -2) }
            return START_NOT_STICKY
        }
        thread(name = "anvex-node-main") {
            applyEnv(env)
            val code = if (NodeBridge.loaded && argv.isNotEmpty()) {
                runCatching { NodeBridge.startNodeWithArguments(argv) }.getOrDefault(-1)
            } else {
                -1
            }
            reportExit(port, token, code)
            stopSelf()
            // Guarantee a fresh process (and a fresh Node) for the next command.
            Process.killProcess(Process.myPid())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // If the manager stops the service (user navigated away / stop), the
        // whole process must die so a blocked node::Start dies with it.
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }

    /** Sets KEY=VALUE pairs in this process's environment before Node starts. */
    private fun applyEnv(pairs: Array<String>) {
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                runCatching { Os.setenv(pair.substring(0, idx), pair.substring(idx + 1), true) }
            }
        }
    }

    private fun reportExit(port: Int, token: String, code: Int) {
        if (port <= 0) return
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                val out = socket.getOutputStream()
                out.write("$token CONTROL\nEXIT $code\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    companion object {
        /** Per-process guard: libnode can start exactly once per process. */
        private val RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)

        const val EXTRA_ARGV = "com.anvex.node.ARGV"
        const val EXTRA_ENV = "com.anvex.node.ENV"
        const val EXTRA_PORT = "com.anvex.node.PORT"
        const val EXTRA_TOKEN = "com.anvex.node.TOKEN"

        /** Builds the start intent for [NodeMobileRuntime]. */
        fun intent(
            context: android.content.Context,
            argv: Array<String>,
            env: Array<String>,
            port: Int,
            token: String,
        ): Intent = Intent(context, NodeExecService::class.java).apply {
            putExtra(EXTRA_ARGV, argv)
            putExtra(EXTRA_ENV, env)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_TOKEN, token)
        }
    }
}
