package com.anvex.core.android.runtime.pi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chat surface renders exactly what this reducer derives from Pi's event stream. */
class PiTranscriptReducerTest {

    private fun rec(json: String) = JSONObject(json)

    private fun assistantStart() = rec("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")

    private fun delta(text: String) =
        rec("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"$text"}}""")

    private fun assistantEnd(text: String, stopReason: String = "stop") = rec(
        """{"type":"message_end","message":{"role":"assistant","stopReason":"$stopReason","content":[{"type":"text","text":"$text"}]}}""",
    )

    @Test
    fun `deltas stream and message end finalizes`() {
        val r = PiTranscriptReducer()
        r.onEvent(assistantStart())
        r.onEvent(delta("Hel"))
        r.onEvent(delta("lo"))
        assertEquals("Hello", r.streamText)
        r.onEvent(assistantEnd("Hello"))
        assertEquals("Hello", r.finalText)
        assertEquals("", r.streamText)
    }

    @Test
    fun `second message replaces stream and final text`() {
        val r = PiTranscriptReducer()
        r.onEvent(assistantStart())
        r.onEvent(delta("first"))
        r.onEvent(assistantEnd("first"))
        r.onEvent(assistantStart())
        r.onEvent(delta("second"))
        assertEquals("second", r.streamText)
        assertEquals("first", r.finalText)
        r.onEvent(assistantEnd("second"))
        assertEquals("second", r.finalText)
    }

    @Test
    fun `user message events are ignored`() {
        val r = PiTranscriptReducer()
        r.onEvent(rec("""{"type":"message_start","message":{"role":"user","content":[]}}"""))
        r.onEvent(delta("nope"))
        assertEquals("", r.streamText)
    }

    @Test
    fun `tool activity drives the status line`() {
        val r = PiTranscriptReducer()
        r.onEvent(rec("""{"type":"tool_execution_start","toolName":"bash"}"""))
        assertEquals("Running bash…", r.statusLine)
        r.onEvent(rec("""{"type":"tool_execution_end","toolName":"bash"}"""))
        assertNull(r.statusLine)
    }

    @Test
    fun `error stop reason surfaces error text`() {
        val r = PiTranscriptReducer()
        r.onEvent(assistantStart())
        r.onEvent(
            rec("""{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"boom","content":[]}}"""),
        )
        assertEquals("boom", r.errorText)
    }
}
