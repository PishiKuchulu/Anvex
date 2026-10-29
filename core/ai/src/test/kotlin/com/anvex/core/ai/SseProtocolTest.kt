package com.anvex.core.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v1.1 §16 — SSE protocol hardening tests: fragmented chunks, multiple events
 * per HTTP chunk, CRLF endings, single-space stripping semantics, id/retry
 * fields, keep-alive comments, missing trailing blank line, HTTP error
 * mapping, and cancellation that actually cancels the HTTP call.
 */
class SseProtocolTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun collectSse(body: String): Pair<List<SseEvent>, AiError?> {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
        val failure = arrayOf<AiError?>(null)
        val request = Request.Builder().url(server.url("/stream")).get().build()

        val flow = Sse.stream(client, request) { err -> failure[0] = err }
        val events = flow.toList()
        return events to failure[0]
    }

    @Test
    fun `multiple sse events in one http chunk are all delivered`() = runTest {
        val body = "data: {\"a\":1}\n\ndata: {\"b\":2}\n\ndata: {\"c\":3}\n\n"
        val (events, err) = collectSse(body)
        assertEquals(null, err)
        assertEquals(3, events.size)
        assertEquals("{\"a\":1}", events[0].data)
        assertEquals("{\"c\":3}", events[2].data)
    }

    @Test
    fun `non-sse json body surfaces the provider error`() = runTest {
        val body = "{\"error\":{\"message\":\"credits exhausted\"}}"
        val (events, err) = collectSse(body)
        assertEquals(0, events.size)
        assertTrue(err != null)
        assertTrue(err!!.message.contains("credits exhausted"))
        assertEquals(AiError.Kind.PROTOCOL, err.kind)
    }

    @Test
    fun `http 402 maps to rate limit and retry-after is kept`() {
        val err = Sse.mapHttpError(402, "", 30L)
        assertEquals(AiError.Kind.RATE_LIMIT, err.kind)
        assertEquals(30L, err.retryAfterSeconds)
        assertEquals(AiError.Kind.NETWORK, Sse.mapHttpError(408, "").kind)
        assertEquals(AiError.Kind.PROTOCOL, Sse.mapHttpError(413, "").kind)
    }

    @Test
    fun `overlong sse line is rejected instead of buffered unbounded`() = runTest {
        val body = "data: " + "x".repeat(1_100_000) + "\n\n"
        val (events, err) = collectSse(body)
        assertEquals(0, events.size)
        assertTrue(err != null)
        assertTrue(err!!.message.contains("exceeds"))
    }

    @Test
    fun `event name is attached and reset per frame`() = runTest {
        val body = "event: message_start\ndata: {\"x\":1}\n\nevent: message_stop\ndata: {\"y\":2}\n\ndata: {\"z\":3}\n\n"
        val (events, _) = collectSse(body)
        assertEquals("message_start", events[0].event)
        assertEquals("message_stop", events[1].event)
        assertEquals(null, events[2].event) // reset after the previous frame
    }

    @Test
    fun `crlf line endings are handled`() = runTest {
        val body = "data: {\"a\":1}\r\n\r\ndata: {\"b\":2}\r\n\r\n"
        val (events, _) = collectSse(body)
        assertEquals(2, events.size)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `exactly one leading space is stripped from data - deeper indentation survives`() = runTest {
        // Per the SSE spec: remove a single leading U+0020 only.
        val body = "data:   {\"indented\":  true}\n\n"
        val (events, _) = collectSse(body)
        // "   {..." with one space stripped => "  {" — two spaces preserved.
        assertEquals("  {\"indented\":  true}", events[0].data)
    }

    @Test
    fun `data without space after colon is delivered verbatim`() = runTest {
        val body = "data:{\"n\":7}\n\n"
        val (events, _) = collectSse(body)
        assertEquals("{\"n\":7}", events[0].data)
    }

    @Test
    fun `multi-line data joins with newlines`() = runTest {
        val body = "data: line1\ndata: line2\n\n"
        val (events, _) = collectSse(body)
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun `id and retry fields are recognized without corrupting the event`() = runTest {
        val body = "id: 42\nretry: 5000\nevent: ping\ndata: {\"ok\":1}\n\n"
        val (events, _) = collectSse(body)
        assertEquals(1, events.size)
        assertEquals("42", events[0].id) // surfaced, not swallowed
        assertEquals("ping", events[0].event)
    }

    @Test
    fun `keep-alive comments are ignored`() = runTest {
        val body = ": heartbeat\n\ndata: {\"a\":1}\n\n: another comment\ndata: {\"b\":2}\n\n"
        val (events, _) = collectSse(body)
        assertEquals(2, events.size)
    }

    @Test
    fun `empty data field is still emitted as an sse event`() = runTest {
        val body = "event: ping\ndata:\n\n"
        val (events, _) = collectSse(body)
        assertEquals(1, events.size)
        assertEquals("ping", events[0].event)
        assertEquals("", events[0].data)
    }

    @Test
    fun `missing trailing blank line still flushes the final event`() = runTest {
        val body = "data: {\"last\":true}" // EOF right after data
        val (events, _) = collectSse(body)
        assertEquals(1, events.size)
        assertEquals("{\"last\":true}", events[0].data)
    }

    @Test
    fun `event after data in the same frame is honored (field order independence)`() = runTest {
        val body = "data: {\"a\":1}\nevent: late-name\n\n"
        val (events, _) = collectSse(body)
        assertEquals("late-name", events[0].event)
    }

    @Test
    fun `http 401 maps to auth error with provider message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Incorrect API key"}}""")
        )
        val (events, err) = run {
            val failure = arrayOf<AiError?>(null)
            val client = OkHttpClient()
            val request = Request.Builder().url(server.url("/stream")).get().build()
            Sse.stream(client, request) { e -> failure[0] = e }.toList()
            emptyList<SseEvent>() to failure[0]
        }
        assertEquals(0, events.size)
        assertEquals(AiError.Kind.AUTH, err?.kind)
        assertEquals("Incorrect API key", err?.message)
    }

    @Test
    fun `http 429 maps to rate limit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota"}}"""))
        val err = run {
            val failure = arrayOf<AiError?>(null)
            val request = Request.Builder().url(server.url("/s")).get().build()
            Sse.stream(client, request) { e -> failure[0] = e }.toList()
            failure[0]
        }
        assertEquals(AiError.Kind.RATE_LIMIT, err?.kind)
    }

    @Test
    fun `network disconnect maps to network error`() = runTest {
        // Server accepts the connection then closes it without a response:
        // the HTTP call itself fails with an IOException.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val err = run {
            val failure = arrayOf<AiError?>(null)
            val request = Request.Builder().url(server.url("/s")).get().build()
            Sse.stream(client, request) { e -> failure[0] = e }.toList()
            failure[0]
        }
        assertEquals(AiError.Kind.NETWORK, err?.kind)
    }

    @Test
    fun `canceling the collector cancels the http request - no further events`() = runTest {
        val body = "data: {\"a\":1}\n\ndata: {\"b\":2}\n\ndata: {\"c\":3}\n\n"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
                .throttleBody(4, 50, TimeUnit.MILLISECONDS) // slow drip
        )
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(server.url("/stream")).get().build()

        val collected = ArrayList<SseEvent>()
        val job = launch {
            Sse.stream(client, request) { }.collect { collected.add(it) }
        }
        // Collect at most the first event, then cancel like a user stopping a stream.
        withTimeout(5000) {
            while (collected.isEmpty()) yield()
        }
        job.cancel()
        job.join()
        val sizeAtCancel = collected.size
        // After cancellation no further events arrive (the call was cancelled).
        delay(300)
        assertEquals(sizeAtCancel, collected.size)
        assertTrue(sizeAtCancel in 1..2)
    }

    @Test
    fun `utf-8 multibyte content survives`() = runTest {
        val body = "data: {\"t\":\"你好 🚀\"}\n\n"
        val (events, _) = collectSse(body)
        assertEquals("{\"t\":\"你好 🚀\"}", events[0].data)
    }
}
