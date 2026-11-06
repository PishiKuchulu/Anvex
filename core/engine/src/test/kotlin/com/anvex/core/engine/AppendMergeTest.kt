package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seam-merge rules for chunked (append) file writes: only an unambiguous
 * whole-line repetition at the seam is stripped — anything weaker stays,
 * because a duplicated line is untidy but stripped content is corruption.
 */
class AppendMergeTest {

    @Test
    fun `no overlap concatenates plainly`() {
        val existing = "line one\nline two\n"
        val chunk = "line three\nline four\n"
        assertEquals(existing + chunk, AppendMerge.merge(existing, chunk))
    }

    @Test
    fun `repeated whole lines at the seam are stripped`() {
        val existing = "alpha line here\nbeta line here\ngamma line here\n"
        val chunk = "beta line here\ngamma line here\ndelta line here\n"
        assertEquals(
            "alpha line here\nbeta line here\ngamma line here\ndelta line here\n",
            AppendMerge.merge(existing, chunk),
        )
    }

    @Test
    fun `single repeated long line is stripped`() {
        val line = "this is a sufficiently long repeated line\n"
        val existing = "start\n$line"
        val chunk = line + "next line\n"
        assertEquals("start\n" + line + "next line\n", AppendMerge.merge(existing, chunk))
    }

    @Test
    fun `short repetition below the minimum is kept`() {
        val existing = "data\nshort\n"
        val chunk = "short\nmore data follows here\n"
        // "short\n" is 6 chars — far below MIN_OVERLAP, so it is treated as
        // coincidence and the chunk is appended verbatim.
        assertEquals(existing + chunk, AppendMerge.merge(existing, chunk))
    }

    @Test
    fun `partial line repetition is kept`() {
        val existing = "the quick brown fox jumps over\n"
        // The chunk repeats the tail MID-line and continues it — not a
        // whole-line seam, so nothing may be stripped.
        val chunk = "brown fox jumps over the lazy dog\n"
        assertEquals(existing + chunk, AppendMerge.merge(existing, chunk))
    }

    @Test
    fun `repetition that continues mid-line is kept`() {
        val existing = "header\nabcdefghijklmnopqrstuvwxyz0123456789\n"
        // Same line repeated but continued with more text before the
        // newline: stripping it would eat real content.
        val chunk = "abcdefghijklmnopqrstuvwxyz0123456789 continued\n"
        assertEquals(existing + chunk, AppendMerge.merge(existing, chunk))
    }

    @Test
    fun `chunk fully contained in the tail adds nothing`() {
        val existing = "one two three four\nfive six seven eight\n"
        val chunk = "five six seven eight\n"
        assertEquals(existing, AppendMerge.merge(existing, chunk))
    }

    @Test
    fun `empty sides concatenate trivially`() {
        assertEquals("abc", AppendMerge.merge("", "abc"))
        assertEquals("abc", AppendMerge.merge("abc", ""))
        assertEquals("", AppendMerge.merge("", ""))
    }
}
