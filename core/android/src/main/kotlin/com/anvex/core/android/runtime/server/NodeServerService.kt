package com.anvex.core.android.runtime.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.anvex.core.android.runtime.node.NodeBridge
import java.io.File
import kotlin.concurrent.thread

/**
 * Persistent Node host for dev servers (F5a, P5): runs in the
 * ":devserver" process because libnode can start only once per process
 * and the one-shot ":node" process is kill-after-command by design.
 *
 * The host script (assets/node-runtime/devserver-host.js) owns a
 * token-guarded loopback control API; [com.anvex.core.android.server.DevServerManager]
 * in the main process drives it. This service is a foreground service
 * while it lives — a running dev server is always visible and stoppable
 * (the plan's battery-honesty mechanism).
 */
class NodeServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!RUNNING.compareAndSet(false, true)) return START_STICKY
        val tokenFile = intent?.getStringExtra(EXTRA_TOKEN_FILE).orEmpty()
        val runtimeDir = intent?.getStringExtra(EXTRA_RUNTIME_DIR).orEmpty()
        thread(name = "anvex-devserver-main") {
            runCatching {
                val host = extractHostScript()
                if (NodeBridge.loaded && host != null && tokenFile.isNotEmpty()) {
                    NodeBridge.startNodeWithArguments(
                        arrayOf("node", host.absolutePath, tokenFile, runtimeDir),
                    )
                }
            }
            // The host exited (stop, crash) — die so the next start gets
            // a fresh process and a fresh Node.
            stopSelf()
            Process.killProcess(Process.myPid())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dev servers", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Anvex dev server")
            .setContentText("A local dev server host is running (loopback only).")
            .setOngoing(true)
            .build()
    }

    private fun extractHostScript(): File? = runCatching {
        val dir = File(filesDir, "devserver").apply { mkdirs() }
        val out = File(dir, "devserver-host.js")
        assets.open("node-runtime/devserver-host.js").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out
    }.getOrNull()

    companion object {
        private val RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val CHANNEL = "anvex-devserver"
        private const val NOTIFICATION_ID = 4711

        const val EXTRA_TOKEN_FILE = "com.anvex.devserver.TOKEN_FILE"
        const val EXTRA_RUNTIME_DIR = "com.anvex.devserver.RUNTIME_DIR"

        fun intent(context: Context, tokenFile: String, runtimeDir: String): Intent =
            Intent(context, NodeServerService::class.java).apply {
                putExtra(EXTRA_TOKEN_FILE, tokenFile)
                putExtra(EXTRA_RUNTIME_DIR, runtimeDir)
            }
    }
}
