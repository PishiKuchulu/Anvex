package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import com.anvex.core.model.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Thinking-level wiring in the OpenAI-compatible client: the mapped
 * effort value per provider kind, the DeepSeek thinking toggle, and
 * the retry-without-effort fallback on HTTP 400.
 */
class OpenAiEffortTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(kind: ProviderKind, model: String = "test-model") = ProviderRuntimeConfig(
        ProviderConfig(
            id = "p1", name = "test", kind = kind,
            baseUrl = server.url("/v1").toString(), model = model,
        ),
        "test-key",
    )

    private fun sseBody(token: String = "Hello") = "data: {\"choices\":[{\"delta\":{\"content\":\"$token\"}}]}\n\ndata: [DONE]\n\n"

    @Test
    fun `openai normal sends medium not normal`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody()))
        val client = OpenAiCompatibleClient(config(ProviderKind.OPENAI))
        client.streamChat(ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "hi")), thinkingLevel = ThinkingLevel.NORMAL)).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
        assertFalse(body.contains("\"reasoning_effort\":\"normal\""))
    }

    @Test
    fun `openai max sends max and high sends high`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody()))
        OpenAiCompatibleClient(config(ProviderKind.OPENAI))
            .streamChat(ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "hi")), thinkingLevel = ThinkingLevel.MAX)).toList()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"reasoning_effort\":\"max\""))

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody()))
        OpenAiCompatibleClient(config(ProviderKind.OPENAI))
            .streamChat(ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "hi")), thinkingLevel = ThinkingLevel.HIGH)).toList()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"reasoning_effort\":\"high\""))
    }

    @Test
    fun `deepseek sends thinking toggle plus capped effort`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody()))
        OpenAiCompatibleClient(config(ProviderKind.DEEPSEEK))
            .streamChat(ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "hi")), thinkingLevel = ThinkingLevel.MAX)).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"max\""))
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
    }

    @Test
    fun `groq xhigh is capped at high`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody()))
        OpenAiCompatibleClient(config(ProviderKind.GROQ))
            .streamChat(ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "hi")), thinkingLevel = ThinkingLevel.XHIGH)).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        assertFalse(body.contains("xhigh"))
    }

    @Test
    fun `http 400 on effort retries once without it and still streams`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"reasoning_effort not supported\"}}"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody()))
        val client = OpenAiCompatibleClient(config(ProviderKind.CUSTOM_OPENAI))
        val events = client.streamChat(ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "hi")), thinkingLevel = ThinkingLevel.HIGH)).toList()
        assertTrue(events.any { it is StreamEvent.Token && it.text == "Hello" })
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("reasoning_effort"))
        assertFalse(second.contains("reasoning_effort"))
        assertFalse(second.contains("\"thinking\""))
    }

    @Test
    fun `non 400 failure is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        val client = OpenAiCompatibleClient(config(ProviderKind.CUSTOM_OPENAI))
        val events = client.streamChat(ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "hi")), thinkingLevel = ThinkingLevel.HIGH)).toList()
        assertTrue(events.any { it is StreamEvent.Failed })
        assertEquals(1, server.requestCount)
    }
}
