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
import java.net.URLDecoder
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

    val rootUrl: String get() = "http://127.0.0.1:$port/"

    /** Entry URL for WebView: harness for React projects, index.html for static. */
    val entryUrl: String
        get() = if (projectType == ProjectType.REACT || projectType == ProjectType.TYPESCRIPT) {
            rootUrl + "__preview__"
        } else {
            rootUrl
        }

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
                // drain headers
                var contentLength = 0
                var hostHeader = ""
                var secFetchDest = ""
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) contentLength = lower.substring(15).trim().toIntOrNull() ?: 0
                    if (lower.startsWith("host:")) hostHeader = line.substring(5).trim()
                    if (lower.startsWith("sec-fetch-dest:")) secFetchDest = lower.substring(15).trim()
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
                val decoded = URLDecoder.decode(rawPath, "UTF-8")

                // v1.1 §12: strict Host validation — only loopback origins may talk
                // to this server (blocks cross-origin references from other apps' WebViews).
                if (!hostHeader.isLocalLoopback()) {
                    respond(socket, 403, "text/plain; charset=utf-8", "Forbidden host".toByteArray())
                    return
                }

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
                    if (safeDiagram != null && PreviewHarness.isDiagramPath(safeDiagram) && fs.exists(safeDiagram)) {
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
                            <style>body{font-family:system-ui;background:#0b1020;color:#e6edf3;display:flex;
                            align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;padding:24px}</style>
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
                if (!fs.exists(safe)) {
                    respond(socket, 404, "text/plain; charset=utf-8", "Not found: $safe".toByteArray())
                    return
                }
                serveFile(socket, safe, method)
            }
        } catch (e: Exception) { android.util.Log.w("Anvex", "non-fatal: ${e.javaClass.simpleName}", e);
            // connection-level error: drop
        }
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
            if (sb.length > 16_384) return sb.toString()
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ harness

    private val json = Json { encodeDefaults = true }

    private fun harnessPage(): String {
        val files = fs.walk().filter { !it.startsWith(".bolt/") }
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
            "txt", "md" -> "text/plain; charset=utf-8"
            "mmd", "mermaid" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}

/** Host header is loopback only (127.0.0.1[:port] / localhost[:port] / [::1][:port]). */
internal fun String?.isLocalLoopback(): Boolean {
    if (this == null) return false // WebView always sends Host; missing = malformed
    val host = substringBefore(':').lowercase().removePrefix("[")
    return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1"
}
