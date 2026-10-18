package com.anvex.app.ui.screens.debug

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.delay
import com.anvex.app.i18n.tr

/**
 * Developer/debug panel (brief §29): runtime status, memory, provider status,
 * preview server state. Never displays API keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(nav: NavController) {
    val context = LocalContext.current
    val graph = AppGraph.get()
    val providers by graph.providers.observeAll().collectAsState(initial = emptyList())
    var memory by remember { mutableStateOf("--") }
    var nativeHeap by remember { mutableStateOf("--") }

    LaunchedEffect(Unit) {
        while (true) {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val max = rt.maxMemory() / (1024 * 1024)
            memory = "Dalvik/ART heap: ${used} MB used / ${max} MB max"
            val native = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            nativeHeap = "Native heap: ${native} MB allocated"
            delay(2000)
        }
    }

    // v4.4.5 (UX): this screen had no way back.
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Debug panel")) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = { nav.popBackStack() }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = tr("Back"),
                    )
                }
            },
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text(tr("Runtime")) },
                supportingContent = {
                    Text(
                        buildString {
                            appendLine(memory)
                            appendLine(nativeHeap)
                            append("Preview server: ${graph.previewServer?.let { "running on ${it.rootUrl}" } ?: "stopped"}")
                        }
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Providers (${providers.size})") },
                supportingContent = {
                    if (providers.isEmpty()) {
                        Text(tr("none configured"))
                    } else {
                        Text(
                            providers.joinToString("\n") { p ->
                                "${p.name} [${p.kind.displayLabel}] key=${if (p.apiKeyEnc != null) "stored" else "missing"} default=${p.isDefault}"
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(tr("Device")) },
                supportingContent = {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = android.os.Debug.MemoryInfo()
                    Debug.getMemoryInfo(memInfo)
                    Text(
                        buildString {
                            appendLine("API level: ${android.os.Build.VERSION.SDK_INT}")
                            appendLine("Device: ${android.os.Build.MODEL}")
                            appendLine("WebView: ${android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "system"}")
                            append("RAM class: ${am?.memoryClass ?: "?"} MB / large ${am?.largeMemoryClass ?: "?"} MB")
                        }
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(tr("Data locations")) },
                supportingContent = {
                    Text(
                        buildString {
                            appendLine("Projects: ${context.filesDir.absolutePath}/projects")
                            append("Database: ${context.filesDir.absolutePath}/databases/anvex.db")
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                },
            )
            ListItem(
                headlineContent = { Text(tr("Note")) },
                supportingContent = {
                    Text(
                        "API keys are never displayed here or logged. Exported projects never contain provider data.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }
    }
}
