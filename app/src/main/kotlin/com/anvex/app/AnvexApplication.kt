package com.anvex.app

import android.app.Application
import com.anvex.core.android.AppGraph

class AnvexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        com.anvex.app.runtime.BackupScheduler.schedule(this)
    }
}
