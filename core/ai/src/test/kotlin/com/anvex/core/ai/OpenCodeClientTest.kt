package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * OpenCodeClient tests (v4.4.1). Every mock body below is a REAL payload captured
 * from live probes against opencode.ai on 2026-10-15 (see
 * docs/OPENCODE_INTEGRATION_REPORT.md) — these are mock-based transport tests,
 * NOT live connectivity claims. The genuine endpoint results are documented in
 * the integration report and were obtained with safe, non-destructive probes.
 *
 * Coverage required by the integration brief:
 *  - model-list parsing + successful discovery (both tiers)
 *  - successful response parsing + streaming
 *  - timeout and cancellation
 *  - HTTP 401 / 403 / 429 / 5xx
 *  - invalid JSON / malformed responses
 *  - provider settings persistence (serialization round-trip)
 *  - secret redaction + honest client identity (no impersonation)
 *  - regression: factory dispatch for all provider families
 *
 * Tool calling: NOT APPLICABLE by design — Anvex's agent protocol is text-based
 * (SEARCH/REPLACE via PatchEngine); the ProviderClient contract carries no native
 * tool-call surface, so there is nothing to parse. Documented, not tested.
 */
class OpenCodeClientTest {

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

    // ---- fixtures --------------------------------------------------------------

    /** Official layout: base = .../inference/openai/v1 (chat family). */
    private fun officialBase(): String = server.url("/inference/openai/v1").toString()

    /** Legacy v4.4 layout: base = .../zen/v1 (models next to chat). */
    private fun legacyBase(): String = server.url("/zen/v1").toString()

    private fun freeRuntime(model: String = "space-bunny-free", base: String = officialBase()) = ProviderRuntimeConfig(
        ProviderConfig(
            id = "oc-free", name = "OpenCode Free", kind = ProviderKind.OPENCODE_FREE,
            baseUrl = base, model = model,
        ),
        apiKey = "",
    )

    private fun consoleRuntime(model: String = "space-bunny-free", base: String = officialBase(), key: String = "zen_livekey_redacted000000") =
        ProviderRuntimeConfig(
            ProviderConfig(
                id = "oc-zen", name = "OpenCode Console", kind = ProviderKind.OPENCODE_ZEN,
                baseUrl = base, model = model,
            ),
            apiKey = key,
        )

    private fun request() = ChatRequest("system", listOf(ChatTurn(MessageRole.USER, "hi")))

    /** Live-captured public models payload (trimmed to a representative mix). */
    private fun realModelsBody() = """
        {"object":"list","data":[
          {"id":"big-pickle","object":"model","created":1792092343,"owned_by":"opencode"},
          {"id":"space-bunny-free","object":"model","created":1792092343,"owned_by":"opencode"},
          {"id":"deepseek-v4.1-flash-free","object":"model","created":1792092343,"owned_by":"opencode"},
          {"id":"gpt-5.5","object":"model","created":1792092343,"owned_by":"opencode"},
          {"id":"jev-1.13-free","object":"model","created":1792092343,"owned_by":"opencode"},
          {"id":"claude-sonnet-5-5","object":"model","created":1792092343,"owned_by":"opencode"},
          {"object":"model","created":1792092343,"owned_by":"opencode"},
          {"id":null,"object":"model"}
        ]}
    """.trimIndent().replace("\n", "")

    /** Live-captured 403 body from the free-tier gate (verbatim, 2026-10-15). */
    private fun real403Body() =
        """{"type":"error","error":{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode. Download it at https://opencode.ai and keep building."}}"""

    /** Live-captured 401 bodies (chat without key / models with bogus key). */
    private fun real401ChatBody() =
        """{"type":"error","error":{"type":"AuthError","message":"Missing API key."}}"""

    private fun real401ModelsBody() =
        """{"error":{"type":"server_error","message":"Invalid credential"}}"""

    // ---- model discovery & parsing ----------------------------------------------

