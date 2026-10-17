package com.anvex.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-independent automatic-backup trigger. Work is bounded and executed off the main thread. */
class BackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_BACKUP) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AppGraph.get().reliability.createAutomaticSnapshot("automatic-alarm") } finally { pending.finish() }
        }
    }
    companion object { const val ACTION_BACKUP = "com.anvex.app.action.AUTOMATIC_BACKUP" }
}
