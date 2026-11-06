package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatcher side of chunked file writes: an append action merges onto
 * the existing file through the same journaled write path as a full
 * write, refuses missing/protected targets, and never routes through
 * the SEARCH/REPLACE patch engine.
 */
class DispatcherAppendTest {

    @Test
    fun `append extends an existing file`() {
        val fs = FakeFs()
        fs.files["notes.md"] = "part one\n"
        val dispatcher = ActionDispatcher(fs)
        val result = dispatcher.applyFileAction(
            BoltAction.FileAction("part two\n", "notes.md", append = true)
        )
        assertTrue(result.success)
        assertEquals("part one\npart two\n", fs.files["notes.md"])
        assertTrue(result.output!!.contains("Appended 9 characters"))
    }

    @Test
    fun `append strips a repeated whole-line seam`() {
        val fs = FakeFs()
        fs.files["report.md"] = "first section done\nsecond section done\n"
        val dispatcher = ActionDispatcher(fs)
        val result = dispatcher.applyFileAction(
            BoltAction.FileAction("second section done\nthird section done\n", "report.md", append = true)
        )
        assertTrue(result.success)
        assertEquals(
            "first section done\nsecond section done\nthird section done\n",
            fs.files["report.md"],
        )
    }

    @Test
    fun `append to a missing file fails without creating it`() {
        val fs = FakeFs()
        val dispatcher = ActionDispatcher(fs)
        val result = dispatcher.applyFileAction(
            BoltAction.FileAction("orphan chunk\n", "nope.md", append = true)
        )
        assertFalse(result.success)
        assertTrue(result.error!!.contains("already exist"))
        assertFalse(fs.files.containsKey("nope.md"))
    }

    @Test
    fun `append to a protected path is refused`() {
        val fs = FakeFs()
        fs.files[".anvex/state.txt"] = "seed\n"
        val dispatcher = ActionDispatcher(fs)
        val result = dispatcher.applyFileAction(
            BoltAction.FileAction("more\n", ".anvex/state.txt", append = true)
        )
        assertFalse(result.success)
        assertEquals("seed\n", fs.files[".anvex/state.txt"])
    }

    @Test
    fun `append chunk starting with the patch marker is stored as body`() {
        val fs = FakeFs()
        fs.files["doc.md"] = "intro\n"
        val dispatcher = ActionDispatcher(fs)
        val patchLike = "<<<< SEARCH\nintro\n====\nchanged\n>>>> REPLACE\n"
        val result = dispatcher.applyFileAction(
            BoltAction.FileAction(patchLike, "doc.md", append = true)
        )
        assertTrue(result.success)
        // Stored verbatim as appended body — NOT applied as a patch.
        assertEquals("intro\n" + patchLike, fs.files["doc.md"])
    }
}
