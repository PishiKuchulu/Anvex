package com.anvex.app

import android.app.Application
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AnvexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // 2026-11-04 (external-audit wave): the periodic encrypted backup
        // used to be scheduled unconditionally. It is now gated by the
        // user's autoBackupEnabled setting (default ON, so behavior is
        // unchanged unless the user turns it off in Settings). The read
        // happens off the main thread; scheduling is idempotent.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        appScope.launch {
            runCatching {
                val enabled = AppGraph.get().settings.settings.first().autoBackupEnabled
                if (enabled) {
                    com.anvex.app.runtime.BackupScheduler.schedule(this@AnvexApplication)
                } else {
                    com.anvex.app.runtime.BackupScheduler.cancel(this@AnvexApplication)
                }
            }
        }
    }
}
