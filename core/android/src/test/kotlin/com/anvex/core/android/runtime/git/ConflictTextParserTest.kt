package com.anvex.core.android.runtime.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictTextParserTest {

    private val sample = """
        line one
        <<<<<<< HEAD
        ours a
        ours b
        =======
        theirs a
        >>>>>>> feature
        line last
    """.trimIndent()

    @Test
    fun parsesOneConflict() {
        val segments = ConflictTextParser.parse(sample)
        assertEquals(3, segments.size)
        val conflict = segments[1] as ConflictSegment.Conflict
        assertEquals("HEAD", conflict.oursLabel)
        assertEquals(listOf("ours a", "ours b"), conflict.ours)
        assertEquals("feature", conflict.theirsLabel)
        assertEquals(listOf("theirs a"), conflict.theirs)
        assertEquals(null, conflict.base)
    }

    @Test
    fun parsesDiff3Base() {
        val text = "<<<<<<< HEAD\no\n||||||| base\nb\n=======\nt\n>>>>>>> x"
        val conflict = ConflictTextParser.parse(text).single() as ConflictSegment.Conflict
        assertEquals(listOf("b"), conflict.base)
    }

    @Test
    fun rendersPicks() {
        val segments = ConflictTextParser.parse(sample)
        val ours = ConflictTextParser.render(segments, listOf(ConflictTextParser.Pick.OURS))
        assertEquals("line one\nours a\nours b\nline last", ours)
        val both = ConflictTextParser.render(segments, listOf(ConflictTextParser.Pick.OURS_THEN_THEIRS))
        assertEquals("line one\nours a\nours b\ntheirs a\nline last", both)
    }

    @Test
    fun plainTextRoundTrips() {
        val text = "a\nb\nc"
        assertFalse(ConflictTextParser.hasConflicts(text))
        val segments = ConflictTextParser.parse(text)
        assertEquals(text, ConflictTextParser.render(segments, emptyList()))
        assertTrue(ConflictTextParser.hasConflicts(sample))
    }
}
