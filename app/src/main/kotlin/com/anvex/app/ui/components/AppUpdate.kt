package com.anvex.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

    init {
        // M-03: a 60–160 MB APK used to sit in cache/shared forever after
        // installation (the installer reads it via FileProvider, so it
        // cannot be deleted at install time). Sweep files older than a
        // day plus any leftover `.part` files on controller creation.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "shared")
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("anvex-update-") && f.lastModified() < cutoff) f.delete()
                    if (f.name.endsWith(".part")) f.delete()
                }
            }
        }
    }

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
        // M-03: never hand the installer an APK signed with a different
        // key than the running app (the debug-key `latest` builds are the
        // reason this check exists).
        if (!AppUpdateChecker.verifySameSigner(context, file)) {
            state = State.Failed("The downloaded APK is not signed with this app's key — refusing to install.")
            return
        }
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
 * The update popup (launch prompt and Settings share it), themed after the
 * reference design: a dark glassy card with a glowing download glyph, a
 * current → new version card, an asset chip, and a gradient progress bar
 * with a byte counter while downloading — all with living color: the glow
 * and border breathe, and a shimmer sweeps the bar while downloading.
 * The state machine and actions are unchanged — only the presentation
 * moved off the plain system dialog.
 * Colors are explicit (always dark) so the popup looks the same on light
 * and dark system themes.
 */
private val UpdateCardBg = Color(0xFF221C33)
private val UpdateCardBorder = Color(0xFF8B7CFF).copy(alpha = 0.6f)
private val UpdateGlass = Color.White.copy(alpha = 0.08f)
private val UpdateTitle = Color(0xFFF5F3FF)
private val UpdateSubtitle = Color(0xFFC4B5FD)
private val UpdateNewVersion = Color(0xFFA78BFA)
private val UpdateLabel = Color(0xFFA1A1B5)
private val UpdateFileText = Color(0xFFD1D5DB)
private val UpdateTrack = Color.White.copy(alpha = 0.12f)
private val UpdateGradientStart = Color(0xFF8B5CF6)
private val UpdateGradientEnd = Color(0xFF22D3EE)
private val UpdateCancelBg = Color(0xFFC7D2FE)
private val UpdateCancelText = Color(0xFF1E1B4B)

@Composable
fun AppUpdateDialog(controller: AppUpdateController, onDismiss: () -> Unit) {
    val state = controller.state
    val info = when (state) {
        is AppUpdateController.State.Available -> state.info
        is AppUpdateController.State.Downloading -> state.info
        is AppUpdateController.State.Downloaded -> state.info
        else -> null
    } ?: return
    val downloading = state is AppUpdateController.State.Downloading

    // Living color: a breathing glow, a breathing border, and a shimmer
    // that sweeps the progress bar while downloading. One infinite
    // transition drives all of them; it only runs while the dialog is
    // composed, so nothing animates after dismissal.
    val motion = rememberInfiniteTransition(label = "update-motion")
    val glowPulse by motion.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.60f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "glow-pulse",
    )
    val borderPulse by motion.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "border-pulse",
    )
    val shimmerPhase by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-phase",
    )

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !downloading,
            dismissOnClickOutside = !downloading,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = UpdateCardBg,
            border = BorderStroke(1.dp, UpdateCardBorder.copy(alpha = borderPulse)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Glowing download glyph: a phone-shaped outline with a
                // down arrow, over a soft violet radial glow.
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(84.dp).clip(CircleShape).background(
                            Brush.radialGradient(
                                listOf(
                                    UpdateGradientStart.copy(alpha = glowPulse),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                    )
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(UpdateCardBg)
                            .border(1.5.dp, UpdateGradientStart, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            tint = UpdateSubtitle,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        tr("Update available"),
                        color = UpdateTitle,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        tr("A new version is ready"),
                        color = UpdateSubtitle,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Current → new version card.
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = UpdateGlass,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        UpdateVersionCell(tr("Current"), "v${controller.currentVersion}", UpdateTitle)
                        Icon(
                            Icons.Filled.ArrowForward,
                            contentDescription = null,
                            tint = UpdateGradientStart,
                            modifier = Modifier.size(28.dp),
                        )
                        UpdateVersionCell(tr("New"), "v${info.version}", UpdateNewVersion)
                    }
                }
                // Asset chip: package glyph + file name + size.
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = UpdateGlass,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Archive,
                            contentDescription = null,
                            tint = UpdateLabel,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "${info.apkName} · ${formatSize(info.sizeBytes)}",
                            color = UpdateFileText,
                            fontSize = 14.sp,
                        )
                    }
                }
                if (state is AppUpdateController.State.Downloading) {
                    val total = if (state.total > 0) state.total else info.sizeBytes
                    val fraction = if (total > 0) {
                        (state.downloaded.toFloat() / total).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val percent = (fraction * 100).toInt()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "${tr("Downloading…")} $percent%",
                            color = UpdateTitle,
                            fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val barWidth = maxWidth
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(UpdateTrack),
                                )
                                Box(
                                    Modifier.fillMaxWidth(fraction).height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(UpdateGradientStart, UpdateGradientEnd),
                                            ),
                                        ),
                                )
                                // Shimmer band sweeping across the bar,
                                // clipped to the bar's own rounded shape.
                                Box(
                                    Modifier.fillMaxWidth().height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                ) {
                                    val bandWidth = barWidth * 0.35f
                                    val bandX = barWidth * (shimmerPhase * 1.4f - 0.2f)
                                    Box(
                                        Modifier.offset(x = bandX).size(bandWidth, 8.dp)
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Color.Transparent,
                                                        Color.White.copy(alpha = 0.35f),
                                                        Color.Transparent,
                                                    ),
                                                ),
                                            ),
                                    )
                                }
                            }
                            // Pulsing head dot riding the fill's end. It
                            // lives outside the clipped bar so it stays
                            // round instead of being cut to bar height.
                            Box(
                                Modifier.fillMaxWidth(fraction).height(8.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier.size(16.dp).clip(CircleShape)
                                            .background(
                                                UpdateGradientStart.copy(alpha = glowPulse),
                                            ),
                                    )
                                    Box(
                                        Modifier.size(10.dp).clip(CircleShape)
                                            .background(Color.White),
                                    )
                                }
                            }
                        }
                        Text(
                            "${formatSize(state.downloaded)} / ${formatSize(total)}",
                            color = UpdateLabel,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state is AppUpdateController.State.Downloaded) {
                    Text(
                        tr("Download complete — tap Install"),
                        color = UpdateSubtitle,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!downloading) {
                        TextButton(onClick = onDismiss) {
                            Text(tr("Later"), color = UpdateLabel)
                        }
                    }
                    when (state) {
                        is AppUpdateController.State.Available ->
                            UpdateGradientButton(tr("Download update")) {
                                controller.download(state.info)
                            }
                        is AppUpdateController.State.Downloading ->
                            Button(
                                onClick = { controller.cancelDownload() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = UpdateCancelBg,
                                    contentColor = UpdateCancelText,
                                ),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            ) {
                                Text(tr("Cancel"), fontWeight = FontWeight.SemiBold)
                            }
                        is AppUpdateController.State.Downloaded ->
                            UpdateGradientButton(tr("Install")) {
                                controller.install(state.file)
                                onDismiss()
                            }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateVersionCell(label: String, version: String, versionColor: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), color = UpdateLabel, fontSize = 12.sp)
        Text(version, color = versionColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UpdateGradientButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                Brush.horizontalGradient(listOf(UpdateGradientStart, UpdateGradientEnd)),
            ),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}
