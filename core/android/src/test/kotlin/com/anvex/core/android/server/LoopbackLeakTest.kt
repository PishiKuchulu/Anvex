package com.anvex.core.android.server

import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.10.2 (second review #1, CRITICAL): the loopback servers kept
 * the session cookie in a ThreadLocal that was never cleared. After
 * the app's WebView opened a token URL once, a token-less request on
 * the same pooled thread (e.g. a plain POST, answered 405 before any
 * auth check) came back WITH `Set-Cookie: <real token>`.
 *
 * The harness pool has exactly 2 threads, and ThreadPoolExecutor
 * creates a fresh worker while workerCount < core size — so the first
 * two token requests land on the two distinct pool threads. After
 * those, every further request reuses a thread that has already
 * served a token URL: before the fix, every attacker request leaked.
 */
class LoopbackLeakTest {

    private fun rawRequest(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val bytes = socket.getInputStream().readBytes()
            return bytes.toString(Charsets.ISO_8859_1)
        }
    }

    @Test
    fun `token never leaks into token-less error responses`() {
        val server = HarnessWebServer(assetLoader = { ByteArray(0) })
        server.start()
        try {
            val token = server.accessToken
            val port = server.port
            assertTrue(port > 0)
            // Prime BOTH pool threads with a token URL (each sets its
            // thread's cookie, as the real WebView does on first load).
            repeat(2) {
                val ok = rawRequest(
                    port,
                    "GET /t/$token/harness/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
                )
                assertTrue("token request must succeed: $ok", ok.startsWith("HTTP/1.1 200"))
                assertTrue("token request must set the cookie", ok.contains("Set-Cookie:"))
            }
            // The reviewer's attack: no Host, no token, wrong method.
            repeat(3) {
                val leaked = rawRequest(port, "POST / HTTP/1.1\r\n\r\n")
                assertTrue("attacker request must be rejected: $leaked", leaked.startsWith("HTTP/1.1 405"))
                assertFalse("token leaked into error response: $leaked", leaked.contains("Set-Cookie:"))
                assertFalse("token leaked into error response: $leaked", leaked.contains(token))
            }
            // And a token-less GET must 403 without a cookie either.
            val forbidden = rawRequest(
                port,
                "GET /harness/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            )
            assertTrue("token-less GET must be forbidden: $forbidden", forbidden.startsWith("HTTP/1.1 403"))
            assertFalse("token leaked into 403: $forbidden", forbidden.contains("Set-Cookie:"))
        } finally {
            // Daemon threads only; the accept thread may log via the
            // android stub on shutdown, which is harmless in tests.
            runCatching { server.stop() }
        }
    }
}
