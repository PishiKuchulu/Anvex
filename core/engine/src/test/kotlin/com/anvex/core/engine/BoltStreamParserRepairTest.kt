package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STEP 1 failing tests for the BoltStreamParser correctness repair (T1–T8),
 * plus the F6 streaming-copy budget test. Written against the pre-repair
 * parser: every test in this file is expected to FAIL before STEP 2 and pass
 * after it.
 */
class BoltStreamParserRepairTest {

    private class Recorder : ParserCallbacks {
        val events = ArrayList<String>()
        val closedActions = ArrayList<BoltAction>()
        val fileActions = ArrayList<BoltAction.FileAction>()
        val streamContents = ArrayList<String>()
        val normalized = ArrayList<Pair<String, String>>()
        var quickCalls = 0
        var quickActions: List<QuickAction> = emptyList()

        override fun onActionOpen(data: ActionCallbackData) {
            events.add("actionOpen:${data.actionId}")
        }

        override fun onActionStream(data: ActionCallbackData) {
            streamContents.add(data.action.content)
        }

        override fun onActionClose(data: ActionCallbackData) {
            events.add("actionClose:${data.actionId}")
            closedActions.add(data.action)
            if (data.action is BoltAction.FileAction) fileActions.add(data.action)
        }

        override fun onQuickActions(actions: List<QuickAction>) {
            quickCalls++
            quickActions = actions
        }

        override fun onContentNormalized(actionId: String, reason: String) {
            normalized.add(actionId to reason)
        }
    }

    private fun artifactWrap(actionTag: String, body: String): String =
        "<boltArtifact id=\"r\" title=\"R\">\n$actionTag$body</boltAction>\n</boltArtifact>"

    // ------------------------------------------------------------------ T1

    @Test
    fun `T1 quick-actions block never leaks into prose at any split point`() {
        val text = "Here is the plan.\n" +
            "<bolt-quick-actions>\n" +
            "<bolt-quick-action type=\"message\" message=\"Add tests\">Add tests</bolt-quick-action>\n" +
            "<bolt-quick-action type=\"message\" message=\"Ship it\">Ship it</bolt-quick-action>\n" +
            "</bolt-quick-actions>\n" +
            "Done."
        for (k in 0..text.length) {
            val rec = Recorder()
            val parser = BoltStreamParser(rec)
            val prose = parser.parse("t1", text.substring(0, k)) + parser.parse("t1", text)
            assertFalse("split k=$k leaked tag text into prose: <$prose>", prose.contains("<bolt"))
            assertTrue("split k=$k lost leading prose", prose.contains("Here is the plan."))
            assertTrue("split k=$k lost trailing prose", prose.contains("Done."))
            assertEquals("split k=$k quick-actions call count", 1, rec.quickCalls)
            assertEquals(
                "split k=$k quick-action items",
                listOf("Add tests", "Ship it"),
                rec.quickActions.map { it.label },
            )
            assertEquals(
                "split k=$k quick-action messages",
                listOf("Add tests", "Ship it"),
                rec.quickActions.map { it.message },
            )
        }
    }

    // ------------------------------------------------------------------ T2

