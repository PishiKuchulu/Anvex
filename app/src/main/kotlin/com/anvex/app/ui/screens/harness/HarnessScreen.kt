package com.anvex.app.ui.screens.harness

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.anvex.app.ui.components.ProjectScaffold
import com.anvex.app.ui.components.ProjectTab
import com.anvex.core.android.AppGraph
import com.anvex.core.android.harness.HarnessBridge
import com.anvex.core.android.server.HarnessWebServer
import kotlinx.coroutines.flow.MutableStateFlow
import com.anvex.app.i18n.tr

/**
 * v4.0 (GOAL 2 + GOAL 6): the OPENCODE-style web harness hosted in the app WebView.
 *
 * - Serves the harness web app from app assets through a loopback-only server.
 * - Injects the [HarnessBridge] as `window.Android`; every capability (agent turns,
 *   terminal, git, files, diagnostics, skills, MCP, builds, ZIP export) goes through
 *   the typed JS bridge — never through HTTP.
 * - GOAL 6 UX: when a ZIP is saved (JSZip → base64 → Android.saveZip), a Snackbar
 *   offers **Open** (ACTION_VIEW) and **Share** (ACTION_SEND) on the saved file.
 */
// v4.4 lint: the Android JS bridge methods are all annotated @JavascriptInterface
// (verified in HarnessBridge); the Any/JsSurface-typed property defeats the detector.
@android.annotation.SuppressLint("JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessScreen(nav: NavController, projectId: String) {
    val context = LocalContext.current
    val graph = AppGraph.get()
    val snackbar = remember { SnackbarHostState() }
    var server by remember { mutableStateOf<HarnessWebServer?>(null) }
    var bridge by remember { mutableStateOf<HarnessBridge?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    // Pending ZIP notification (fileName + uri + optional share intent).
    // v4.4.4 (smoothness): remembered — a fresh instance per recomposition made
    // LaunchedEffect(pendingZip) restart constantly and dropped notifications.
    val pendingZip = remember { MutableStateFlow<Triple<String, String, Intent?>?>(null) }

    LaunchedEffect(projectId) {
        // Adapter: the harness server asks for "harness/<file>" or a runtime asset name.
        val harnessAssetLoader: (String) -> ByteArray? = { name ->
            try {
                if (name.startsWith("harness/")) {
                    context.assets.open(name).use { it.readBytes() }
                } else {
                    context.assets.open("runtime/$name").use { it.readBytes() }
                }
            } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e); null }
        }
        // v4.1 (Nebula): workspace root for the /__anvex__/download route.
        // Evaluated lazily on server threads (runBlocking mirrors HarnessBridge.realRootSync).
        val workspaceRoot: () -> java.io.File? = {
            runCatching {
                val fs = kotlinx.coroutines.runBlocking { graph.projects.fileSystemForAsync(projectId) }
                (fs as? com.anvex.core.android.fs.RealProjectFileSystem)?.rootDirectory()
            }.getOrNull()
        }
        val harnessServer = HarnessWebServer(harnessAssetLoader, workspaceRoot)
        harnessServer.start()
        server = harnessServer
        val harnessBridge = HarnessBridge(projectId) { fileName, uriString, shareIntent ->
            pendingZip.value = Triple(fileName, uriString, shareIntent)
        }
        bridge = harnessBridge
        url = harnessServer.harnessUrl
    }

    // GOAL 6: Snackbar with Open/Share actions for the saved ZIP.
    LaunchedEffect(pendingZip) {
        pendingZip.collect { zip ->
            if (zip != null) {
                val (fileName, uriString, shareIntent) = zip
                pendingZip.value = null
                val result = snackbar.showSnackbar(
                    message = "Saved to Downloads: $fileName",
                    actionLabel = "Share",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    shareOrOpenZip(context, uriString, shareIntent)
                }
            }
        }
    }

    DisposableEffect(projectId) {
        onDispose {
            bridge?.stop()
            server?.stop()
        }
    }

    ProjectScaffold(
        nav = nav,
        projectId = projectId,
        title = "Harness",
        currentTab = ProjectTab.HARNESS,
        actions = {
            IconButton(onClick = { reloadTick++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload harness")
            }
        },
    ) { modifier ->
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
            Column(modifier.fillMaxSize().padding(innerPadding)) {
                val entryUrl = url
                if (entryUrl == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(tr("Starting harness server…"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                } else {
                    val harnessBridge = bridge
                    if (harnessBridge == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        var lastLoaded by remember { mutableStateOf("") }
                        var lastTick by remember { mutableStateOf(0) }
                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.allowUniversalAccessFromFileURLs = false
                                    settings.allowFileAccessFromFileURLs = false
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        settings.safeBrowsingEnabled = true
                                    }
                                    harnessBridge.attach(this)
                                    addJavascriptInterface(harnessBridge.jsInterface, "Android")
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: android.webkit.WebView?,
                                            request: android.webkit.WebResourceRequest?,
                                        ): Boolean {
                                            val target = request?.url ?: return false
                                            val expected = Uri.parse(entryUrl)
                                            val isTrustedOrigin = target.scheme.equals(expected.scheme, ignoreCase = true) &&
                                                target.host.equals(expected.host, ignoreCase = true) &&
                                                target.port == expected.port &&
                                                (target.scheme.equals("http", true) || target.scheme.equals("https", true))
                                            if (isTrustedOrigin) return false
                                            if (target.scheme.equals("http", true) || target.scheme.equals("https", true)) {
                                                if (request.isForMainFrame) {
                                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                                                }
                                                return true
                                            }
                                            return true // never follow file:/content:/intent: navigations
                                        }
                                    }
                                    webChromeClient = object : android.webkit.WebChromeClient() { }
                                    harnessBridge.start()
                                    loadUrl(entryUrl)
                                }
                            },
                            update = { webView ->
                                if (lastLoaded != entryUrl) {
                                    lastLoaded = entryUrl
                                    lastTick = reloadTick
                                    webView.loadUrl(entryUrl)
                                } else if (reloadTick != lastTick) {
                                    lastTick = reloadTick
                                    webView.reload()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onRelease = { webView ->
                                runCatching { webView.stopLoading() }
                                runCatching { webView.removeJavascriptInterface("Android") }
                                runCatching { webView.destroy() }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun shareOrOpenZip(context: android.content.Context, uriString: String, preparedShare: Intent?) {
    runCatching {
        val intent = preparedShare ?: Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ZIP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
