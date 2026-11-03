package com.anvex.app.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import com.anvex.app.i18n.tr

/**
 * Phase 5.1 Python sandbox: runs code in Pyodide inside a WebView (page: assets/python/index.html).
 * The WebView has no file access and exposes only one bridge method that returns text.
 * Pyodide itself is loaded from a pinned CDN version, so the first run needs network access.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PythonRunnerDialog(code: String, onDismiss: () -> Unit) {
    // 2026-11-03 audit: the loading state was a static text line, so a
    // slow first run looked like a hang for up to the 120s timeout. There
    // is now a real progress indicator, and both state strings go through
    // tr() (resolved here — the WebView callbacks below are not
    // composable scopes and cannot call tr() themselves).
    val loadingText = tr("Loading Python (Pyodide)…")
    val timeoutText = tr("Python timed out after 120 seconds and was stopped.")
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val finished = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            web?.destroy()
            web = null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Python sandbox")) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                // Review A3: the first run downloads the Pyodide runtime
                // from a CDN — say so in the UI, not just in docs.
                Text(
                    tr("First run downloads the Python runtime (needs network)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (running) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 10.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(loadingText, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    // 2026-11-03 audit: output is selectable so results
                    // can be copied out of the sandbox dialog.
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            addJavascriptInterface(PythonBridge { text ->
                                finished.set(true)
                                Handler(Looper.getMainLooper()).post {
                                    output = text
                                    running = false
                                }
                            }, "AnvexPython")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript("runCode(${JSONObject.quote(code)});", null)
                                    // v4.10.1 (SEC-04): a runaway script used to
                                    // spin the sandbox until the user gave up and
                                    // closed the dialog. Bound every run to 120s.
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (!finished.get()) {
                                            output = timeoutText
                                            running = false
                                            web?.stopLoading()
                                            web?.destroy()
                                            web = null
                                        }
                                    }, 120_000)
                                }
                            }
                            loadUrl("file:///android_asset/python/index.html")
                            web = this
                        }
                    },
                    modifier = Modifier.size(1.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

private class PythonBridge(private val callback: (String) -> Unit) {
    @JavascriptInterface
    fun onResult(text: String) {
        callback(text.take(20_000))
    }
}
