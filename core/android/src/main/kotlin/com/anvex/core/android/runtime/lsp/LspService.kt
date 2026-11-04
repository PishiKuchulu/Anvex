package com.anvex.core.android.runtime.lsp

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
 * Persistent Node host for language servers (F2c, P9): runs in the
 * ":lsp" process because libnode can start only once per process.
 * Mirrors NodeServerService (F5a): the host script
 * (assets/node-runtime/lsp-host.js) owns a token-guarded loopback
 * control API; [LspManager] in the main process drives it. Foreground
 * while it lives — a running language server is visible and stoppable
 * (battery honesty, same as the dev-server host).
 */
class LspService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!RUNNING.compareAndSet(false, true)) return START_STICKY
        val tokenFile = intent?.getStringExtra(EXTRA_TOKEN_FILE).orEmpty()
        val runtimeDir = intent?.getStringExtra(EXTRA_RUNTIME_DIR).orEmpty()
        thread(name = "anvex-lsp-main") {
            runCatching {
                val host = extractHostScript()
                if (NodeBridge.loaded && host != null && tokenFile.isNotEmpty()) {
                    NodeBridge.startNodeWithArguments(
                        arrayOf("node", host.absolutePath, tokenFile, runtimeDir),
                    )
                }
            }
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
            NotificationChannel(CHANNEL, "Language servers", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Anvex language server")
            .setContentText("A local language server host is running (loopback only).")
            .setOngoing(true)
            .build()
    }

    private fun extractHostScript(): File? = runCatching {
        val dir = File(filesDir, "lsp").apply { mkdirs() }
        val out = File(dir, "lsp-host.js")
        assets.open("node-runtime/lsp-host.js").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out
    }.getOrNull()

    companion object {
        private val RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val CHANNEL = "anvex-lsp"
        private const val NOTIFICATION_ID = 4712

        const val EXTRA_TOKEN_FILE = "com.anvex.lsp.TOKEN_FILE"
        const val EXTRA_RUNTIME_DIR = "com.anvex.lsp.RUNTIME_DIR"

        fun intent(context: Context, tokenFile: String, runtimeDir: String): Intent =
            Intent(context, LspService::class.java).apply {
                putExtra(EXTRA_TOKEN_FILE, tokenFile)
                putExtra(EXTRA_RUNTIME_DIR, runtimeDir)
            }
    }
}