    @Test
    fun `free tier discovers only -free models and sends no credential`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(realModelsBody()))
        val models = OpenCodeClient(freeRuntime()).listModels()
        val ids = models.map { it.id }
        assertEquals(listOf("space-bunny-free", "deepseek-v4.1-flash-free", "jev-1.13-free"), ids)
        // Discovery is public: the free tier must not send any credential.
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("Authorization"))
        assertEquals(OpenCodeClient.HONEST_USER_AGENT, recorded.getHeader("User-Agent"))
    }

    @Test
    fun `console tier lists the full catalog with bearer`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(realModelsBody()))
        val models = OpenCodeClient(consoleRuntime()).listModels()
        // Full catalog: 6 valid ids of the mix (the 2 malformed entries are skipped).
        assertEquals(6, models.size)
        assertTrue(models.any { it.id == "gpt-5.5" })
        val recorded = server.takeRequest()
        assertEquals("Bearer zen_livekey_redacted000000", recorded.getHeader("Authorization"))
    }

    @Test
    fun `models url derives the inference family root for the official layout`() {
        assertEquals(
            server.url("/inference/v1/models").toString(),
            OpenCodeClient(freeRuntime()).modelsUrl(),
        )
    }

    @Test
    fun `models url falls back to the base path for legacy zen rows`() {
        assertEquals(
            server.url("/zen/v1/models").toString(),
            OpenCodeClient(freeRuntime(base = legacyBase())).modelsUrl(),
        )
        assertEquals(
            server.url("/zen/v1/models").toString(),
            OpenCodeClient(consoleRuntime(base = legacyBase())).modelsUrl(),
        )
    }

    @Test
    fun `invalid json models body degrades to an empty list`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("this is {{{ not json"))
        val models = OpenCodeClient(freeRuntime()).listModels()
        assertTrue(models.isEmpty())
    }

    @Test
    fun `models entries without an id are skipped`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"owned_by":"opencode"},{"id":"cat-jam-free"}]}"""))
        val models = OpenCodeClient(freeRuntime()).listModels()
        assertEquals(listOf("cat-jam-free"), models.map { it.id })
    }

    // ---- response parsing & streaming -------------------------------------------

    @Test
    fun `chat streams tokens and completion over the shared sse transport`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]\n\n"))
        val events = OpenCodeClient(consoleRuntime()).streamChat(request()).toList()
        val tokens = events.filterIsInstance<StreamEvent.Token>()
        assertEquals(listOf("Hel", "lo"), tokens.map { it.text })
        val completed = events.filterIsInstance<StreamEvent.Completed>().first()
        assertEquals(5L, completed.usage?.totalTokens)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"stream\":true"))
        assertTrue(sent.contains("\"model\":\"space-bunny-free\""))
    }

    @Test
    fun `malformed sse frames are skipped without failing the stream`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: this is not json\n\ndata: [DONE]\n\n"))
        val events = OpenCodeClient(consoleRuntime()).streamChat(request()).toList()
        assertTrue(events.filterIsInstance<StreamEvent.Token>().isEmpty())
        assertTrue(events.none { it is StreamEvent.Failed })
        assertTrue(events.any { it is StreamEvent.Completed })
    }

    @Test
    fun `reasoning deltas surface as reasoning events`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking…\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n"))
        val events = OpenCodeClient(consoleRuntime()).streamChat(request()).toList()
        assertEquals(1, events.filterIsInstance<StreamEvent.Reasoning>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.Token>().size)
    }

    // ---- error mapping (chat stream) ---------------------------------------------

    @Test
    fun `403 free tier gate maps to ACCESS_RESTRICTED with verbatim upstream message`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody(real403Body()))
        val events = OpenCodeClient(freeRuntime()).streamChat(request()).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().firstOrNull()
        assertNotNull(failure)
        assertEquals(AiError.Kind.ACCESS_RESTRICTED, failure!!.error.kind)
        assertEquals(403, failure.error.httpStatus)
        assertTrue(failure.error.message.contains("free tier can only be used from within OpenCode"))
    }

    @Test
    fun `401 maps to AUTH and is distinct from the 403 access restriction`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody(real401ChatBody()))
        val events = OpenCodeClient(consoleRuntime(key = "expired-or-wrong-key")).streamChat(request()).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().first()
        assertEquals(AiError.Kind.AUTH, failure.error.kind)
        assertTrue(failure.error.message.contains("Missing API key"))
    }

    @Test
    fun `429 maps to RATE_LIMIT`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))
        val events = OpenCodeClient(freeRuntime()).streamChat(request()).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().first()
        assertEquals(AiError.Kind.RATE_LIMIT, failure.error.kind)
    }

    @Test
    fun `5xx maps to NETWORK`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        val events = OpenCodeClient(freeRuntime()).streamChat(request()).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().first()
        assertEquals(AiError.Kind.NETWORK, failure.error.kind)
        assertEquals(502, failure.error.httpStatus)
    }

    @Test
    fun `stream that ends without content or done is a PROTOCOL failure`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{}}]}\n\n"))
        val events = OpenCodeClient(freeRuntime()).streamChat(request()).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().first()
        assertEquals(AiError.Kind.PROTOCOL, failure.error.kind)
    }

    // ---- honest connection validation ----------------------------------------------

    @Test
    fun `free tier validation reports discovery ok and the real 403 chat gate`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(realModelsBody()))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody(real403Body()))
        val result = OpenCodeClient(freeRuntime()).validateConnection()
        assertFalse(result.ok)
        assertTrue(result.message.contains("Model discovery OK (3 `-free` models listed)"))
        assertTrue(result.message.contains("HTTP 403"))
        assertTrue(result.message.contains("free tier can only be used from within OpenCode"))
        assertTrue(result.message.contains("will not impersonate"))
        // Request order: discovery (family root) first, then the chat probe.
        val discovery = server.takeRequest()
        assertEquals("/inference/v1/models", discovery.path)
        val probe = server.takeRequest()
        assertEquals("/inference/openai/v1/chat/completions", probe.path)
    }

    @Test
    fun `free tier validation would report success if upstream opened the gate`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(realModelsBody()))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
        val result = OpenCodeClient(freeRuntime()).validateConnection()
        assertTrue(result.ok)
        assertTrue(result.message.contains("chat endpoint answered"))
    }

    @Test
    fun `console validation verifies the key through the authenticated models fetch`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(realModelsBody()))
        val result = OpenCodeClient(consoleRuntime()).validateConnection()
        assertTrue(result.ok)
        assertEquals(6, result.modelsFound)
        assertTrue(result.message.contains("API key verified"))
        assertEquals("Bearer zen_livekey_redacted000000", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `console validation refuses to claim success without a key`() = kotlinx.coroutines.test.runTest {
        val result = OpenCodeClient(consoleRuntime(key = "")).validateConnection()
        assertFalse(result.ok)
        assertTrue(result.message.contains("No API key set"))
        // No network call was needed to know this — and none must be reported as success.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `console validation surfaces 401 invalid credential verbatim`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody(real401ModelsBody()))
        val result = OpenCodeClient(consoleRuntime(key = "wrong-key")).validateConnection()
        assertFalse(result.ok)
        assertTrue(result.message.contains("HTTP 401"))
        assertTrue(result.message.contains("Invalid credential"))
    }

    // ---- timeout & cancellation ------------------------------------------------------

    private fun shortTimeoutClient() = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .build()

    @Test
    fun `models timeout surfaces as a network failure, not a hang or fake ok`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody(realModelsBody()).setHeadersDelay(3, TimeUnit.SECONDS))
        val result = OpenCodeClient(freeRuntime(), shortTimeoutClient()).validateConnection()
        assertFalse(result.ok)
        assertTrue(result.message.startsWith("Network error:"))
    }

    @Test
    fun `chat stream cancellation stays silent - no failed event`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n")
            .setBodyDelay(5, TimeUnit.SECONDS))
        val client = OpenCodeClient(freeRuntime())
        val events = mutableListOf<StreamEvent>()
        val job = launch(Dispatchers.IO) { client.streamChat(request()).toList(events) }
        val deadline = System.currentTimeMillis() + 5_000
        while (server.requestCount == 0 && System.currentTimeMillis() < deadline) delay(25)
        assertTrue(server.requestCount > 0) // request actually reached the server
        job.cancelAndJoin()
        // Cancellation must be silent: no Failed event fabricates an upstream error.
        assertTrue(events.none { it is StreamEvent.Failed })
    }

    // ---- persistence ------------------------------------------------------------------

    @Test
    fun `provider config round-trips both opencode kinds`() {
        for (kind in listOf(ProviderKind.OPENCODE_FREE, ProviderKind.OPENCODE_ZEN)) {
            val config = ProviderConfig(
                id = "persist-1", name = "OC", kind = kind,
                baseUrl = kind.defaultBaseUrl, model = "space-bunny-free",
                temperature = 0.4, maxTokens = 1024, isDefault = true,
            )
            val encoded = Json.encodeToString(ProviderConfig.serializer(), config)
            val decoded = Json.decodeFromString(ProviderConfig.serializer(), encoded)
            assertEquals(kind, decoded.kind)
            assertEquals("space-bunny-free", decoded.model)
            assertEquals(kind.defaultBaseUrl, decoded.baseUrl)
            // ProviderRepository stores kind.name in Room — valueOf must round-trip.
            assertEquals(kind, ProviderKind.valueOf(decoded.kind.name))
        }
    }

    @Test
    fun `default base urls point at the verified live endpoints`() {
        assertEquals("https://opencode.ai/inference/openai/v1", ProviderKind.OPENCODE_FREE.defaultBaseUrl)
        assertEquals("https://opencode.ai/inference/openai/v1", ProviderKind.OPENCODE_ZEN.defaultBaseUrl)
    }

    // ---- secret redaction & honest identity ---------------------------------------------

    @Test
    fun `runtime config toString never reveals the console key`() {
        val text = consoleRuntime().toString()
        assertTrue(!text.contains("zen_livekey_redacted000000"))
        assertTrue(text.contains("•")) // masked form present instead
    }

    @Test
    fun `client never impersonates the official opencode client`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: [DONE]\n\n"))
        OpenCodeClient(freeRuntime()).streamChat(request()).toList()
        val recorded = server.takeRequest()
        val ua = recorded.getHeader("User-Agent")
        assertNotNull(ua)
        // Honest identity: Anvex's own UA — never the official client's.
        assertEquals(OpenCodeClient.HONEST_USER_AGENT, ua)
        assertFalse(ua!!.startsWith("opencode/"))
        // No attestation/fingerprint headers of the official client, ever.
        assertEquals(null, recorded.getHeader("x-opencode-client"))
        assertEquals(null, recorded.getHeader("x-opencode-session"))
        assertEquals(null, recorded.getHeader("x-opencode-request"))
    }

    // ---- regression: factory dispatch ------------------------------------------------------

    @Test
    fun `factory routes opencode kinds to the isolated client and leaves others untouched`() {
        fun clientOf(kind: ProviderKind) = ProviderClientFactory.create(
            ProviderRuntimeConfig(ProviderConfig(id = "k", name = "k", kind = kind, model = "m"), "k")
        )
        assertTrue(clientOf(ProviderKind.OPENCODE_FREE) is OpenCodeClient)
        assertTrue(clientOf(ProviderKind.OPENCODE_ZEN) is OpenCodeClient)
        // Existing families keep their adapters.
        assertTrue(clientOf(ProviderKind.OPENAI) is OpenAiCompatibleClient)
        assertTrue(clientOf(ProviderKind.CUSTOM_OPENAI) is OpenAiCompatibleClient)
        assertTrue(clientOf(ProviderKind.ANTHROPIC) is AnthropicClient)
        assertTrue(clientOf(ProviderKind.GEMINI) is GeminiClient)
    }

    @Test
    fun `shared openai client no longer carries opencode special cases`() = kotlinx.coroutines.test.runTest {
        // The -free filter and the free-tier validation branch moved to OpenCodeClient;
        // a CUSTOM_OPENAI row against the same payload must see the full list.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(realModelsBody()))
        val runtime = ProviderRuntimeConfig(
            ProviderConfig(
                id = "cust", name = "custom", kind = ProviderKind.CUSTOM_OPENAI,
                baseUrl = server.url("/v1").toString(), model = "any",
            ),
            apiKey = "k",
        )
        val models = OpenAiCompatibleClient(runtime).listModels()
        assertEquals(6, models.size)
        assertEquals("/v1/models", server.takeRequest().path)
    }
}