    @Test
    fun `T2 file action accepts the path attribute`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "t2",
            artifactWrap("<boltAction type=\"file\" path=\"src/App.tsx\">", "export const x = 1;"),
        )
        assertEquals(1, rec.fileActions.size)
        assertEquals("src/App.tsx", rec.fileActions[0].filePath)
        assertEquals("export const x = 1;\n", rec.fileActions[0].content)
    }

    // ------------------------------------------------------------------ T3

    @Test
    fun `T3 single-quoted and bare attribute values parse like double-quoted`() {
        val variants = listOf(
            "<boltAction type=\"file\" filePath=\"a.txt\">",
            "<boltAction type='file' filePath='a.txt'>",
            "<boltAction type=file filePath=a.txt>",
        )
        for (tag in variants) {
            val rec = Recorder()
            val parser = BoltStreamParser(rec)
            parser.parse("t3", artifactWrap(tag, "body"))
            assertEquals("tag=$tag", 1, rec.fileActions.size)
            assertEquals("tag=$tag", "a.txt", rec.fileActions[0].filePath)
            assertEquals("tag=$tag", "body\n", rec.fileActions[0].content)
        }
    }

    // ------------------------------------------------------------------ T4

    @Test
    fun `T4 attribute names match on word boundaries and filePath wins over path`() {
        // filePath present alongside path and a data-path decoy: filePath wins.
        run {
            val rec = Recorder()
            val parser = BoltStreamParser(rec)
            parser.parse(
                "t4a",
                artifactWrap(
                    "<boltAction type=\"file\" data-path=\"wrong.txt\" filePath=\"right.txt\" path=\"alias.txt\">",
                    "x",
                ),
            )
            assertEquals(1, rec.fileActions.size)
            assertEquals("right.txt", rec.fileActions[0].filePath)
        }
        // No filePath: the path alias is used, and data-path is NOT read as path.
        run {
            val rec = Recorder()
            val parser = BoltStreamParser(rec)
            parser.parse(
                "t4b",
                artifactWrap("<boltAction type=\"file\" data-path=\"wrong.txt\" path=\"alias.txt\">", "x"),
            )
            assertEquals(1, rec.fileActions.size)
            assertEquals("alias.txt", rec.fileActions[0].filePath)
        }
        // data-path alone must not leak into a read action's path argument either.
        run {
            val rec = Recorder()
            val parser = BoltStreamParser(rec)
            parser.parse(
                "t4c",
                artifactWrap("<boltAction type=\"read\" data-path=\"wrong.txt\">", ""),
            )
            val tool = rec.closedActions.filterIsInstance<BoltAction.ToolAction>().single()
            assertEquals("", tool.argument)
        }
    }

    @Test
    fun `T4b file action with no path at all closes as a typed failure, never an empty-path write`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "t4d",
            artifactWrap("<boltAction type=\"file\" data-path=\"wrong.txt\">", "x"),
        )
        assertTrue("an empty-path FileAction must never reach onActionClose", rec.fileActions.isEmpty())
        val failure = rec.closedActions.filterIsInstance<BoltAction.UnknownAction>().single()
        assertEquals("file-missing-path", failure.type)
    }

    // ------------------------------------------------------------------ T5

    @Test
    fun `T5 tsx content with raw angle brackets and literal entities stays byte-for-byte verbatim`() {
        val body = "const ok = a < b; const el = <div>{\"&lt;\"}</div>;"
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "t5",
            artifactWrap("<boltAction type=\"file\" filePath=\"App.tsx\">", body),
        )
        assertEquals(1, rec.fileActions.size)
        // Verbatim, plus the single trailing-newline policy — nothing else.
        assertEquals(body + "\n", rec.fileActions[0].content)
        assertTrue(rec.normalized.isEmpty())
    }

    // ------------------------------------------------------------------ T6

    @Test
    fun `T6 python file keeps leading blank lines and trailing spaces`() {
        // Body: one separator newline after the tag, then two blank lines,
        // a line with trailing spaces, a last line, then surplus newlines.
        val raw = "\n\n\nx = 1  \ny = 2\n\n\n"
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "t6",
            artifactWrap("<boltAction type=\"file\" filePath=\"main.py\">", raw),
        )
        assertEquals(1, rec.fileActions.size)
        assertEquals("\n\nx = 1  \ny = 2\n", rec.fileActions[0].content)
    }

    // ------------------------------------------------------------------ T7

    @Test
    fun `T7 flush without an explicit flag never applies an unterminated file action`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val full = "<boltArtifact id=\"f\" title=\"F\">\n" +
            "<boltAction type=\"file\" filePath=\"half.txt\">\nline1\nline2\npartial"
        parser.parse("t7a", full)
        // Pack 01-AMEND C2 (declared expectation change): return contract is
        // now true ⟺ applied; this drop previously returned true.
        assertFalse(parser.flush("t7a", full))
        assertTrue("unterminated file action was applied", rec.closedActions.isEmpty())
    }

    @Test
    fun `T7b flush never applies an unterminated shell action`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val full = "<boltArtifact id=\"f\" title=\"F\">\n" +
            "<boltAction type=\"shell\">\nrm -rf /tmp/half"
        parser.parse("t7b", full)
        // Pack 01-AMEND C2 (declared expectation change): drops now return
        // false under the true ⟺ applied contract (previously true).
        assertFalse(parser.flush("t7b", full))
        assertTrue("unterminated shell action was applied", rec.closedActions.isEmpty())
        // Not even under the explicit opt-in (item 3 invariant; the former
        // salvage-policy half used the IncompletePolicy overload, deleted
        // under item 4/C3 — the opt-in is the strongest surviving shape).
        val rec2 = Recorder()
        val parser2 = BoltStreamParser(rec2)
        parser2.parse("t7b2", full.replace("t7b", "t7b2"))
        assertFalse(parser2.flush("t7b2", full, applyIncomplete = true))
        assertTrue("shell action was applied under the opt-in", rec2.closedActions.isEmpty())
    }

    // Pack 01-AMEND C3 (declared removal): T7c ("salvage policy applies
    // only the line-complete prefix of a file action") was REMOVED with the
    // IncompletePolicy type it tested. SALVAGE_FILE_PREFIX had zero
    // production call sites; the single surviving line-prefix
    // implementation is ChatSession.lineCompletePrefix, covered by
    // ChatSessionLineCompletePrefixTest in :core:android. Engine test
    // count delta from this removal: −1.

    // ------------------------------------------------------------------ T8

    @Test
    fun `T8 fully escaped html document is decoded once and the decode is reported`() {
        val body = "&lt;html&gt;&lt;body&gt;Hi &amp; bye&lt;/body&gt;&lt;/html&gt;"
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "t8",
            artifactWrap("<boltAction type=\"file\" filePath=\"index.html\">", body),
        )
        assertEquals(1, rec.fileActions.size)
        assertEquals("<html><body>Hi & bye</body></html>\n", rec.fileActions[0].content)
        assertEquals(listOf("0" to "fully-escaped-markup"), rec.normalized)
    }

    @Test
    fun `T8b html with raw tags is not decoded and not reported`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "t8b",
            artifactWrap(
                "<boltAction type=\"file\" filePath=\"index.html\">",
                "<html><body>Hi</body></html>",
            ),
        )
        assertEquals("<html><body>Hi</body></html>\n", rec.fileActions[0].content)
        assertTrue(rec.normalized.isEmpty())
    }

    // ------------------------------------------------------------------ PERF

    @Test
    fun `PERF streaming a 200KB file in 1KB chunks copies less than 5x the file size`() {
        val line = "a".repeat(99) + "\n"
        val content = line.repeat(2048) // 204,800 chars
        val full = "<boltArtifact id=\"big\" title=\"Big\">\n" +
            "<boltAction type=\"file\" filePath=\"big.txt\">" + content +
            "</boltAction>\n</boltArtifact>"
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        var acc = ""
        while (acc.length < full.length) {
            acc = full.substring(0, minOf(acc.length + 1024, full.length))
            parser.parse("perf", acc)
        }
        assertEquals(1, rec.fileActions.size)
        assertEquals(content, rec.fileActions[0].content)
        // The frozen streaming contract held throughout: stream events carry
        // the accumulated content (the last one precedes the closing chunk,
        // so it is within one chunk of complete).
        assertTrue(rec.streamContents.isNotEmpty())
        assertTrue(rec.streamContents.last().length > content.length - 2048)
        val copied = parser.totalContentCharsCopied
        // Item 5: the two numbers, labeled. processingCopiedChars is the
        // asserted budget metric; streamPayloadChars (total chars of
        // payloads handed to onActionStream — inherently quadratic, since
        // each payload is the full accumulated content) is REPORTED ONLY,
        // never asserted.
        println("PERF processingCopiedChars=$copied streamPayloadChars=${parser.totalStreamPayloadChars} fileChars=${content.length}")
        assertTrue("copy counter is not instrumented", copied > 0)
        assertTrue(
            "copied $copied chars for a ${content.length}-char file (budget ${5L * content.length})",
            copied < 5L * content.length,
        )
    }
}
