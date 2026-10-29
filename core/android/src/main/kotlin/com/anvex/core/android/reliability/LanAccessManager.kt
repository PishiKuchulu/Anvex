package com.anvex.core.android.reliability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore

/**
 * Process-bound LAN status endpoint. It exposes only non-sensitive health/status
 * information and requires a random bearer token on every request. It is not an
 * agent execution endpoint and never exposes credentials, files or commands.
 */
class LanAccessManager {
    data class Status(val running: Boolean, val port: Int, val address: String, val tokenPreview: String?)

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val clients = Semaphore(MAX_CONCURRENT_CLIENTS)
    @Volatile private var token: String? = null
    @Volatile private var port: Int = DEFAULT_PORT

    @Synchronized
    fun start(
        statusProvider: () -> String,
        requestedPort: Int = DEFAULT_PORT,
        exposeOnLan: Boolean = false,
        htmlProvider: (() -> String)? = null,
        tasksProvider: () -> List<com.anvex.core.android.db.TaskRunEntity> = { emptyList() },
        logsProvider: () -> List<com.anvex.core.android.db.DiagnosticEventEntity> = { emptyList() },
    ): Status {
        if (running.get()) return status()
        require(requestedPort in 1024..65535) { "Invalid LAN port" }
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val serverBind = if (exposeOnLan) "0.0.0.0" else "127.0.0.1"
        val socket = ServerSocket(requestedPort, 32, InetAddress.getByName(serverBind))
        port = socket.localPort
        bindAddress = serverBind
        server = socket
        running.set(true)
        Thread({ serve(socket, statusProvider, htmlProvider, tasksProvider, logsProvider) }, "Anvex-LAN-Status").apply { isDaemon = true }.start()
        return status()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        token = null
    }

