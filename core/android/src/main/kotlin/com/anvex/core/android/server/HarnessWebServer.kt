package com.anvex.core.android.server

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v4.0 (GOAL 2) / v4.1 (Nebula): loopback-only static server for the web harness assets.
 *
 * Serves exactly:
 *  - `/harness/`          → assets/harness/index.html
 *  - `/harness/<name>`    → assets/harness/<name> (strict whitelist of bundled files,
 *                           plus /harness/fonts/<name> for the offline JetBrains Mono)
 *  - `/__runtime__/<name>`→ the offline React/Babel runtime bundle (shared with the
 *                            preview server; immutable caching)
 *  - `/__anvex__/download?path=<relative>` → v4.1: streams a workspace file back
 *                            as an attachment (Content-Disposition), enabling direct
 *                            downloads of e.g. .anvex/attachments/<sha>*.zip from
 *                            the harness page via a plain <a href> — no blob:, no
 *                            URL.createObjectURL.
 *
 * Same security posture as StaticPreviewServer: binds 127.0.0.1 only, validates the
 * Host header, rejects non-GET/HEAD, no directory listing. The harness talks to
 * Android capabilities exclusively through the JS bridge — never through HTTP —
 * with the single v4.1 exception above, which is read-only, path-sanitized
 * (".." rejected, canonical containment under the workspace root enforced) and
 * loopback-bound by construction.
 */
