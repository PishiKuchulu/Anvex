package com.anvex.core.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** H-05: an undecryptable key must survive saves; a failed encryption must fail loudly. */
class McpConfigSaveTest {

    private fun server(id: String, key: String? = null, undecryptable: Boolean = false) =
        McpServerConfig(id = id, name = id, url = "https://$id.example", apiKey = key, keyUndecryptable = undecryptable)

    @Test
    fun `undecryptable key survives a save of another server`() {
        val view = listOf(server("a", undecryptable = true), server("b", key = "plain-b"))
        val out = mergeMcpConfigsForSave(view, mapOf("a" to "ENC-A", "b" to "ENC-B")) { "ENC($it)" }
        assertEquals("ENC-A", out.first { it.id == "a" }.apiKey)
        assertEquals("ENC(plain-b)", out.first { it.id == "b" }.apiKey)
        assertFalse(out.first { it.id == "b" }.keyUndecryptable)
    }

    @Test
    fun `failed encryption throws instead of storing null`() {
        try {
            mergeMcpConfigsForSave(listOf(server("b", key = "plain")), emptyMap()) { null }
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("b"))
        }
    }

    @Test
    fun `blank key clears and undecryptable without stored blob clears flag`() {
        val view = listOf(server("b", key = "  "), server("c", undecryptable = true))
        val out = mergeMcpConfigsForSave(view, emptyMap()) { "ENC($it)" }
        assertNull(out.first { it.id == "b" }.apiKey)
        assertFalse(out.first { it.id == "c" }.keyUndecryptable)
        assertNull(out.first { it.id == "c" }.apiKey)
    }
}
