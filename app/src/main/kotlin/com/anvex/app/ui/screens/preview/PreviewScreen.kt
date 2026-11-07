package com.anvex.app.ui.screens.preview

import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.app.i18n.tr
import com.anvex.app.ui.components.ProjectScaffold
import com.anvex.app.ui.components.ProjectTab
import com.anvex.app.vm.PreviewViewModel
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.AppSettings
import com.anvex.core.model.ProjectType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Embedded preview (brief §15): WebView over the in-app localhost server,
 * console bridge, refresh, open-in-browser, and an honest capability note for
 * projects whose toolchain cannot run on Android.
 */
/**
 * v4.4 lint: a named JS surface (JavascriptInterface rule). The annotation is
 * declared on the interface AND repeated on the override (runtime requirement).
 */
private interface PreviewJs {
    @android.webkit.JavascriptInterface
    fun post(json: String)
}

@OptIn(ExperimentalMaterial3Api::class)
// v4.4 lint: the __anvex bridge methods are annotated @JavascriptInterface on the
// object literal (verified); the detector cannot resolve the type through remember{},
// so this specific false positive is suppressed at the site.
@android.annotation.SuppressLint("JavascriptInterface")
@Composable
fun PreviewScreen(nav: NavController, projectId: String) {
    val vm: PreviewViewModel = viewModel(key = "preview-$projectId") { PreviewViewModel(projectId) }
    val project by vm.project.collectAsStateWithLifecycle()
    val url by vm.url.collectAsStateWithLifecycle()
    val console by vm.console.collectAsStateWithLifecycle()
    val refreshTick by vm.refreshTick.collectAsStateWithLifecycle()
    val previewFiles by vm.previewFiles.collectAsStateWithLifecycle()
    // Wiring gap 6: the console pane's visibility comes from the
    // persisted previewConsoleVisible setting, and the toolbar toggle
    // now persists through SettingsRepository.setPreviewConsoleVisible
    // (the seeding below applies the persisted value once per entry;
    // in-session toggles are never overwritten by it).
    val appSettings by AppGraph.get().settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var showConsole by remember { mutableStateOf(false) }
    var consoleSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(appSettings.previewConsoleVisible) {
        if (!consoleSeeded) {
            showConsole = appSettings.previewConsoleVisible
            consoleSeeded = true
        }
    }
    // UI #2: page-load progress + a visible load-error surface (errors
    // previously went only to the hidden console feed).
    var loadProgress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // v4.10.2 (second review #8): the preview URL carries the loopback
    // session token in its path, so "Open in browser" hands that token
    // to another app (and its history) — the very thing SEC-01 keeps
    // out. Ask first instead of silently sharing it.
    var showBrowserWarning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v4.4 (architect feature 7): retained reference for the screenshot capture.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(project?.id, project?.type) {
        project?.let { vm.start(it.type) }
    }

    val bridge = remember {
        object : PreviewJs {
            @JavascriptInterface
            override fun post(json: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    vm.onBridgeMessage(json)
                }
            }
        }
    }

    /** B-3 fix: build bridge JSON safely — no string interpolation into JSON. */
    fun bridgeJson(type: String, level: String? = null, text: String): String {
        val valueKey = if (type == "console") "text" else "message"
        val msg = kotlinx.serialization.json.buildJsonObject {
            put("type", type)
            put("payload", kotlinx.serialization.json.buildJsonObject {
                level?.let { put("level", it) }
                put(valueKey, text)
            })
        }
        return msg.toString()
    }

    /**
     * v4.4 (architect feature 7): capture the current preview into a PNG and hand it
     * to the chat as a vision attachment (honest failure if the capture is blank).
     */
    fun sendScreenshotToChat() {
        val view = webViewRef
        if (view == null || view.width == 0 || view.height == 0) {
            vm.onBridgeMessage(bridgeJson("error", text = "Screenshot unavailable: the preview is not laid out yet."))
            return
        }
        val bitmap = runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            view.draw(canvas) // main thread — click handlers run on the UI thread
            bmp
        }.getOrNull()
        if (bitmap == null) {
            vm.onBridgeMessage(bridgeJson("error", text = "Screenshot capture failed."))
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val saved = runCatching {
                val dir = java.io.File(context.cacheDir, "screenshots").apply { mkdirs() }
                val file = java.io.File(dir, "preview-${System.currentTimeMillis()}.png")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file,
                )
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (saved == null) {
                    vm.onBridgeMessage(bridgeJson("error", text = "Could not save the screenshot."))
                } else {
                    com.anvex.app.share.IncomingShare.offer(
                        com.anvex.app.share.IncomingShare.Payload(
                            "Here is a screenshot of the current preview. Analyze it and fix any visual or layout problems.",
                            listOf(saved),
                        )
                    )
                    nav.navigate(com.anvex.app.ui.Routes.chat(projectId))
                }
            }
        }
    }

    ProjectScaffold(
        nav = nav,
        projectId = projectId,
        title = tr("Preview"),
        currentTab = ProjectTab.PREVIEW,
        actions = {
            IconButton(onClick = { vm.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = tr("Refresh"))
            }
            IconButton(onClick = {
                val next = !showConsole
                showConsole = next
                // Persist the choice: the pane reopens as the user left
                // it (and the Settings switch reflects the same value).
                scope.launch { AppGraph.get().settings.setPreviewConsoleVisible(next) }
            }) {
                Icon(Icons.Filled.BugReport, contentDescription = tr("Console"))
            }
            // v4.4 (architect feature 7 — Vision): capture the preview as a PNG and
            // hand it to the chat as a vision attachment. Honest failure if the
            // capture is empty; never a fabricated screenshot.
            IconButton(onClick = { sendScreenshotToChat() }) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = tr("Send screenshot to chat"))
            }
            IconButton(
                onClick = { showBrowserWarning = true },
            ) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = tr("Open in browser"))
            }
        },
    ) { modifier ->
        // v4.4.6: the header block height is measured explicitly and the WebView
        // is padded below it. weight() on AndroidView proved unreliable on
        // device (content rendered behind the banner); this is deterministic.
        var headerHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        if (showBrowserWarning) {
            AlertDialog(
                onDismissRequest = { showBrowserWarning = false },
                title = { Text(tr("Open in external browser?")) },
                text = {
                    Text(
                        tr("The preview URL contains this session's access token, so the browser app will receive it and keep it in its history. The token stops working when the preview stops, but the URL remains in the browser's history until cleared there."),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showBrowserWarning = false
                        url?.let {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                            }
                        }
                    }) { Text(tr("Open anyway")) }
                },
                dismissButton = {
                    TextButton(onClick = { showBrowserWarning = false }) { Text(tr("Cancel")) }
                },
            )
        }
        Box(modifier.fillMaxSize()) {
            var lastLoaded by remember { mutableStateOf("") }
            var lastTick by remember { mutableStateOf(0) }
            url?.let { entryUrl ->
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
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
                            addJavascriptInterface(bridge, "__anvex")
                            webViewClient = object : WebViewClient() {
                                // §13 hardening: the preview WebView must never navigate
                                // off the loopback origin — an external page would keep the
                                // __anvex bridge object and could spam the console feed.
                                // Main-frame navigations to other origins are handed to the
                                // external browser app instead.
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url ?: return false
                                    val isMainFrame = request.isForMainFrame
                                    val expected = Uri.parse(entryUrl)
                                    val isTrustedOrigin = url.scheme.equals(expected.scheme, ignoreCase = true) &&
                                        url.host.equals(expected.host, ignoreCase = true) &&
                                        url.port == expected.port &&
                                        (url.scheme.equals("http", true) || url.scheme.equals("https", true))
                                    if (isTrustedOrigin) return false // exact server origin only; the JS bridge stays trusted
                                    if (url.scheme.equals("http", true) || url.scheme.equals("https", true)) {
                                        if (isMainFrame) {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, url))
                                            }
                                        }
                                        return true // block other origins, including different loopback ports
                                    }
                                    // Non-http(s) schemes (file:, content:, intent:, ...) are
                                    // never loaded; user-triggered intents only via the button.
                                    return true
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?,
                                ) {
                                    pageError = null
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        // UI #2: surface the failure in the UI,
                                        // not only in the hidden console feed.
                                        pageError = error?.description?.toString() ?: "unknown"
                                        vm.onBridgeMessage(
                                            bridgeJson("error", text = "Page failed to load: ${error?.description ?: "unknown"}")
                                        )
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                    msg?.let {
                                        vm.onBridgeMessage(
                                            bridgeJson(
                                                "console",
                                                level = it.messageLevel().name.lowercase(),
                                                text = it.message(),
                                            )
                                        )
                                    }
                                    return true
                                }
                            }
                            loadUrl(entryUrl)
                        }
                    },
                    update = { webView ->
                        if (lastLoaded != entryUrl) {
                            lastLoaded = entryUrl
                            lastTick = refreshTick
                            webView.loadUrl(entryUrl)
                        } else if (refreshTick != lastTick) {
                            lastTick = refreshTick
                            webView.reload()
                        }
                    },
                    // v4.4.6: explicit top padding below the measured header —
                    // weight() on AndroidView rendered behind the banner on device.
                    modifier = Modifier.fillMaxSize()
                        .padding(top = with(density) { headerHeightPx.toDp() }),
                    onRelease = { webView ->
                        runCatching { webView.stopLoading() }
                        runCatching { webView.removeJavascriptInterface("__anvex") }
                        runCatching { webView.destroy() }
                    },
                )
            } ?: run {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text(
                        tr("Starting preview server…"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            // Header overlay: banner + quick-open + console, measured explicitly.
            // The WebView above is padded below this block's height.
            Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { headerHeightPx = it.size.height }
            ) {
                project?.let { p ->
                    if (p.type == ProjectType.VITE || p.type == ProjectType.UNKNOWN) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(
                                tr("This project may need a bundler/dev server (npm run dev). Use the Dev server origin below when the project has one; static files stay editable and exportable either way."),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                    }
                }
                // F5a (P5): origin switch — static files vs the live dev server.
                val origin by vm.origin.collectAsStateWithLifecycle()
                val devState by vm.devState.collectAsStateWithLifecycle()
                LaunchedEffect(origin, devState) {
                    while (origin == PreviewViewModel.PreviewOrigin.DEV &&
                        devState is com.anvex.core.android.server.DevServerState.Running
                    ) {
                        vm.noteDevAlive()
                        kotlinx.coroutines.delay(60_000)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = origin == PreviewViewModel.PreviewOrigin.STATIC,
                        onClick = { vm.setOrigin(PreviewViewModel.PreviewOrigin.STATIC) },
                        label = { Text(tr("Static")) },
                    )
                    FilterChip(
                        selected = origin == PreviewViewModel.PreviewOrigin.DEV,
                        onClick = { vm.setOrigin(PreviewViewModel.PreviewOrigin.DEV) },
                        label = { Text(tr("Dev server")) },
                    )
                    when (val state = devState) {
                        is com.anvex.core.android.server.DevServerState.Running -> {
                            Text(
                                state.url,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.stopDevServer() }) { Text(tr("Stop")) }
                        }
                        is com.anvex.core.android.server.DevServerState.Starting -> {
                            Text(
                                tr("Starting dev server…"),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        is com.anvex.core.android.server.DevServerState.Failed -> {
                            Text(
                                state.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.startDevServer() }) { Text(tr("Retry")) }
                        }
                        com.anvex.core.android.server.DevServerState.Idle -> {
                            if (origin == PreviewViewModel.PreviewOrigin.DEV) {
                                TextButton(onClick = { vm.startDevServer() }) { Text(tr("Start dev server")) }
                            }
                        }
                    }
                }
                if (origin == PreviewViewModel.PreviewOrigin.DEV) {
                    val devLines = when (val state = devState) {
                        is com.anvex.core.android.server.DevServerState.Running -> state.lines
                        is com.anvex.core.android.server.DevServerState.Starting -> state.lines
                        is com.anvex.core.android.server.DevServerState.Failed -> state.lines
                        com.anvex.core.android.server.DevServerState.Idle -> emptyList()
                    }
                    if (devLines.isNotEmpty()) {
                        Text(
                            devLines.takeLast(6).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
                url?.let { entryUrl ->
                    // Architect item 3: quick-open row — entry HTML files plus every
                    // mermaid/SVG diagram, so diagram files are reachable in preview
                    // exactly like HTML/React entries.
                    if (previewFiles.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            previewFiles.forEach { path ->
                                val selected = entryUrl.endsWith(android.net.Uri.encode(path, "/"))
                                FilterChip(
                                    selected = selected,
                                    onClick = { vm.openFile(path) },
                                    label = {
                                        Text(
                                            path.substringAfterLast('/'),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    val state = rememberLazyListState()
                    // v4.4.4 (smoothness): follow the tail only when already at the
                    // bottom — instant jump, no animation fighting console emissions.
                    LaunchedEffect(console.size) {
                        if (console.isNotEmpty()) {
                            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                            if (last == null || last >= console.size - 3) state.scrollToItem(console.size - 1)
                        }
                    }
                    if (showConsole) {
                        Surface(tonalElevation = 2.dp) {
                            Column(Modifier.fillMaxWidth().height(180.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    Text(
                                        "${tr("Console")} (${console.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.weight(1f).padding(top = 6.dp),
                                    )
                                    IconButton(onClick = { vm.clearConsole() }) {
                                        Icon(Icons.Filled.Delete, contentDescription = tr("Clear console"))
                                    }
                                }
                                LazyColumn(state = state) {
                                    // v4.4.4 (smoothness): stable keys for console inserts.
                                    items(console, key = { "${it.ts}:${it.text.hashCode()}" }) { line ->
                                        Text(
                                            line.text,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = when (line.level) {
                                                "error" -> MaterialTheme.colorScheme.error
                                                "warn" -> MaterialTheme.colorScheme.secondary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // UI #2: load progress + visible error surface.
                if (loadProgress in 1..99) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                pageError?.let { err ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                "${tr("Page failed to load")}: $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.reload() }) { Text(tr("Reload")) }
                            IconButton(onClick = { pageError = null }) {
                                Icon(Icons.Filled.Close, contentDescription = tr("Dismiss"))
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { vm.stop() }
    }
}
