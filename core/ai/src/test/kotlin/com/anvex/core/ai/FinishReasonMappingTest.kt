package com.anvex.core.ai

import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pack 02, STEP 1 (R1–R3, R7) — the canonical finish-reason contract,
 * pinned per client against MockWebServer fixtures:
 *
 *   StreamEvent.Completed.finishReason is ALWAYS one of
 *   "stop" | "length" | "tool_call" | "content_filter" | "other",
 *   or null when the provider reported none — never a raw provider
 *   string. Raw vocabularies ("end_turn", "STOP", "max_tokens",
 *   "MAX_TOKENS", "tool_calls") leaking through is the defect that made
 *   ChatSession classify healthy Gemini/Claude replies as truncated.
 */
class FinishReasonMappingTest {

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

    private fun enqueueSse(body: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
    }

    private fun openAiClient() = OpenAiCompatibleClient(
        ProviderRuntimeConfig(
            ProviderConfig(
                id = "p1", name = "test", kind = ProviderKind.CUSTOM_OPENAI,
                baseUrl = server.url("/v1").toString(), model = "gpt-4o-mini",
            ),
            "test-key",
        )
    )

    private fun anthropicClient() = AnthropicClient(
        ProviderRuntimeConfig(
            ProviderConfig(
                id = "p2", name = "anth", kind = ProviderKind.CUSTOM_ANTHROPIC,
                baseUrl = server.url("").toString(), model = "claude-sonnet-4-5",
            ),
            "test-key",
        )
    )

    private fun geminiClient() = GeminiClient(
        ProviderRuntimeConfig(
            ProviderConfig(
                id = "p3", name = "gem", kind = ProviderKind.GEMINI,
                baseUrl = server.url("").toString(), model = "gemini-2.5-pro",
            ),
            "test-key",
        )
    )

    private suspend fun completedReason(events: List<StreamEvent>): String? {
        assertTrue(
            "expected a Completed event, got: $events",
            events.any { it is StreamEvent.Completed },
        )
        return events.filterIsInstance<StreamEvent.Completed>().first().finishReason
    }

    // ------------------------------------------------------------------ R1

    @Test
    fun `R1 openai normal end maps to stop`() = runTest {
        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        val events = openAiClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("stop", completedReason(events))
    }

    @Test
    fun `R1 anthropic end_turn maps to stop`() = runTest {
        enqueueSse(
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        )
        val events = anthropicClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("stop", completedReason(events))
    }

    @Test
    fun `R1 gemini STOP maps to stop`() = runTest {
        enqueueSse(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]},\"finishReason\":\"STOP\"}]}\n\n"
        )
        val events = geminiClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("stop", completedReason(events))
    }

    // ------------------------------------------------------------------ R2

    @Test
    fun `R2 openai length maps to length`() = runTest {
        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        val events = openAiClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("length", completedReason(events))
    }

    @Test
    fun `R2 anthropic max_tokens maps to length`() = runTest {
        enqueueSse(
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":5}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        )
        val events = anthropicClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("length", completedReason(events))
    }

    @Test
    fun `R2 gemini MAX_TOKENS maps to length`() = runTest {
        enqueueSse(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"MAX_TOKENS\"}]}\n\n"
        )
        val events = geminiClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("length", completedReason(events))
    }

    // ------------------------------------------------------------------ R3

    @Test
    fun `R3 gemini propagates the candidate real finishReason instead of the hardcoded STOP`() = runTest {
        // Today GeminiClient emits Completed("STOP") whenever any token
        // streamed — the candidate's real finishReason is read only for
        // the safety check and then discarded, so a MAX_TOKENS truncation
        // is invisible and no continuation can ever fire. This fixture's
        // ONLY correct canonical outcome is "length".
        enqueueSse(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial ans\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"wer\"}]},\"finishReason\":\"MAX_TOKENS\"}]}\n\n"
        )
        val events = geminiClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("length", completedReason(events))
    }

    // ------------------------------------------------- mapping completeness

    @Test
    fun `openai tool_calls and content_filter map to canonical values`() = runTest {
        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        assertEquals("tool_call", completedReason(openAiClient().streamChat(ChatRequest("sys", listOf())).toList()))

        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"content_filter\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        assertEquals("content_filter", completedReason(openAiClient().streamChat(ChatRequest("sys", listOf())).toList()))

        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"some_future_reason\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        assertEquals("other", completedReason(openAiClient().streamChat(ChatRequest("sys", listOf())).toList()))
    }

    @Test
    fun `anthropic tool_use and stop_sequence map to canonical values`() = runTest {
        enqueueSse(
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":2}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        )
        assertEquals("tool_call", completedReason(anthropicClient().streamChat(ChatRequest("sys", listOf())).toList()))

        enqueueSse(
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"stop_sequence\"},\"usage\":{\"output_tokens\":2}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        )
        assertEquals("stop", completedReason(anthropicClient().streamChat(ChatRequest("sys", listOf())).toList()))
    }

    @Test
    fun `gemini candidate SAFETY finishReason becomes content_filter completion not a protocol failure`() = runTest {
        // A candidate-level finishReason (distinct from a promptFeedback
        // block, which stays a failure): the contract maps it to
        // Completed("content_filter") so ChatSession can apply its
        // truncation handling instead of a hard error.
        enqueueSse(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]},\"finishReason\":\"SAFETY\"}]}\n\n"
        )
        val events = geminiClient().streamChat(ChatRequest("sys", listOf())).toList()
        assertEquals("content_filter", completedReason(events))
    }

    // ------------------------------------------------------------------ R7

    @Test
    fun `R7 gemini thinking-only reply completes as an empty answer not a failure`() = runTest {
        // All parts are thought=true and the candidate finishes STOP:
        // the reasoning must be delivered, and the outcome must be a
        // Completed("stop") with no answer tokens — a distinct
        // empty-answer outcome — NOT Failed("Stream ended without
        // content"), which is today's behavior.
        enqueueSse(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"let me think\",\"thought\":true}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" more thought\",\"thought\":true}]},\"finishReason\":\"STOP\"}]}\n\n"
        )
        val events = geminiClient().streamChat(ChatRequest("sys", listOf())).toList()
        val reasoning = events.filterIsInstance<StreamEvent.Reasoning>()
        assertEquals(2, reasoning.size)
        assertEquals("let me think", reasoning[0].text)
        assertTrue("thinking-only reply must not produce answer tokens", events.none { it is StreamEvent.Token })
        assertTrue(
            "thinking-only reply must not be a failure: $events",
            events.none { it is StreamEvent.Failed },
        )
        assertEquals("stop", completedReason(events))
    }
}