    fun authenticate(candidate: String?): Boolean {
        val expected = token ?: return false
        if (!running.get() || candidate == null) return false
        return MessageDigest.isEqual(candidate.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))
    }

    fun currentToken(): String? = token

    @Volatile private var bindAddress: String = "127.0.0.1"

    fun status(): Status = Status(running.get(), port, if (running.get()) "$bindAddress:$port" else "stopped", token?.take(8)?.plus("…"))

    private fun serve(
        socket: ServerSocket,
        statusProvider: () -> String,
        htmlProvider: (() -> String)?,
        tasksProvider: () -> List<com.anvex.core.android.db.TaskRunEntity>,
        logsProvider: () -> List<com.anvex.core.android.db.DiagnosticEventEntity>,
    ) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (!clients.tryAcquire()) { runCatching { client.close() }; continue }
            Thread({ try { handle(client, statusProvider, htmlProvider, tasksProvider, logsProvider) } finally { clients.release() } }, "Anvex-LAN-Client").apply { isDaemon = true }.start()
        }
    }

    /**
     * M-02: `BufferedReader.readLine()` is unbounded, and the old code
     * truncated only AFTER the whole line was in memory — an unauthenticated
     * client could fill memory by never sending `\n`. This reader caps
     * every line at 8 KB and enforces an overall connection deadline, so
     * a slow drip (which resets `soTimeout` on every byte) cannot hold
     * one of the 8 client slots forever.
     */
    private class CappedLineReader(
        private val input: java.io.InputStream,
        private val deadlineMs: Long,
    ) {
        private val buffer = java.io.ByteArrayOutputStream()
        fun readLine(): String? {
            buffer.reset()
            while (true) {
                if (System.currentTimeMillis() > deadlineMs) {
                    throw java.net.SocketTimeoutException("connection deadline exceeded")
                }
                val b = input.read()
                if (b < 0) {
                    return if (buffer.size() == 0) null else buffer.toString(Charsets.US_ASCII.name())
                }
                if (buffer.size() >= MAX_LINE_BYTES) throw java.io.IOException("request line exceeds cap")
                if (b == '\n'.code) break
                if (b != '\r'.code) buffer.write(b)
            }
            return buffer.toString(Charsets.US_ASCII.name())
        }
    }

    private fun handle(
        socket: Socket,
        statusProvider: () -> String,
        htmlProvider: (() -> String)?,
        tasksProvider: () -> List<com.anvex.core.android.db.TaskRunEntity>,
        logsProvider: () -> List<com.anvex.core.android.db.DiagnosticEventEntity>,
    ) {
        socket.use { s ->
            s.soTimeout = 3_000
            try {
                val reader = CappedLineReader(s.getInputStream(), System.currentTimeMillis() + CONNECTION_DEADLINE_MS)
                val request = reader.readLine().orEmpty().take(2_000)
                var line = reader.readLine()
                var authorized = false
                var headerCount = 0
                while (!line.isNullOrEmpty() && ++headerCount <= 64) {
                    if (line.startsWith("Authorization:", ignoreCase = true)) {
                        val value = line.substringAfter(':').trim()
                        authorized = value.startsWith("Bearer ") && authenticate(value.removePrefix("Bearer ").trim())
                    }
                    line = reader.readLine()
                }
                val out = PrintWriter(s.getOutputStream(), true)
                if (!authorized) {
                    writeResponse(out, 401, "application/json", "{\"error\":\"unauthorized\"}")
                } else {
                    // M-02: the query string used to be part of the route
                    // (`/api/status?x=1` -> 404). Strip it before matching.
                    val path = request.substringAfter(' ').substringBefore(' ').substringBefore('?').take(256)
                when {
                    path == "/" -> {
                        val body = htmlProvider?.invoke() ?: defaultHtml()
                        writeResponse(out, 200, "text/html; charset=utf-8", body)
                    }
                    path == "/status" || path == "/api/status" -> {
                        val body = "{\"status\":\"ok\",\"service\":\"Anvex\",\"runtime\":${quote(statusProvider())}}"
                        writeResponse(out, 200, "application/json; charset=utf-8", body)
                    }
                    path == "/api/tasks" -> {
                        val body = tasksProvider().take(50).joinToString(prefix = "[", postfix = "]") { "{\"id\":${it.id},\"label\":${quote(it.label)},\"state\":${quote(it.state)},\"updatedAt\":${it.updatedAt}}" }
                        writeResponse(out, 200, "application/json; charset=utf-8", body)
                    }
                    path == "/api/logs" -> {
                        val body = logsProvider().take(50).joinToString(prefix = "[", postfix = "]") { "{\"category\":${quote(it.category)},\"level\":${quote(it.level)},\"message\":${quote(it.message)},\"createdAt\":${it.createdAt}}" }
                        writeResponse(out, 200, "application/json; charset=utf-8", body)
                    }
                    else -> writeResponse(out, 404, "application/json", "{\"error\":\"not_found\"}")
                }
            }
                out.flush()
            } catch (_: java.net.SocketTimeoutException) {
                // Idle too long or deadline exceeded: just drop the client.
            } catch (_: java.io.IOException) {
                // Client went away mid-request: nothing to answer.
            }
        }
    }

    private fun writeResponse(out: PrintWriter, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        out.print("HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        out.print(body)
        out.flush()
    }
    private fun defaultHtml(): String = "<html><head><meta name='viewport' content='width=device-width'></head><body><h1>Anvex Agent</h1><p>Authenticated read-only Agent status.</p><p>Use /api/status, /api/tasks and /api/logs.</p></body></html>"
    /**
     * M-02: the hand-rolled escaper only handled `\`, `"` and `\n`
     * (every other control character broke the JSON) and `take(500)`
     * could cut mid-escape. kotlinx.serialization escapes correctly;
     * the value is still capped at 500 chars, but BEFORE encoding.
     */
    private fun quote(value: String): String = Json.encodeToString(value.take(500))

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_CONCURRENT_CLIENTS = 8
        private const val MAX_LINE_BYTES = 8_192
        private const val CONNECTION_DEADLINE_MS = 30_000L
    }
}
