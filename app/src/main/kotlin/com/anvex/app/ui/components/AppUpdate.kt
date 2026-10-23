package com.anvex.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.anvex.app.i18n.tr
import com.anvex.app.ui.screens.home.formatSize
import com.anvex.core.android.update.AppUpdateChecker
import com.anvex.core.android.update.AppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * v4.7: shared state machine behind Settings → "Check for update" and the
 * on-launch update popup. One controller lives at the app root so both
 * surfaces observe the same check/download, and a download started from
 * either place is visible (and cancellable) in both.
 */
class AppUpdateController(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: AppUpdateInfo) : State
        data class Downloading(val info: AppUpdateInfo, val downloaded: Long, val total: Long) : State
        data class Downloaded(val info: AppUpdateInfo, val file: File) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null

    fun check() {
        if (state is State.Checking || state is State.Downloading) return
        state = State.Checking
        scope.launch {
            state = try {
                AppUpdateChecker.fetchLatestNewerThan(currentVersion)
                    ?.let { State.Available(it) }
                    ?: State.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: "Update check failed")
            }
        }
    }

    fun download(info: AppUpdateInfo) {
        if (state is State.Downloading) return
        val dest = File(File(context.cacheDir, "shared"), "anvex-update-${info.version}.apk")
        state = State.Downloading(info, 0L, info.sizeBytes)
        downloadJob = scope.launch {
            try {
                AppUpdateChecker.download(info.downloadUrl, dest, info.sha256Url) { downloaded, total ->
                    state = State.Downloading(info, downloaded, total)
                }
                state = State.Downloaded(info, dest)
            } catch (e: CancellationException) {
                File(dest.parentFile, dest.name + ".part").delete()
                state = State.Available(info)
            } catch (e: Exception) {
                File(dest.parentFile, dest.name + ".part").delete()
                state = State.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Opens the system installer; routes to the unknown-apps consent first when needed. */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val consent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(consent) }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/**
 * The update popup (launch prompt and Settings share it): current → new
 * version chips, then Download with live progress, then Install.
 */
@Composable
fun AppUpdateDialog(controller: AppUpdateController, onDismiss: () -> Unit) {
    val state = controller.state
    val info = when (state) {
        is AppUpdateController.State.Available -> state.info
        is AppUpdateController.State.Downloading -> state.info
        is AppUpdateController.State.Downloaded -> state.info
        else -> null
    } ?: return

    AlertDialog(
        onDismissRequest = { if (state !is AppUpdateController.State.Downloading) onDismiss() },
        icon = { Icon(Icons.Filled.SystemUpdate, null) },
        title = { Text(tr("Update available")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("A new version is ready"))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("Current"), style = MaterialTheme.typography.labelSmall)
                            Text("v${controller.currentVersion}", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(Icons.Filled.ArrowForward, null, Modifier.size(20.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("New"), style = MaterialTheme.typography.labelSmall)
                            Text(
                                "v${info.version}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Text(
                    "${info.apkName} · ${formatSize(info.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state is AppUpdateController.State.Downloading) {
                    val total = if (state.total > 0) state.total else info.sizeBytes
                    val fraction = if (total > 0) (state.downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatSize(state.downloaded)} / ${formatSize(total)}", style = MaterialTheme.typography.bodySmall)
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateController.State.Available ->
                    Button(onClick = { controller.download(state.info) }) { Text(tr("Download update")) }
                is AppUpdateController.State.Downloading ->
                    Button(onClick = { controller.cancelDownload() }) { Text(tr("Cancel")) }
                is AppUpdateController.State.Downloaded ->
                    Button(onClick = { controller.install(state.file); onDismiss() }) { Text(tr("Install")) }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is AppUpdateController.State.Downloading) {
                TextButton(onClick = onDismiss) { Text(tr("Later")) }
            }
        },
    )
}
