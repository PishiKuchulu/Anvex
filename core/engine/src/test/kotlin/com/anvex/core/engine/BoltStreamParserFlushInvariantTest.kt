package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pack 01-AMEND, item 3 — the flush() safety invariant, answered with a
 * test: an unterminated SHELL or START action is NEVER applied (no
 * onActionClose) under ANY call shape of flush(), including the explicit
 * applyIncomplete opt-in. The opt-in exists to recover a FILE action
 * whose closing tag was omitted at a clean stop; it must never become a
 * way to run a possibly-truncated command.
 *
 * flush() returns true exactly when an action was applied (Pack 01-AMEND,
 * C2 — the return contract was redefined so call sites can narrate
 * precisely; every drop returns false).
 *
 * The shapes enumerated are the complete post-amend surface:
 *   flush(id) · flush(id, Boolean) · flush(id, fullText) ·
 *   flush(id, fullText, Boolean)
 * (The IncompletePolicy overload was deleted under item 4/C3 — it had no
 * production call sites.)
 */
class BoltStreamParserFlushInvariantTest {

    private class Recorder : ParserCallbacks {
        val closed = mutableListOf<BoltAction>()
        override fun onActionClose(data: ActionCallbackData) { closed += data.action }
    }

    private fun parserWithOpenAction(actionTag: String, body: String): Pair<BoltStreamParser, Recorder> {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val full = "<boltArtifact id=\"inv\" title=\"Inv\">\n$actionTag$body"
        parser.parse("inv", full)
        return parser to rec
    }

    private val shapes: List<Pair<String, (BoltStreamParser, String) -> Boolean>> = listOf(
        "flush(id)" to { p, full -> p.flush("inv") },
        "flush(id, applyIncomplete=true)" to { p, _ -> p.flush("inv", applyIncomplete = true) },
        "flush(id, applyIncomplete=false)" to { p, _ -> p.flush("inv", applyIncomplete = false) },
        "flush(id, fullText)" to { p, full -> p.flush("inv", full) },
        "flush(id, fullText, applyIncomplete=true)" to { p, full -> p.flush("inv", full, applyIncomplete = true) },
        "flush(id, fullText, applyIncomplete=false)" to { p, full -> p.flush("inv", full, applyIncomplete = false) },
    )

    private fun fullTextFor(actionTag: String, body: String) =
        "<boltArtifact id=\"inv\" title=\"Inv\">\n$actionTag$body"

    @Test
    fun `unterminated shell action is never applied under any flush shape`() {
        val tag = "<boltAction type=\"shell\">"
        val body = "rm -rf build"
        // Pass 1 — the safety invariant itself: no shape may CLOSE the
        // action (application == onActionClose), including the opt-in.
        for ((shapeName, flush) in shapes) {
            val (parser, rec) = parserWithOpenAction(tag, body)
            flush(parser, fullTextFor(tag, body))
            assertEquals("shell must not close under $shapeName", 0, rec.closed.size)
        }
        // Pass 2 — the C2 return contract: every drop reports false.
        for ((shapeName, flush) in shapes) {
            val (parser, _) = parserWithOpenAction(tag, body)
            assertFalse("flush must report no application under $shapeName", flush(parser, fullTextFor(tag, body)))
        }
    }

    @Test
    fun `unterminated start action is never applied under any flush shape`() {
        val tag = "<boltAction type=\"start\">"
        val body = "npm run dev"
        // Pass 1 — the safety invariant itself (see the shell test).
        for ((shapeName, flush) in shapes) {
            val (parser, rec) = parserWithOpenAction(tag, body)
            flush(parser, fullTextFor(tag, body))
            assertEquals("start must not close under $shapeName", 0, rec.closed.size)
        }
        // Pass 2 — the C2 return contract: every drop reports false.
        for ((shapeName, flush) in shapes) {
            val (parser, _) = parserWithOpenAction(tag, body)
            assertFalse("flush must report no application under $shapeName", flush(parser, fullTextFor(tag, body)))
        }
    }

    @Test
    fun `two-arg opt-in applies the FULL accumulated content of a chunked unterminated file action`() {
        // Reviewer pin (post-AMEND, pre-merge): ProviderAgentPlanner calls
        // the TWO-ARG overload flush(id, applyIncomplete = true), which
        // delegates with fullText = "". The positive control below asserts
        // applied CONTENT only under the three-arg overload; its two-arg
        // block asserts the close COUNT only. This test closes that gap:
        // the content arrives across several cumulative parse() calls
        // (real streaming), the stream ends mid-body, and the two-arg
        // opt-in must apply the full accumulated text recovered from the
        // parser's own buffer — never an empty file.
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val full = "<boltArtifact id=\"inv\" title=\"Inv\">\n" +
            "<boltAction type=\"file\" filePath=\"chunked.txt\">\n" +
            "line one\nline two\npartial tail"
        // Cumulative chunks, split inside the header, the tag, and the body.
        var acc = ""
        for (chunk in listOf(full.substring(0, 20), full.substring(20, 55), full.substring(55, 90), full.substring(90))) {
            acc += chunk
            parser.parse("inv", acc)
        }
        assertEquals(0, rec.closed.size)
        assertTrue(parser.flush("inv", applyIncomplete = true))
        assertEquals(1, rec.closed.size)
        val applied = rec.closed.single() as BoltAction.FileAction
        assertEquals("chunked.txt", applied.filePath)
        // Close normalization: one leading separator newline stripped,
        // trailing newlines collapsed to exactly one.
        assertEquals("line one\nline two\npartial tail\n", applied.content)
    }

    @Test
    fun `positive control - the explicit opt-in does apply an unterminated file action`() {
        // Guards against a vacuous invariant (an opt-in that applies
        // nothing at all would pass the two tests above).
        val tag = "<boltAction type=\"file\" filePath=\"ok.txt\">"
        val body = "\nhello"
        run {
            val (parser, rec) = parserWithOpenAction(tag, body)
            assertTrue(parser.flush("inv", fullTextFor(tag, body), applyIncomplete = true))
            assertEquals(1, rec.closed.size)
            assertEquals("hello\n", (rec.closed.single() as BoltAction.FileAction).content)
        }
        run {
            val (parser, rec) = parserWithOpenAction(tag, body)
            assertTrue(parser.flush("inv", applyIncomplete = true))
            assertEquals(1, rec.closed.size)
        }
        // …while every non-opt-in shape still drops the same file action
        // (no close, and the C2 contract reports the drop as false).
        for ((shapeName, flush) in shapes) {
            if ("applyIncomplete=true" in shapeName) continue
            val (parser, rec) = parserWithOpenAction(tag, body)
            val applied = flush(parser, fullTextFor(tag, body))
            assertEquals("file must not close under $shapeName", 0, rec.closed.size)
            assertFalse("flush must report no application under $shapeName", applied)
        }
    }
}
