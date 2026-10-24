package com.anvex.core.android.server

import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.PreviewHarness
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.model.ProjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-app, localhost-only HTTP server for previews (brief §15). Hand-written on
 * ServerSocket: zero third-party dependencies, fully controlled security.
 *
 *  - binds 127.0.0.1 ONLY (never 0.0.0.0 — not reachable from the network)
 *  - v1.1 §12: validates the Host header against loopback destinations. This is
 *    a destination hardening check, not an Origin/CSRF boundary; direct local
 *    clients can still connect to a loopback listener.
 *  - path-sanitized static file serving with MIME types
 *  - `/__preview__` route renders the React transpile harness
 *  - `/__runtime__/` routes serve the OFFLINE bundled runtime (Babel/React, v1.1 §11)
 *  - GET/HEAD only; no directory listing outside project root
 */
class StaticPreviewServer(
    private val fs: ProjectFileSystem,
    private val projectType: ProjectType,
    /** Loads a bundled runtime asset by name (wired to app assets by AppGraph). */
    private val runtimeAsset: (String) -> ByteArray? = { null },
) {
    /** v4.10.1 (SEC-01): per-session loopback token; see LoopbackAuth. */
    val accessToken: String = LoopbackAuth.newToken()

    private val pendingCookie = ThreadLocal<String?>()
    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var pool: ExecutorService? = null

    private val requestCounter = AtomicInteger(0)

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val newPool = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "bolt-preview").apply { isDaemon = true }
        }
        try {
            serverSocket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")).apply {
                port = localPort
            }
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
                } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e);
                    // socket closed
                }
            }
        }, "bolt-preview-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e); }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
    }

    /** Base URL WITH the session token path — WebView entry points use this. */
    val rootUrl: String get() = "http://127.0.0.1:$port/t/$accessToken/"

    /** Entry URL for WebView: harness for React projects, index.html for static. */
    val entryUrl: String
        get() = if (projectType == ProjectType.REACT || projectType == ProjectType.TYPESCRIPT) {
            rootUrl + "__preview__"
        } else {
            rootUrl
        }

    // ------------------------------------------------------------------ handler

    private fun handle(client: Socket) {
        // v4.10.2 (second review #1, CRITICAL): same ThreadLocal leak
        // as the harness server — pendingCookie was never cleared, so
        // a token-less error response on a reused pool thread leaked
        // the real session token. Cleared at request start and in
        // finally; within one request it is only set after auth.
        pendingCookie.remove()
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
                // drain headers
                var contentLength = 0
                var hostHeader = ""
                var secFetchDest = ""
                var cookieHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) contentLength = lower.substring(15).trim().toIntOrNull() ?: 0
                    if (lower.startsWith("host:")) hostHeader = line.substring(5).trim()
                    if (lower.startsWith("sec-fetch-dest:")) secFetchDest = lower.substring(15).trim()
                    if (lower.startsWith("cookie:")) cookieHeader = line.substring(7).trim()
                }
                if (contentLength > 0) {
                    var skipped = 0
                    val buf = ByteArray(minOf(contentLength, 64 * 1024))
                    while (skipped < contentLength) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        skipped += n
                    }
                }

                val rawPath = parts[1].substringBefore('?')

                // v1.1 §12: strict Host validation — only loopback origins may talk
                // to this server (blocks cross-origin references from other apps' WebViews).
                if (!hostHeader.isLocalLoopback()) {
                    respond(socket, 403, "text/plain; charset=utf-8", "Forbidden host".toByteArray())
                    return
                }

                // v4.10.1 (SEC-01): the Host check constrains the destination,
                // not the caller — require the per-session token (path or the
                // cookie it plants) so other on-device apps cannot read the
                // workspace through this server.
                val auth = LoopbackAuth.check(rawPath, cookieHeader, accessToken, LoopbackAuth.PREVIEW_COOKIE)
                if (auth is LoopbackAuth.Check.Denied) {
                    respond(socket, 403, "text/plain; charset=utf-8", "Forbidden".toByteArray())
                    return
                }
                val granted = auth as LoopbackAuth.Check.Granted
                pendingCookie.set(if (granted.viaTokenPath) LoopbackAuth.cookieValue(accessToken, LoopbackAuth.PREVIEW_COOKIE) else null)
                // v4.10.1 (BUG-08): URLDecoder turned '+' into a space (file
                // names like c++.txt 404'd) and threw on malformed '%' —
                // decode strictly as path bytes instead.
                val decoded = decodePath(granted.path)

                // v1.1 §11: bundled offline runtime assets (Babel / React / ReactDOM).
                if (decoded.startsWith(PreviewHarness.RUNTIME_ROUTE)) {
                    serveRuntimeAsset(socket, decoded, method)
                    return
                }

                // Architect review item 3: diagram NAVIGATIONS render through their
                // own harness pages — mermaid sources render offline with the bundled
                // mermaid runtime, SVG files render in the sandboxed inline container.
                // Subresource loads (Sec-Fetch-Dest: image/style/…) still get the raw
                // bytes so <img src="…svg"> embedded in HTML keeps working; clients
                // that send no Sec-Fetch header are treated as navigations.
                if (secFetchDest.isEmpty() || secFetchDest == "document") {
                    val safeDiagram = PathSanitizer.sanitizeOrNull(decoded.trimStart('/'))
                    if (safeDiagram != null && !com.anvex.core.engine.SensitivePaths.isSensitive(safeDiagram) && PreviewHarness.isDiagramPath(safeDiagram) && fs.exists(safeDiagram)) {
                        serveDiagramPage(socket, safeDiagram, method)
                        return
                    }
                }

                if (decoded == "/__preview__") {
                    val html = harnessPage()
                    respond(socket, 200, "text/html; charset=utf-8", html.toByteArray())
                    return
                }

                val rel = decoded.trimStart('/')
                if (rel.isEmpty()) {
                    // root: serve index.html or a placeholder
                    if (fs.exists("index.html")) {
                        serveFile(socket, "index.html", method)
                    } else {
                        val html = """
                            <!doctype html><html><head><meta charset="utf-8">
                            <meta name="viewport" content="width=device-width,initial-scale=1">
                            <style>html,body{height:100%;margin:0}body{font-family:system-ui;background:#0b1020;color:#e6edf3;display:flex;
                            align-items:center;justify-content:center;min-height:100%;text-align:center;padding:24px;box-sizing:border-box}</style>
                            </head><body><div><h2>No index.html yet</h2>
                            <p>Ask the AI to create your app, or add an index.html in the editor.</p></div></body></html>
                        """.trimIndent()
                        respond(socket, 200, "text/html; charset=utf-8", html.toByteArray())
                    }
                    return
                }

                val safe = PathSanitizer.sanitizeOrNull(rel)
                if (safe == null) {
                    respond(socket, 400, "text/plain; charset=utf-8", "Bad path".toByteArray())
                    return
                }
                // v4.10.1 (SEC-01): secrets and app/VCS metadata are never
                // served (404, so existence is not confirmed either).
                if (com.anvex.core.engine.SensitivePaths.isSensitive(safe)) {
                    respond(socket, 404, "text/plain; charset=utf-8", "Not found: $safe".toByteArray())
                    return
                }
                if (!fs.exists(safe)) {
                    respond(socket, 404, "text/plain; charset=utf-8", "Not found: $safe".toByteArray())
                    return
                }
                serveFile(socket, safe, method)
            }
        } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e);
            // connection-level error: drop
        } finally { pendingCookie.remove() }
    }

    private fun serveFile(socket: Socket, rel: String, method: String) {
        if (fs.isDirectory(rel)) {
            val index = listOf("$rel/index.html", "$rel/index.htm").firstOrNull { fs.exists(it) }
            if (index != null) {
                val bytes = fs.readFileBytes(index) ?: ByteArray(0)
                respond(socket, 200, mimeFor(index), bytes, headOnly = method == "HEAD")
            } else {
                respond(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
            }
            return
        }
        val bytes = fs.readFileBytes(rel)
        if (bytes == null) {
            respond(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
            return
        }
        respond(socket, 200, mimeFor(rel), bytes, headOnly = method == "HEAD")
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray, headOnly: Boolean = false, cacheControl: String = "no-store") {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: ").append(cacheControl).append("\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            pendingCookie.get()?.let { append("Set-Cookie: ").append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    /**
     * Serves immutable bundled runtime assets with a strict filename whitelist.
     * Defense in depth: Android's AssetManager already rejects `..` traversal,
     * but the route also validates the requested name against the exact set of
     * bundled files — anything else (URL-decoded `../`, weird names, etc.)
     * 404s before the asset loader is ever consulted.
     */
    private fun serveRuntimeAsset(socket: Socket, decoded: String, method: String) {
        val name = decoded.removePrefix(PreviewHarness.RUNTIME_ROUTE)
        if (name != PreviewHarness.ASSET_BABEL &&
            name != PreviewHarness.ASSET_REACT_UMD &&
            name != PreviewHarness.ASSET_REACT_DOM_UMD &&
            name != PreviewHarness.ASSET_MERMAID &&
            !PreviewHarness.WRAPPER_ASSETS.contains(name)
        ) {
            respond(socket, 404, "text/plain; charset=utf-8", "Runtime asset not found: $name".toByteArray())
            return
        }
        val bytes = runtimeAsset(name)
        if (bytes == null) {
            respond(socket, 404, "text/plain; charset=utf-8", "Runtime asset not found: $name".toByteArray())
            return
        }
        // Runtime assets are fixed at build time — cache hard in the WebView.
        respond(socket, 200, mimeFor(name), bytes, headOnly = method == "HEAD", cacheControl = "max-age=31536000, immutable")
    }

    /**
     * Serves a mermaid (.mmd/.mermaid) or SVG (.svg) project file as its rendered
     * diagram page (generated by [PreviewHarness]). The file content is read and
     * embedded server-side, so rendering works fully offline.
     */
    private fun serveDiagramPage(socket: Socket, rel: String, method: String) {
        val source = fs.readFile(rel)
        if (source == null) {
            respond(socket, 404, "text/plain; charset=utf-8", "Not found: $rel".toByteArray())
            return
        }
        val html = try {
            PreviewHarness.generateDiagramPage(rel, source)
        } catch (e: Exception) {
            android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}")
            respond(socket, 500, "text/plain; charset=utf-8", "Diagram page could not be generated".toByteArray())
            return
        }
        respond(socket, 200, "text/html; charset=utf-8", html.toByteArray(), headOnly = method == "HEAD")
    }

    private fun readLine(input: InputStream): String? {
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

    // ------------------------------------------------------------------ harness

    private val json = Json { encodeDefaults = true }

    private fun harnessPage(): String {
        val files = fs.walk().filter { path -> path.split('/').none { it == ".git" || it == ".anvex" || it == ".anvex-pi" || it == "node_modules" } }
        val manifest = buildJsonArray {
            files.forEach { add(buildJsonObject { put("path", it) }) }
        }
        val entry = detectEntry(files)
        val cssLinks = ArrayList<String>()
        if (fs.exists("index.html")) {
            fs.readFile("index.html")?.let { html ->
                Regex("""<link[^>]+rel=["']stylesheet["'][^>]+href=["']([^"']+)["']""")
                    .findAll(html).forEach { m ->
                        val href = m.groupValues[1]
                        if (!href.startsWith("http")) cssLinks.add("/" + href.trimStart('/'))
                    }
            }
        }
        val importMap = buildImportMap()
        return PreviewHarness.generate(manifest.toString(), entry, cssLinks, importMap)
    }

    /** Entry detection: index.html module script, else common React entry files. */
    private fun detectEntry(files: List<String>): String {
        if (fs.exists("index.html")) {
            fs.readFile("index.html")?.let { html ->
                val m = Regex("""<script[^>]+type=["']module["'][^>]+src=["']([^"']+)["']""").find(html)
                if (m != null) {
                    val src = m.groupValues[1].trim().removePrefix("/").removePrefix("./")
                    if (files.any { it == src }) return src
                }
            }
        }
        return listOf("src/main.jsx", "src/main.tsx", "src/index.jsx", "src/index.tsx", "main.jsx", "index.jsx", "app.js", "index.js")
            .firstOrNull { files.contains(it) } ?: "src/main.jsx"
    }

    /** Bare-dep import map: react family resolves OFFLINE from the bundled runtime;
     * everything else maps to esm.sh (online, v1.1 §11). */
    private fun buildImportMap(): String {
        val map = LinkedHashMap<String, String>()
        map.putAll(PreviewHarness.OFFLINE_SPECIFIERS)
        val pkgText = fs.readFile("package.json")
        if (pkgText != null) {
            try {
                val pkg = json.parseToJsonElement(pkgText).let { it as? kotlinx.serialization.json.JsonObject }
                for (key in listOf("dependencies", "devDependencies")) {
                    val deps = pkg?.get(key) as? kotlinx.serialization.json.JsonObject ?: continue
                    for ((name, versionEl) in deps) {
                        if (PreviewHarness.OFFLINE_SPECIFIERS.containsKey(name) || name == "react-dom/client") continue
                        val version = (versionEl as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "latest"
                        // v4.10.2 (second review #13): non-registry
                        // specifiers (file:, workspace:, link:, git+, raw
                        // URLs) are local packages, not registry versions —
                        // mapping them to esm.sh fabricated a broken URL.
                        // Skip them instead of inventing one; registry
                        // ranges (>=, *, ^, ~) are left for esm.sh to
                        // resolve, as before.
                        if (version.startsWith("file:") || version.startsWith("workspace:") ||
                            version.startsWith("link:") || version.startsWith("git+") ||
                            version.startsWith("http://") || version.startsWith("https://")
                        ) continue
                        val v = version.removePrefix("^").removePrefix("~").ifBlank { "latest" }
                        map[name] = "https://esm.sh/$name@$v"
                    }
                }
            } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e);
            }
        }
        return buildJsonObject {
            map.forEach { (k, v) -> put(k, v) }
        }.toString()
    }

    companion object {
        fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "jsx", "tsx", "ts" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wasm" -> "application/wasm"
            "pdf" -> "application/pdf"
            "xml" -> "application/xml; charset=utf-8"
            "csv" -> "text/csv; charset=utf-8"
            "avif" -> "image/avif"
            "txt", "md" -> "text/plain; charset=utf-8"
            "mmd", "mermaid" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}

/** Host header is loopback only (127.0.0.1[:port] / localhost[:port] / [::1][:port]). */
internal fun String?.isLocalLoopback(): Boolean {
    if (this == null) return false // WebView always sends Host; missing = malformed
    // v4.10.1 (BUG-08): bracketed IPv6 ("[::1]:8080") — substringBefore(':')
    // used to yield "[" and the ::1 branches were unreachable.
    val host = if (startsWith("[")) {
        substring(1, indexOf(']').takeIf { it > 0 } ?: length).lowercase()
    } else {
        substringBefore(':').lowercase()
    }
    return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1"
}

/**
 * Strict path percent-decoding (BUG-08): `%XX` bytes are UTF-8; '+' stays
 * a literal plus (paths are not form-encoded); malformed escapes pass
 * through literally instead of throwing the connection away.
 */
internal fun decodePath(raw: String): String {
    if ('%' !in raw) return raw
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 3 <= raw.length) {
            val code = raw.substring(i + 1, i + 3).toIntOrNull(16)
            // v4.10.2 (second review #13): the harness decoder rejects
            // negative codes, this one did not — "%-1" parsed as -1 and
            // ByteArrayOutputStream.write(-1) silently wrote 0xFF.
            if (code != null && code >= 0) {
                bytes.write(code)
                i += 3
                continue
            }
        }
        bytes.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}
