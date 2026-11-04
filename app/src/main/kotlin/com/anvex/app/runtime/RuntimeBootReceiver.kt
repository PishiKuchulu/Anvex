package com.anvex.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restores backup scheduling after reboot — but only when the user has
 * automatic backups enabled (2026-11-04 audit wave; previously this
 * rescheduled unconditionally). Agent FGS restarts only through Android's
 * service lifecycle or user action. */
class RuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runCatching {
                    if (AppGraph.get().settings.settings.first().autoBackupEnabled) {
                        BackupScheduler.schedule(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
