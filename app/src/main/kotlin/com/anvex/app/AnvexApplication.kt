package com.anvex.app

import android.app.Application
import com.anvex.core.android.AppGraph
import com.anvex.core.android.reliability.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AnvexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 2026-11-06 (in-app Logs): the crash handler goes in BEFORE
        // anything else can fail — it writes the crash synchronously to
        // a file and chains to the system handler; the next launch
        // imports that file into the diagnostic event store.
        CrashReporter.install(this)
        AppGraph.init(this)
        // 2026-11-04 (external-audit wave): the periodic encrypted backup
        // used to be scheduled unconditionally. It is now gated by the
        // user's autoBackupEnabled setting (default ON, so behavior is
        // unchanged unless the user turns it off in Settings). The read
        // happens off the main thread; scheduling is idempotent.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        appScope.launch {
            // 2026-11-06: import the previous run's crash (if any) into
            // the diagnostic log and leave an app-start marker, so the
            // Logs timeline shows where one run ended and the next began.
            runCatching {
                val graph = AppGraph.get()
                CrashReporter.importPending(this@AnvexApplication) { category, level, message, details ->
                    graph.reliability.log(category, level, message, details)
                }
                val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
                @Suppress("DEPRECATION")
                val version = pi?.let { "${it.versionName} (code ${it.versionCode})" } ?: "unknown"
                graph.reliability.log("app", "INFO", "App started", "version=$version")
            }
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