class HarnessWebServer(
    private val assetLoader: (String) -> ByteArray?,
    /** v4.1: resolves the project workspace root for /__anvex__/download. */
    private val workspaceRootProvider: () -> File? = { null },
) {
    /** v4.10.1 (SEC-01): per-session loopback token; see LoopbackAuth. */
    val accessToken: String = LoopbackAuth.newToken()

    private val pendingCookie = ThreadLocal<String?>()

    @Volatile var port: Int = 0
        private set
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null

    /** The exact asset files the harness may request (defense in depth). */
    private val harnessFiles = setOf(
        "index.html", "styles.css", "app.jsx", "bridge.js", "jszip.min.js",
    )

    /** v4.1: offline JetBrains Mono bundle under /harness/fonts/ (SIL OFL). */
    private val harnessFontFiles = setOf(
        "JetBrainsMono-Regular.ttf", "JetBrainsMono-Medium.ttf", "JetBrainsMono-Bold.ttf",
    )

    /** Runtime asset names beyond the PreviewHarness whitelist that the harness page needs. */
    private val extraRuntimeAssets = setOf("react.js", "react-dom.js", "react-dom-client.js", "react-jsx-runtime.js")

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val newPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "bolt-harness").apply { isDaemon = true } }
        try {
            serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { port = localPort }
            pool = newPool
        } catch (t: Throwable) {
            newPool.shutdownNow()
            throw t
        }
        val sock = requireNotNull(serverSocket) { "Server socket was not created." }
        Thread({
            while (!sock.isClosed) {
                try {
                    val client = sock.accept()
                    pool?.execute { handle(client) }
                } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e); }
            }
        }, "bolt-harness-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
    }

    val harnessUrl: String get() = "http://127.0.0.1:$port/t/$accessToken/harness/"

    // ------------------------------------------------------------------ handler

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 15_000
                val input = socket.getInputStream().buffered()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                if (method != "GET" && method != "HEAD") {
                    respond(socket, 405, "text/plain; charset=utf-8", "Method Not Allowed".toByteArray())
                    return
                }
                var hostHeader = ""
                var fetchSite = ""
                var contentLength = 0
                var cookieHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("host:")) hostHeader = line.substring(5).trim()
                    if (lower.startsWith("sec-fetch-site:")) fetchSite = lower.substring(15).trim()
                    if (lower.startsWith("content-length:")) contentLength = lower.substring(15).trim().toIntOrNull() ?: 0
                    if (lower.startsWith("cookie:")) cookieHeader = line.substring(7).trim()
                }
                if (contentLength > 0) {
                    val buf = ByteArray(minOf(contentLength, 64 * 1024))
                    var skipped = 0
                    while (skipped < contentLength) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        skipped += n
                    }
                }
                if (!hostHeader.isLocalLoopback()) {
                    respond(socket, 403, "text/plain; charset=utf-8", "Forbidden host".toByteArray())
                    return
                }
                // Another page on 127.0.0.1 (e.g. a generated app in the preview, which is a
                // different port and therefore a different origin) must not be able to pull
                // harness files or workspace downloads. Browsers send Sec-Fetch-Site; absent
                // means a non-browser client, which can only be on-device anyway.
                if (fetchSite == "cross-site" || fetchSite == "same-site") {
                    respond(socket, 403, "text/plain; charset=utf-8", "Cross-origin requests are not allowed".toByteArray())
                    return
                }
                val rawTarget = parts[1]
                val rawQuery = rawTarget.substringAfter('?', "")
                // v4.10.1 (SEC-01): same token gate as the preview server —
                // Sec-Fetch headers only ever constrained browsers.
                val auth = LoopbackAuth.check(rawTarget.substringBefore('?'), cookieHeader, accessToken)
                if (auth is LoopbackAuth.Check.Denied) {
                    respond(socket, 403, "text/plain; charset=utf-8", "Forbidden".toByteArray())
                    return
                }
                val granted = auth as LoopbackAuth.Check.Granted
                pendingCookie.set(if (granted.viaTokenPath) LoopbackAuth.cookieValue(accessToken) else null)
                val decoded = decodePath(granted.path)
                when {
                    decoded == "/harness" || decoded == "/harness/" || decoded == "/harness/index.html" -> {
                        serveHarnessAsset(socket, "index.html", method)
                    }
                    decoded.startsWith("/harness/fonts/") -> {
                        // v4.1: bundled JetBrains Mono (SIL OFL), strictly whitelisted.
                        val fontName = decoded.removePrefix("/harness/fonts/")
                        if (fontName in harnessFontFiles && !fontName.contains('/')) {
                            serveHarnessAsset(socket, "fonts/$fontName", method)
                        } else {
                            respond(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                        }
                    }
                    decoded.startsWith("/harness/") -> {
                        val name = decoded.removePrefix("/harness/").substringBefore('/')
                        if (name in harnessFiles) serveHarnessAsset(socket, name, method)
                        else respond(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                    }
                    decoded == DOWNLOAD_ROUTE -> {
                        handleWorkspaceDownload(socket, rawQuery, method)
                    }
                    decoded.startsWith(RUNTIME_ROUTE) -> {
                        val runtimeName = decoded.removePrefix(RUNTIME_ROUTE)
                        val allowed = runtimeName == com.anvex.core.engine.PreviewHarness.ASSET_BABEL ||
                            runtimeName == com.anvex.core.engine.PreviewHarness.ASSET_REACT_UMD ||
                            runtimeName == com.anvex.core.engine.PreviewHarness.ASSET_REACT_DOM_UMD ||
                            runtimeName in com.anvex.core.engine.PreviewHarness.WRAPPER_ASSETS ||
                            runtimeName in extraRuntimeAssets
                        if (!allowed) {
                            respond(socket, 404, "text/plain; charset=utf-8", "Runtime asset not found".toByteArray())
                            return
                        }
                        val bytes = assetLoader(runtimeName)
                        if (bytes == null) respond(socket, 404, "text/plain; charset=utf-8", "Runtime asset not found".toByteArray())
                        else respond(socket, 200, mimeFor(runtimeName), bytes, headOnly = method == "HEAD", cacheControl = "max-age=31536000, immutable")
                    }
                    else -> respond(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                }
            }
        } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e); }
    }

    private fun serveHarnessAsset(socket: Socket, name: String, method: String) {
        val bytes = assetLoader("harness/$name")
        if (bytes == null) {
            respond(socket, 404, "text/plain; charset=utf-8", "Harness asset not found: $name".toByteArray())
            return
        }
        respond(socket, 200, mimeFor(name), bytes, headOnly = method == "HEAD", cacheControl = "no-store")
    }

    // ------------------------------------------------------------- v4.1: workspace download

    /**
     * GET/HEAD /__anvex__/download?path=<relative/workspace/path>
     *
     * Streams a file from the project workspace as a download attachment. The
     * server itself is bound to 127.0.0.1 only (see [start]), and the Host header
     * has already been validated as loopback in [handle].
     *
     * Hardening (zip-slip / traversal):
     *  - the path parameter must be relative, non-blank and must NOT contain ".."
     *    or a backslash;
     *  - the resolved target is canonicalized and must remain inside the
     *    canonical workspace root;
     *  - only regular files are served (no directories, no symlinked escapes).
     *
     * Responds 400 for malformed/unsafe paths, 404 when the workspace or the
     * file is unavailable, 403 if canonical containment is violated.
     */
    private fun handleWorkspaceDownload(socket: Socket, rawQuery: String, method: String) {
        fun fail(status: Int, message: String) {
            respond(socket, status, "text/plain; charset=utf-8", message.toByteArray())
        }
        val relative = queryParam(rawQuery, "path")
        // v4.10.1 (BUG-09): segment-based traversal check — the old
        // contains("..") also rejected innocent names like "a..b.txt".
        if (relative.isNullOrEmpty() || relative.startsWith("/") || relative.contains('\\') ||
            relative.split('/').any { it == ".." }
        ) {
            fail(400, "Bad download path")
            return
        }
        // v4.10.1 (SEC-01): secrets and VCS internals are never downloadable
        // (.anvex/attachments stays allowed — it is this route's purpose).
        if (com.anvex.core.engine.SensitivePaths.isSecretFile(relative)) {
            fail(404, "File not found")
            return
        }
        val root = workspaceRootProvider()?.canonicalFile
        if (root == null || !root.isDirectory) {
            fail(404, "Workspace unavailable")
            return
        }
        val target = try {
            java.io.File(root, relative).canonicalFile
        } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e);
            fail(400, "Bad download path")
            return
        }
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            fail(403, "Path escapes the workspace")
            return
        }
        if (!target.isFile) {
            fail(404, "File not found")
            return
        }
        // Attachment filename: strip quotes/control chars so the header stays injectable-proof.
        val fileName = target.name.replace("\"", "_").replace(Regex("[\\p{Cntrl}]"), "")
            .ifBlank { "download.bin" }
        // v4.10.1 (BUG-09): the header is written as ISO-8859-1, which turned
        // Persian (and any non-Latin) names into '?'. Send an ASCII fallback
        // in filename= plus the real name percent-encoded in filename*.
        val asciiName = fileName.map { if (it.code in 0x20..0x7E) it else '_' }.joinToString("")
            .ifBlank { "download.bin" }
        val encodedName = fileName.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val v = b.toInt() and 0xFF
            val ch = v.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in ".-_~") ch.toString() else "%%%02X".format(v)
        }
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Disposition: attachment; filename=\"$asciiName\"; filename*=UTF-8''$encodedName\r\n")
            append("Content-Length: ").append(target.length()).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            pendingCookie.get()?.let { append("Set-Cookie: ").append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (method != "HEAD") {
            target.inputStream().use { input -> input.copyTo(out, bufferSize = 64 * 1024) }
        }
        out.flush()
    }

    /** First value of a query parameter (percent-decoded, '+' preserved). */
    private fun queryParam(rawQuery: String, name: String): String? {
        if (rawQuery.isEmpty()) return null
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx) == name) return percentDecode(pair.substring(idx + 1))
        }
        return null
    }

    /** Strict percent-decoding — deliberately does NOT translate '+' to a space,
     *  because workspace file names may legitimately contain '+'. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 3 <= value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null && code >= 0) {
                    bytes.write(code)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString("UTF-8")
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray, headOnly: Boolean = false, cacheControl: String = "no-store") {
        val reason = when (status) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: ").append(cacheControl).append("\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            pendingCookie.get()?.let { append("Set-Cookie: ").append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16_384) throw IllegalStateException("Header line too long")
        }
        return sb.toString()
    }

    companion object {
        const val RUNTIME_ROUTE = "/__runtime__/"
        const val DOWNLOAD_ROUTE = "/__anvex__/download"

        fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs", "jsx" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}

/** Host validation is shared with the preview server (internal in the same package). */
