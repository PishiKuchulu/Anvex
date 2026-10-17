package com.anvex.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores backup scheduling after reboot. Agent FGS restarts only through Android's service lifecycle or user action. */
class RuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        BackupScheduler.schedule(context)
    }
}
