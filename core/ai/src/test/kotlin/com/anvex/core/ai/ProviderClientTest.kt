package com.anvex.core.ai

import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Provider adapter tests against a local MockWebServer exercising the exact
 * wire protocols: OpenAI-compatible SSE, Anthropic SSE, Gemini SSE, model
 * listing, and error mapping (brief §31).
 */
class ProviderClientTest {

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

    private fun openAiConfig(model: String = "gpt-4o-mini") = ProviderRuntimeConfig(
        ProviderConfig(
            id = "p1", name = "test", kind = ProviderKind.CUSTOM_OPENAI,
            baseUrl = server.url("/v1").toString(), model = model,
        ),
        apiKey = "test-key-" + "x".repeat(20),
    )

    @Test
    fun `openai compatible streams tokens via sse`() = runTest {
        val body = """
            data: {"choices":[{"delta":{"content":"Hello"}}]}
            
            data: {"choices":[{"delta":{"content":" world"}}]}
            
            data: [DONE]
            
        """.trimIndent().replace("\n\n", "\n\n")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val client = OpenAiCompatibleClient(openAiConfig())
        val events = client.streamChat(ChatRequest("system", listOf(ChatTurn(com.anvex.core.model.MessageRole.USER, "hi"))))
            .toList()

        val tokens = events.filterIsInstance<StreamEvent.Token>()
        assertEquals(2, tokens.size)
        assertEquals("Hello", tokens[0].text)
        assertEquals(" world", tokens[1].text)
        assertTrue(events.any { it is StreamEvent.Completed })

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key-" + "x".repeat(20), request.getHeader("Authorization"))
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"stream\":true"))
        assertTrue(sent.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(sent.contains("\"role\":\"system\""))
    }

    @Test
    fun `custom openai endpoint receives mapped reasoning_effort`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")
        )
        val client = OpenAiCompatibleClient(openAiConfig())
        client.streamChat(ChatRequest("s", emptyList())).toList()
        val body = server.takeRequest().body.readUtf8()
        // The old gate dropped the knob for arbitrary endpoints; it is now
        // sent mapped (default NORMAL -> "medium") and stripped only when
        // the endpoint rejects it with HTTP 400 (see OpenAiEffortTest),
        // so an unsupported knob can never break a chat.
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
    }

    @Test
    fun `openai auth error maps to AUTH kind`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Invalid API key"}}""")
        )
        val client = OpenAiCompatibleClient(openAiConfig())
        val events = client.streamChat(ChatRequest("s", listOf())).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().firstOrNull()
        assertNotNull(failure)
        assertEquals(AiError.Kind.AUTH, failure!!.error.kind)
        assertTrue(failure.error.message.contains("Invalid API key"))
    }

    @Test
    fun `openai listModels parses data array`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini","display_name":"GPT 4o mini"}]}""")
        )
        val client = OpenAiCompatibleClient(openAiConfig())
        val models = client.listModels()
        assertEquals(2, models.size)
        assertEquals("gpt-4o", models[0].id)
        assertEquals("GPT 4o mini", models[1].displayName)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `anthropic streams content_block_delta events`() = runTest {
        val body = buildString {
            append("event: message_start\n")
            append("""data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}""")
            append("\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}""")
            append("\n\n")
            append("event: message_delta\n")
            append("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""")
            append("\n\n")
            append("event: message_stop\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val runtime = ProviderRuntimeConfig(
            ProviderConfig(
                id = "p2", name = "anth", kind = ProviderKind.CUSTOM_ANTHROPIC,
                baseUrl = server.url("").toString(), model = "claude-3-5-sonnet-latest",
            ),
            apiKey = "test-anth-key",
        )
        val client = AnthropicClient(runtime)
        val events = client.streamChat(ChatRequest("sys", listOf())).toList()

        val tokens = events.filterIsInstance<StreamEvent.Token>()
        assertEquals(1, tokens.size)
        assertEquals("Hi", tokens[0].text)
        val completed = events.filterIsInstance<StreamEvent.Completed>().first()
        assertEquals(10L, completed.usage?.promptTokens)
        assertEquals(5L, completed.usage?.completionTokens)

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("test-anth-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"stream\":true"))
        assertTrue(sent.contains("\"system\":\"sys\""))
    }

    @Test
    fun `gemini streams generateContent sse chunks`() = runTest {
        val body = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hola"}]}}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}
            
            data: {"candidates":[{"content":{"parts":[{"text":" amigo"}]}}]}
            
        """.trimIndent().replace("\n\n\n", "\n\n")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val runtime = ProviderRuntimeConfig(
            ProviderConfig(
                id = "p3", name = "gem", kind = ProviderKind.GEMINI,
                baseUrl = server.url("").toString(), model = "gemini-1.5-flash",
            ),
            apiKey = "test-gem-key",
        )
        val client = GeminiClient(runtime)
        val events = client.streamChat(ChatRequest("sys", listOf())).toList()

        val tokens = events.filterIsInstance<StreamEvent.Token>()
        assertEquals(2, tokens.size)
        assertEquals("Hola", tokens[0].text)
        assertEquals(" amigo", tokens[1].text)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains(":streamGenerateContent"))
        assertTrue(request.path!!.contains("alt=sse"))
        assertEquals("test-gem-key", request.getHeader("x-goog-api-key"))
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("systemInstruction"))
    }

    @Test
    fun `factory dispatches by protocol`() {
        val openAi = ProviderClientFactory.create(
            ProviderRuntimeConfig(
                ProviderConfig(id = "a", name = "a", kind = ProviderKind.OPENAI, model = "m"),
                "k"
            )
        )
        val anthropic = ProviderClientFactory.create(
            ProviderRuntimeConfig(
                ProviderConfig(id = "b", name = "b", kind = ProviderKind.ANTHROPIC, model = "m"),
                "k"
            )
        )
        val gemini = ProviderClientFactory.create(
            ProviderRuntimeConfig(
                ProviderConfig(id = "c", name = "c", kind = ProviderKind.GEMINI, model = "m"),
                "k"
            )
        )
        assertTrue(openAi is OpenAiCompatibleClient)
        assertTrue(anthropic is AnthropicClient)
        assertTrue(gemini is GeminiClient)
    }

    @Test
    fun `default http client disables replay retries and has bounded call timeout`() {
        val http = defaultHttpClient()
        assertEquals(30 * 60 * 1000, http.callTimeoutMillis)
        assertTrue(!http.retryOnConnectionFailure)
    }

    @Test
    fun `maskApiKey never reveals full key`() {
        val masked = maskApiKey("sk-abcdefghijklmnop1234")
        assertTrue(masked.startsWith("sk-abc"))
        assertTrue(masked.endsWith("1234"))
        assertTrue(!masked.contains("defghijkl"))
        assertEquals("••••••••••", maskApiKey("1234567890"))
    }

    @Test
    fun `empty openai api key does not send authorization header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{\"data\":[]}"))
        val runtime = ProviderRuntimeConfig(
            ProviderConfig(id = "empty", name = "local", kind = ProviderKind.CUSTOM_OPENAI, baseUrl = server.url("/v1").toString(), model = "local"),
            apiKey = "",
        )
        OpenAiCompatibleClient(runtime).listModels()
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `runtime config toString does not reveal key or header values`() {
        val runtime = ProviderRuntimeConfig(
            ProviderConfig(id = "p", name = "secret-provider", kind = ProviderKind.OPENAI, model = "m", extraHeaders = mapOf("X-Secret" to "header-secret")),
            apiKey = "sk-super-secret-value",
        )
        val text = runtime.toString()
        assertTrue(!text.contains("sk-super-secret-value"))
        assertTrue(!text.contains("header-secret"))
        assertTrue(text.contains("X-Secret"))
    }

    @Test
    fun `attachment is attached to the last user turn by index`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
        val client = OpenAiCompatibleClient(openAiConfig())
        val turns = listOf(
            com.anvex.core.ai.ChatTurn(com.anvex.core.model.MessageRole.USER, "first"),
            com.anvex.core.ai.ChatTurn(com.anvex.core.model.MessageRole.ASSISTANT, "answer"),
            com.anvex.core.ai.ChatTurn(com.anvex.core.model.MessageRole.USER, "second"),
        )
        client.streamChat(ChatRequest("sys", turns, attachments = listOf(ChatAttachment("note.txt", "text/plain", text = "attached")))).toList()
        val sent = server.takeRequest().body.readUtf8()
        // v4.0 fix: the request body is serializer-built (B-3), so the newline inside
        // the JSON string appears as the escaped \\n sequence on the wire.
        assertTrue(sent.contains("\\n[Attachment: note.txt]\\nattached"))
        val secondPos = sent.indexOf("second")
        val attachmentPos = sent.indexOf("[Attachment: note.txt]")
        assertTrue(secondPos >= 0 && attachmentPos > secondPos)
    }

    @Test
    fun `gemini base url with v1beta does not duplicate path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n"))
        val runtime = ProviderRuntimeConfig(
            ProviderConfig(id = "g", name = "gem", kind = ProviderKind.GEMINI, baseUrl = server.url("/v1beta").toString(), model = "gemini-2.5-pro"),
            apiKey = "k",
        )
        GeminiClient(runtime).streamChat(ChatRequest("sys", emptyList())).toList()
        val path = server.takeRequest().requestUrl?.encodedPath
        assertEquals("/v1beta/models/gemini-2.5-pro:streamGenerateContent", path)
    }

    @Test
    fun `gemini safety block becomes protocol failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n\n"))
        val runtime = ProviderRuntimeConfig(ProviderConfig(id = "g", name = "gem", kind = ProviderKind.GEMINI, baseUrl = server.url("").toString(), model = "gemini-2.5-pro"), "k")
        val events = GeminiClient(runtime).streamChat(ChatRequest("sys", emptyList())).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().first()
        assertTrue(failure.error.message.contains("SAFETY"))
    }

    @Test
    fun `error message extraction from provider bodies`() {
        val msg = Sse.extractErrorMessage("""{"error":{"message":"quota exceeded"}}""")
        assertEquals("quota exceeded", msg)
    }
    @Test
    fun `gemini capability detection is conservative and variant aware`() {
        assertTrue(GeminiCapabilities.supportsThinkingBudget("gemini-2.5-flash-lite-preview-09-2025"))
        assertTrue(GeminiCapabilities.supportsThinkingBudget("models/gemini-3-pro-preview"))
        assertTrue(GeminiCapabilities.supportsThinkingLevel("gemini-3-pro-preview"))
        assertTrue(!GeminiCapabilities.supportsThinkingBudget("gemini-1.5-flash"))
        assertTrue(!GeminiCapabilities.supportsThinkingBudget("gemini-4-preview"))
    }

}
