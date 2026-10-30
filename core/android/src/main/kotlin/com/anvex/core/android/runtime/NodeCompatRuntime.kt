package com.anvex.core.android.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.engine.PathSanitizer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * v4.4: an honest in-app Node.js COMPATIBILITY layer.
 *
 * A real Node binary cannot be bundled into an APK (Node is built against
 * glibc; Android runs bionic — see docs/BUILD.md), so this layer does not
 * pretend to be Node. It runs plain JavaScript files on the WebView JS engine
 * with a curated, clearly-limited Node-style API:
 *
 *   - console.log/warn/error/info          (captured, streamed back as lines)
 *   - setTimeout / clearTimeout (passthrough to the engine)
 *   - process.{argv, env, version, platform, exit(code)}
 *   - fs: readFileSync / writeFileSync / appendFileSync / existsSync /
 *         readdirSync / mkdirSync / statSync (subset, workspace-sandboxed)
 *   - require(): relative project files ONLY (./ ../) — NO node_modules
 *     resolution and NO native addons; npm packages must run through the
 *     Termux backend.
 *
 * What it deliberately does NOT provide: network APIs (no fetch/xhr/http),
 * no Buffer, no streams, no child_process. Everything outside the subset
 * throws a descriptive error instead of silently failing.
 */
class NodeCompatRuntime(
    private val context: Context,
    private val projectRoot: File,
) {

    data class Line(val level: String, val text: String)
    data class Result(
        val success: Boolean,
        val lines: List<Line>,
        val error: String?,
        val exitCode: Int,
    )

    private val output = mutableListOf<Line>()
    private var finished = AtomicBoolean(false)

    // ------------------------------------------------------------------ public api

    /**
     * Run [scriptPath] (workspace-relative) inside the compat layer.
     * Blocks the caller for at most [timeoutMs].
     */
    suspend fun execute(scriptPath: String, timeoutMs: Long = 30_000L): Result {
        val safePath = PathSanitizer.sanitize(scriptPath)
            ?: return Result(false, emptyList(), "Script path escapes the workspace.", 1)
        val source = projectRoot.resolve(safePath).takeIf { it.isFile }?.let { file ->
            file.length().takeIf { it in 1..MAX_SCRIPT_BYTES }?.let { file.readText().take(MAX_SCRIPT_CHARS) }
        } ?: return Result(false, emptyList(), "Script not found or too large: $scriptPath", 1)

        output.clear()
        finished.set(false)
        val thread = HandlerThread("anvex-node-compat").also { it.start() }
        val handler = Handler(thread.looper)
        try {
            return suspendCancellableCoroutine { cont ->
                var web: WebView? = null
                fun complete(result: Result) {
                    if (!finished.compareAndSet(false, true)) return
                    handler.post {
                        runCatching { web?.destroy() }
                        runCatching { thread.quitSafely() }
                    }
                    if (cont.isActive) cont.resume(result)
                }

                handler.post {
                    web = createWebView()
                    val js = buildScript(safePath, source)
                    var waited = false
                    val wv = requireNotNull(web) { "WebView was not created" }
                    wv.evaluateJavascript(js) { value ->
                        waited = true
                        val exit = parseExitMarker(value)
                        complete(Result(exit == 0, output.toList(), null, exit))
                    }
                    handler.postDelayed({
                        if (!waited && !finished.get()) {
                            output.add(Line("error", "[anvex] execution timed out after ${timeoutMs}ms"))
                            complete(Result(false, output.toList(), "Timed out.", 124))
                        }
                    }, timeoutMs.coerceIn(1_000L, 5 * 60_000L))
                }
            }
        } finally {
            if (!thread.isAlive) thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val web = WebView(context.applicationContext)
        web.settings.javaScriptEnabled = true
        // No network from the compat layer, ever.
        web.settings.blockNetworkLoads = true
        web.settings.databaseEnabled = false
        web.settings.domStorageEnabled = false
        web.addJavascriptInterface(FsBridge(), "AnvexNodeFs")
        web.addJavascriptInterface(ConsoleBridge(), "AnvexNodeConsole")
        web.loadUrl("about:blank")
        return web
    }

    /** Console + completion bridge. Never sends data out of the app. */
    private inner class ConsoleBridge {
        @JavascriptInterface
        fun log(level: String, text: String) {
            if (output.size < 5_000) {
                synchronized(output) { output.add(Line(level, text.take(4_000))) }
            }
        }

        @JavascriptInterface
        fun ping(): Boolean = true
    }

    /** Sandboxed filesystem bridge. All paths are re-sanitized here (JS input is untrusted). */
    private inner class FsBridge {
        // M-10: writes go through the real filesystem (atomic publish,
        // canonical containment) instead of raw File writes.
        private val fs: RealProjectFileSystem by lazy { RealProjectFileSystem(projectRoot) }

        @JavascriptInterface
        fun invoke(op: String, path: String, arg: String?): String {
            fun ok(data: JSONObject.() -> Unit = {}) = JSONObject().apply { put("ok", true); data(); }.toString()
            return try {
                // M-10: the old code called the THROWING sanitize() outside
                // the try, so `../x` escaped this @JavascriptInterface
                // method as an uncaught IllegalArgumentException (the `?:`
                // branch was dead code). Sanitize-or-null, inside the try,
                // plus a canonical containment check and a symlink refusal.
                val safe = PathSanitizer.sanitizeOrNull(path)
                    ?: return err("path escapes the workspace: $path")
                val target = File(projectRoot, safe)
                if (!target.canonicalFile.toPath().startsWith(projectRoot.canonicalFile.toPath())) {
                    return err("path escapes the workspace: $path")
                }
                if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
                    return err("symlinks are not followed here: $path")
                }
                // App/VCS metadata is read-only through this bridge: a script
                // must not rewrite .git, .anvex or .anvex-pi state.
                val metadata = safe.split('/').any { it == ".git" || it == ".anvex" || it == ".anvex-pi" }
                if (metadata && op in setOf("write", "append", "unlink", "mkdir")) {
                    return err("protected path (app/VCS metadata): $path")
                }
                when (op) {
                    "read" -> when {
                        !target.isFile -> err("no such file: $path")
                        target.length() > MAX_RW_BYTES -> err("file too large to read: $path")
                        else -> ok { put("data", target.readText().take(MAX_RW_CHARS)) }
                    }
                    "write" -> {
                        if (arg == null || arg.length > MAX_RW_CHARS) return err("write payload missing or too large")
                        fs.writeFile(safe, arg)
                        ok()
                    }
                    "append" -> {
                        if (arg == null || arg.length > MAX_RW_CHARS) return err("append payload missing or too large")
                        fs.appendFile(safe, arg)
                        ok()
                    }
                    "exists" -> ok { put("data", target.exists()) }
                    "readdir" -> {
                        if (!target.isDirectory) return err("not a directory: $path")
                        ok { put("data", target.listFiles()?.map { it.name }?.take(1_000) ?: emptyList<String>()) }
                    }
                    "mkdir" -> { target.mkdirs(); ok() }
                    "stat" -> when {
                        !target.exists() -> err("no such file: $path")
                        else -> ok {
                            put("data", JSONObject().apply {
                                put("size", target.length())
                                put("isDirectory", target.isDirectory)
                                put("isFile", target.isFile)
                            })
                        }
                    }
                    "unlink" -> {
                        if (target.isDirectory) return err("unlink is file-only; directories are not removable here")
                        if (target.isFile) target.delete()
                        ok()
                    }
                    else -> err("unsupported fs op: $op")
                }
            } catch (e: Exception) {
                err(e.message ?: e::class.simpleName ?: "fs error")
            }
        }
    }

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message.take(500)).toString()

    private fun parseExitMarker(value: String?): Int {
        if (value == null) return 1
        val raw = value.trim('"').replace("\\\"", "\"")
        return runCatching { JSONObject(raw).optInt("exit", 1) }.getOrDefault(1)
    }

    // ------------------------------------------------------------------ script assembly

    private fun buildScript(safePath: String, source: String): String {
        val prelude = runCatching {
            context.assets.open("runtime/node_compat_prelude.js").readBytes().decodeToString()
        }.getOrDefault(DEFAULT_PRELUDE)
        return """
            (function(){
            $prelude
            return JSON.stringify({exit: AnvexNode.__run(${jsString(safePath)}, ${jsString(source)})});
            })()
        """.trimIndent()
    }

    private fun jsString(raw: String): String =
        JSONObject().put("v", raw).toString().let { it.substring(it.indexOf(':') + 1, it.length - 1) }

    companion object {
        const val MAX_SCRIPT_BYTES = 512L * 1024
        const val MAX_SCRIPT_CHARS = 512_000
        const val MAX_RW_BYTES = 512L * 1024
        const val MAX_RW_CHARS = 512_000

        /** Minimal fallback prelude when the asset is unavailable. */
        private const val DEFAULT_PRELUDE = """
            var AnvexNode = {
              __run: function(path, source) {
                var e = AnvexNodeCompat(path, source);
                return JSON.stringify({exit: e});
              }
            };
            function AnvexNodeCompat(path, source) {
              try { (new Function('__filename', source))(path); return 0; }
              catch (err) { AnvexNodeConsole.log('error', String(err)); return 1; }
            }
        """
    }
}
