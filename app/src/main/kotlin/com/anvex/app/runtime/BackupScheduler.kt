package com.anvex.app.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object BackupScheduler {
    private const val REQUEST = 7401
    private const val INTERVAL = 6L * 60 * 60 * 1_000L
    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BackupAlarmReceiver::class.java).setAction(BackupAlarmReceiver.ACTION_BACKUP)
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pending)
    }
    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BackupAlarmReceiver::class.java).setAction(BackupAlarmReceiver.ACTION_BACKUP)
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending); pending.cancel()
    }
}
