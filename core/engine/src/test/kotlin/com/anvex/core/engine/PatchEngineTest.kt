package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.4 (architect feature 2): SEARCH/REPLACE targeted-edit patch blocks.
 */
class PatchEngineTest {

    private val source = """
        fun main() {
            val count = 0
            println("Count: " + count)
        }
    """.trimIndent()

    private fun patch(search: String, replace: String) =
        "<<<< SEARCH\n$search\n====\n$replace\n>>>> REPLACE"

    @Test
    fun `single block applies to existing content`() {
        val p = patch("val count = 0", "val count = 1")
        assertTrue(PatchEngine.looksLikePatch(p))
        val outcome = PatchEngine.apply(source, p)
        assertTrue(outcome is PatchEngine.PatchOutcome.Applied)
        outcome as PatchEngine.PatchOutcome.Applied
        assertEquals(1, outcome.blocksApplied)
        assertTrue(outcome.newContent.contains("val count = 1"))
        assertFalse(outcome.newContent.contains("val count = 0"))
        // untouched lines survive
        assertTrue(outcome.newContent.contains("""println("Count: " + count)"""))
    }

    @Test
    fun `multiple blocks apply in order`() {
        val p = patch("val count = 0", "val count = 42") + "\n" +
            patch("""println("Count: " + count)""", """println("Answer: " + count)""")
        val outcome = PatchEngine.apply(source, p) as? PatchEngine.PatchOutcome.Applied ?: error("expected Applied")
        assertEquals(2, outcome.blocksApplied)
        assertTrue(outcome.newContent.contains("val count = 42"))
        assertTrue(outcome.newContent.contains("""println("Answer: " + count)"""))
    }

    @Test
    fun `missing search text fails without modifying the file`() {
        val p = patch("this line does not exist anywhere", "x")
        val outcome = PatchEngine.apply(source, p)
        assertTrue(outcome is PatchEngine.PatchOutcome.Failed)
        outcome as PatchEngine.PatchOutcome.Failed
        assertTrue(outcome.reason.contains("SEARCH text was not found"))
        assertTrue(outcome.reason.contains("The file was NOT modified"))
    }

    @Test
    fun `multi-line search spans exact lines`() {
        // The SEARCH text must mirror the source EXACTLY — including indentation.
        val p = patch(
            "    val count = 0\n    println(\"Count: \" + count)",
            "    val count = 7\n    println(\"Answer: \" + count)",
        )
        val outcome = PatchEngine.apply(source, p) as? PatchEngine.PatchOutcome.Applied ?: error("expected Applied")
        assertTrue(outcome.newContent.contains("val count = 7"))
        assertFalse(outcome.newContent.contains("Count:"))
    }

    @Test
    fun `malformed patch reports the structural error`() {
        val broken = "<<<< SEARCH\nsomething\n====\nreplacement" // missing >>> REPLACE
        val outcome = PatchEngine.apply(source, broken)
        assertTrue(outcome is PatchEngine.PatchOutcome.Failed)
        assertTrue((outcome as PatchEngine.PatchOutcome.Failed).reason.contains("missing '>>>> REPLACE'"))
    }

    @Test
    fun `regular content is not treated as a patch`() {
        assertFalse(PatchEngine.looksLikePatch(source))
        // A document that merely MENTIONS the markers mid-text is not a patch.
        assertFalse(PatchEngine.looksLikePatch("note: use <<<< SEARCH / ==== / >>>> REPLACE blocks for targeted edits"))
        // Marker at the very start IS a patch candidate (by contract).
        assertTrue(PatchEngine.looksLikePatch("<<<< SEARCH\nold\n====\nnew\n>>>> REPLACE"))
    }

    @Test
    fun `lenient match ignores surrounding blank lines`() {
        val p = "<<<< SEARCH\n\n   val count = 0   \n\n====\n   val count = 9   \n\n>>>> REPLACE"
        val outcome = PatchEngine.apply(source, p) as? PatchEngine.PatchOutcome.Applied ?: error("expected Applied")
        assertTrue(outcome.newContent.contains("val count = 9"))
    }
}
