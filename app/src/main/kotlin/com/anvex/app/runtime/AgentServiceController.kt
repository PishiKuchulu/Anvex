package com.anvex.app.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * User-controlled foreground Agent lifecycle. No AlarmManager watchdog is used:
 * Android 12+ restricts background foreground-service starts from ordinary alarms.
 * START_STICKY lets Android restart the service when the system legitimately recreates it.
 */
object AgentServiceController {
    fun start(context: Context) {
        AgentRuntimeState.setEnabled(context, true)
        ContextCompat.startForegroundService(context, Intent(context, AgentForegroundService::class.java))
    }
    fun stop(context: Context) {
        AgentRuntimeState.setEnabled(context, false)
        context.stopService(Intent(context, AgentForegroundService::class.java))
    }
}
