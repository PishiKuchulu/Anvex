package com.anvex.core.engine.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LspFramingTest {

    @Test
    fun `encode writes a content-length header`() {
        val frame = LspFraming.encode("{\"a\":1}")
        val text = String(frame, Charsets.US_ASCII)
        assertTrue(text.startsWith("Content-Length: 7\r\n\r\n"))
    }

    @Test
    fun `decoder round-trips one frame`() {
        val decoder = LspFraming.Decoder()
        val out = decoder.feed(LspFraming.encode("{\"jsonrpc\":\"2.0\"}"))
        assertEquals(listOf("{\"jsonrpc\":\"2.0\"}"), out)
    }

    @Test
    fun `decoder tolerates split frames`() {
        val decoder = LspFraming.Decoder()
        val frame = LspFraming.encode("{\"x\":\"abcdef\"}")
        assertTrue(decoder.feed(frame.copyOfRange(0, 10)).isEmpty())
        assertTrue(decoder.feed(frame.copyOfRange(10, frame.size - 2)).isEmpty())
        val out = decoder.feed(frame.copyOfRange(frame.size - 2, frame.size))
        assertEquals(listOf("{\"x\":\"abcdef\"}"), out)
    }

    @Test
    fun `decoder splits batched frames`() {
        val decoder = LspFraming.Decoder()
        val both = LspFraming.encode("{\"n\":1}") + LspFraming.encode("{\"n\":2}")
        assertEquals(listOf("{\"n\":1}", "{\"n\":2}"), decoder.feed(both))
    }

    @Test
    fun `utf8 bodies count bytes not chars`() {
        val json = "{\"t\":\"سلام\"}"
        val decoder = LspFraming.Decoder()
        assertEquals(listOf(json), decoder.feed(LspFraming.encode(json)))
    }
}
