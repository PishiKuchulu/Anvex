package com.anvex.core.android.reliability

import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deterministic, bounded repairs only. No arbitrary shell or plugin execution. */
class SelfHealingEngine(private val fs: ProjectFileSystem) {
    data class Finding(val id: String, val severity: Severity, val message: String, val repairable: Boolean)
    enum class Severity { INFO, WARNING, ERROR }

    suspend fun diagnose(): List<Finding> = withContext(Dispatchers.IO) {
        buildList {
            if (!fs.exists(".anvex")) add(Finding("runtime-dir", Severity.INFO, "Anvex metadata directory is missing.", true))
            if (fs.exists(".anvex-shell.log")) add(Finding("legacy-shell-log", Severity.WARNING, "Legacy shell log is present in the workspace.", true))
            // v4.10.1 (BUG-15): runtime logs used to be written to the
            // workspace ROOT with timestamped names the old check never
            // matched; they now live under .anvex/logs/ — clean the orphans.
            val orphanLogs = fs.walk().count { path ->
                val name = path.substringAfterLast('/')
                !path.contains('/') && (name.startsWith(".anvex-shell-") || name.startsWith(".anvex-node-")) && name.endsWith(".log")
            }
            if (orphanLogs > 0) add(Finding("orphan-runtime-logs", Severity.WARNING, "$orphanLogs orphaned runtime log(s) at the workspace root.", true))
            val suspicious = fs.walk().count { it.contains(".tmp") || it.endsWith(".part") }
            if (suspicious > 0) add(Finding("stale-temp", Severity.WARNING, "$suspicious stale temporary artifact(s) detected.", true))
        }
    }

    suspend fun repair(findingId: String): Boolean = withContext(Dispatchers.IO) {
        when (findingId) {
            "runtime-dir" -> { fs.mkdirs(".anvex"); true }
            "legacy-shell-log" -> fs.delete(".anvex-shell.log", false)
            "orphan-runtime-logs" -> fs.walk().filter { path ->
                val name = path.substringAfterLast('/')
                !path.contains('/') && (name.startsWith(".anvex-shell-") || name.startsWith(".anvex-node-")) && name.endsWith(".log")
            }.take(100).all { fs.delete(it, false) }
            "stale-temp" -> fs.walk().filter { it.contains(".tmp") || it.endsWith(".part") }.take(100).all { fs.delete(it, false) }
            else -> false
        }
    }
}
