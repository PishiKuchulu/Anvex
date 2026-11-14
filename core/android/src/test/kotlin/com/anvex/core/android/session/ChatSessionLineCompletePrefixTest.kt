package com.anvex.core.android.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pack 01-AMEND, C3 — dedicated tests for the ONE line-prefix
 * implementation that remains after IncompletePolicy's deletion:
 * ChatSession.lineCompletePrefix (the parser's duplicate salvage policy
 * is gone; this function is the single source of the rule).
 */
class ChatSessionLineCompletePrefixTest {

    @Test
    fun `no newline yields empty`() {
        assertEquals("", ChatSession.lineCompletePrefix("partial line, never terminated"))
        assertEquals("", ChatSession.lineCompletePrefix(""))
    }

    @Test
    fun `multi-line returns exactly through the last newline`() {
        assertEquals("one\ntwo\nthree\n", ChatSession.lineCompletePrefix("one\ntwo\nthree\n"))
        assertEquals("one\ntwo\n", ChatSession.lineCompletePrefix("one\ntwo\nthree"))
    }

    @Test
    fun `trailing partial line is discarded`() {
        assertEquals("complete\n", ChatSession.lineCompletePrefix("complete\npartial-tail"))
        assertEquals("\n", ChatSession.lineCompletePrefix("\npartial"))
    }
}
