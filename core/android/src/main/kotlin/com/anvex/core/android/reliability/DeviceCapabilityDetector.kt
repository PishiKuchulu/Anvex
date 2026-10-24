package com.anvex.core.android.reliability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Runtime capability matrix. Values distinguish platform capability from installed/configured state. */
class DeviceCapabilityDetector(private val context: Context) {
    data class Capabilities(
        val apiLevel: Int,
        val abi: String,
        val arm64: Boolean,
        val notificationPermissionNeeded: Boolean,
        val foregroundService: Boolean,
        val wirelessAdbCapable: Boolean,
        val wirelessAdbEnabled: Boolean,
        val shizukuInstalled: Boolean,
        val storageManager: Boolean,
        val webServer: Boolean,
    ) {
        val wirelessAdb: Boolean get() = wirelessAdbCapable && wirelessAdbEnabled
        val shizukuPossible: Boolean get() = shizukuInstalled
    }

    fun detect(): Capabilities {
        val api = Build.VERSION.SDK_INT
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val wirelessCapable = api >= 30
        val wirelessEnabled = if (wirelessCapable) {
            runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        } else false
        val shizuku = runCatching { context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)
        // v4.10.1: lint's NewApi check cannot follow the `api` alias +
        // runCatching lambda, so the guard is written in the form lint
        // verifies (direct SDK_INT check, try/catch instead of a lambda) —
        // runtime behavior is identical.
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { Environment.isExternalStorageManager() } catch (t: Throwable) { false }
        } else true
        return Capabilities(api, abi, Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }, api >= 33, api >= 26, wirelessCapable, wirelessEnabled, shizuku, storage, true)
    }
}
