package com.anvex.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anvex.app.R
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps long-running agent orchestration eligible for Android foreground execution.
 *
 * v4.0 (GOAL 2): besides the persistent runtime notification, the service now
 * observes the durable task queue and posts user-visible notifications when a
 * task reaches a terminal or attention state (COMPLETED / FAILED /
 * WAITING_APPROVAL), so background agent runs surface without the user polling
 * the Agent Center. Notifications are importance-DEFAULT and silently omitted
 * when POST_NOTIFICATIONS has not been granted (API 33+).
 */
class AgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var seenTaskStates = HashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Agent runtime", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(EVENT_CHANNEL, "Agent events", NotificationManager.IMPORTANCE_DEFAULT),
        )
        startForeground(NOTIFICATION_ID, notification("Agent runtime active"))
        WatchdogState.markHeartbeat()
        scope.launch {
            while (true) {
                WatchdogState.markHeartbeat()
                kotlinx.coroutines.delay(30_000L)
            }
        }
        scope.launch { runCatching { AppGraph.get().taskEngine.drain() } }
        scope.launch { observeTaskEvents() }
    }

    /**
     * Android 15+ limits `dataSync` foreground services (about six hours per day). When the limit is
     * reached the system calls this instead of killing the process: stop cleanly and let the user
     * restart the runtime from the app.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        WatchdogState.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WatchdogState.markHeartbeat()
        return START_STICKY
    }

    override fun onDestroy() { WatchdogState.clear(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    /** Terminal/attention state transitions become visible notifications. */
    private suspend fun observeTaskEvents() {
        runCatching {
            AppGraph.get().reliability.observeTasks(50).collect { tasks ->
                val nm = getSystemService(NotificationManager::class.java) ?: return@collect
                tasks.take(20).forEach { task ->
                    val previous = seenTaskStates[task.id]
                    if (previous != null && previous != task.state &&
                        task.state in setOf("COMPLETED", "FAILED", "WAITING_APPROVAL", "INTERRUPTED")
                    ) {
                        val (title, text) = when (task.state) {
                            "COMPLETED" -> "Agent task completed" to (task.detail ?: task.label)
                            "FAILED" -> "Agent task failed" to (task.detail ?: task.label)
                            "WAITING_APPROVAL" -> "Agent needs approval" to (task.detail ?: task.label)
                            else -> "Agent task interrupted" to (task.detail ?: task.label)
                        }
                        notifyEvent(nm, task.id.toInt(), title, text)
                    }
                    seenTaskStates[task.id] = task.state
                }
            }
        }
    }

    private fun notifyEvent(nm: NotificationManager, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return // permission not granted — stay silent (the Agent Center still shows everything)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, EVENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(EVENT_NOTIFICATION_BASE + id, notification) }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_agent)
        .setContentTitle("Anvex Agent")
        .setContentText(text)
        .setOngoing(true)
        .build()

    companion object {
        const val CHANNEL = "agent-runtime"
        const val EVENT_CHANNEL = "agent-events"
        const val NOTIFICATION_ID = 4101
        const val EVENT_NOTIFICATION_BASE = 4200
    }
}

object WatchdogState {
    @Volatile private var heartbeat = 0L
    fun markHeartbeat() { heartbeat = System.currentTimeMillis() }
    fun ageMs(now: Long = System.currentTimeMillis()): Long = if (heartbeat == 0L) Long.MAX_VALUE else now - heartbeat
    fun clear() { heartbeat = 0L }
}
