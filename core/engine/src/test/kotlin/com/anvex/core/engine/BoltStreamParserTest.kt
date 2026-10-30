package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Kotlin port of bolt.diy's StreamingMessageParser — including
 * chunk-boundary splits, markdown-fence cleanup, escaped tags, and quick actions.
 */
class BoltStreamParserTest {

    private class Recorder : ParserCallbacks {
        val events = ArrayList<String>()
        var lastFileAction: BoltAction.FileAction? = null
        var quickActions: List<QuickAction> = emptyList()
        var openedArtifact: AnvexArtifactData? = null
        var closedArtifact: AnvexArtifactData? = null

        override fun onArtifactOpen(artifact: AnvexArtifactData) {
            openedArtifact = artifact; events.add("artifactOpen:${artifact.title}")
        }

        override fun onArtifactClose(artifact: AnvexArtifactData) {
            closedArtifact = artifact; events.add("artifactClose")
        }

        override fun onActionOpen(data: ActionCallbackData) {
            events.add("actionOpen:${data.actionId}:${data.action.typeName()}")
        }

        override fun onActionStream(data: ActionCallbackData) {
            events.add("actionStream:${data.actionId}")
        }

        override fun onActionClose(data: ActionCallbackData) {
            events.add("actionClose:${data.actionId}")
            if (data.action is BoltAction.FileAction) lastFileAction = data.action
        }

        override fun onQuickActions(actions: List<QuickAction>) {
            quickActions = actions
        }
    }

    @Test
    fun `parses full artifact with file and shell actions`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = """
            Sure! Let me build that.
            <boltArtifact id="todo-app" title="Todo App">
            <boltAction type="file" filePath="index.html">
            <html>hello</html>
            </boltAction>
            <boltAction type="shell">
            npm install
            </boltAction>
            <boltAction type="start">
            npm run dev
            </boltAction>
            </boltArtifact>
            Done!
        """.trimIndent().replace("            ", "")

        val prose = parser.parse("m1", input)

        assertEquals("Todo App", rec.openedArtifact?.title)
        assertEquals(3, rec.events.count { it.startsWith("actionOpen") })
        assertEquals(3, rec.events.count { it.startsWith("actionClose") })
        assertTrue(rec.closedArtifact != null)
        assertEquals("<html>hello</html>\n", rec.lastFileAction?.content)
        // artifact body is stripped from prose; leading text and trailing text remain
        assertTrue(prose.contains("Sure! Let me build that."))
        assertTrue(probeContainsDone(prose))
        assertTrue(!prose.contains("<html>"))
    }

    private fun probeContainsDone(prose: String) = prose.contains("Done!")

    @Test
    fun `handles chunk boundaries splitting tags`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val full = """
            <boltArtifact id="x" title="X">
            <boltAction type="file" filePath="a.js">console.log(1);</boltAction>
            </boltArtifact>
        """.trimIndent().replace("            ", "")

        // The parser contract (same as upstream) is: parse(messageId, FULL accumulated text).
        // Feed accumulated text in awkward chunk sizes to prove tolerance.
        var prose = ""
        var len = 0
        while (len < full.length) {
            len = minOf(len + 7, full.length)
            prose += parser.parse("m2", full.substring(0, len))
        }
        parser.flush("m2")

        assertEquals("X", rec.openedArtifact?.title)
        assertEquals("console.log(1);\n", rec.lastFileAction?.content)
    }

    @Test
    fun `takeOpenAction returns open action without applying it`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "mX",
            "<boltArtifact id=\"x\" title=\"X\">\n" +
                "<boltAction type=\"file\" filePath=\"half.kt\">\nfun half(",
        )
        val open = parser.takeOpenAction("mX")
        assertTrue(open is BoltAction.FileAction)
        assertEquals("half.kt", (open as BoltAction.FileAction).filePath)
        assertTrue(rec.events.none { it.startsWith("actionClose") })
        // Already cleared: a later flush must not emit it either.
        parser.flush("mX")
        assertTrue(rec.events.none { it.startsWith("actionClose") })
    }

    @Test
    fun `file action strips markdown fence and unescapes tags`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = """
            <boltArtifact id="y" title="Y">
            <boltAction type="file" filePath="App.jsx">
            ```jsx
            const T = () => <div>&lt;b&gt;hi&lt;/b&gt;</div>;
            ```
            </boltAction>
            </boltArtifact>
        """.trimIndent().replace("            ", "")

        parser.parse("m3", input)

        val content = rec.lastFileAction?.content ?: ""
        assertTrue(content.startsWith("const T"))
        assertTrue(content.contains("<b>hi</b>"))
        assertTrue(!content.contains("```"))
        assertTrue(content.endsWith("\n"))
    }

    @Test
    fun `markdown files keep fences and escaping`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = """
            <boltArtifact id="z" title="Z">
            <boltAction type="file" filePath="README.md">
            # Title
            ```js
            code
            ```
            </boltAction>
            </boltArtifact>
        """.trimIndent().replace("            ", "")

        parser.parse("m4", input)

        val content = rec.lastFileAction?.content ?: ""
        assertTrue(content.contains("```"))
        assertTrue(content.contains("# Title"))
    }

    @Test
    fun `streams file content progressively`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        var full = "<boltArtifact id=\"s\" title=\"S\">"
        parser.parse("m5", full)
        full += "<boltAction type=\"file\" filePath=\"x.css\">body {"
        parser.parse("m5", full)
        assertEquals(1, rec.events.count { it.startsWith("actionStream") })
        full += " color: red; }</boltAction></boltArtifact>"
        parser.parse("m5", full)
        assertEquals("body { color: red; }\n", rec.lastFileAction?.content)
    }

    @Test
    fun `quick actions are surfaced and stripped`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = """
            Answer text.
            <bolt-quick-actions>
            <bolt-quick-action type="message" message="Add dark mode">Make it dark</bolt-quick-action>
            </bolt-quick-actions>
            More.
        """.trimIndent().replace("            ", "")

        val prose = parser.parse("m6", input)

        assertEquals(1, rec.quickActions.size)
        assertEquals("Make it dark", rec.quickActions.first().label)
        assertEquals("Add dark mode", rec.quickActions.first().message)
        assertTrue(!prose.contains("bolt-quick-action"))
        assertTrue(prose.contains("Answer text."))
    }

    @Test
    fun `plain text with html-like tags passes through`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val prose = parser.parse("m7", "Use <div> and <b>bold</b> normally.")
        assertEquals("Use <div> and <b>bold</b> normally.", prose)
        assertNull(rec.openedArtifact)
    }

    @Test
    fun `unknown action type is preserved not thrown`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse(
            "m8",
            """<boltArtifact id="u" title="U"><boltAction type="supabase" operation="query">select 1;</boltAction></boltArtifact>"""
        )
        assertEquals(1, rec.events.count { it.startsWith("actionOpen") })
    }

    @Test
    fun `flush closes pending action at end of stream`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        var full = "<boltArtifact id=\"f\" title=\"F\">"
        parser.parse("m9", full)
        full += "<boltAction type=\"shell\">npm install"
        parser.parse("m9", full)
        assertEquals(0, rec.events.count { it.startsWith("actionClose") })
        parser.flush("m9")
        assertEquals(1, rec.events.count { it.startsWith("actionClose") })
    }

    @Test
    fun `flush with applyIncomplete=false drops a truncated file action (H-06)`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        var full = "<boltArtifact id=\"f\" title=\"F\">"
        parser.parse("m10", full)
        full += "<boltAction type=\"file\" filePath=\"package.json\">{ \"name\": \"x\", \"dep"
        parser.parse("m10", full)
        val dropped = parser.flush("m10", applyIncomplete = false)
        assertEquals(true, dropped)
        assertEquals(0, rec.events.count { it.startsWith("actionClose") })
    }

    @Test
    fun `flush reports false when nothing is pending`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse("m11", "plain text without artifact")
        assertEquals(false, parser.flush("m11", applyIncomplete = false))
    }
}

private fun BoltAction.typeName(): String = when (this) {
    is BoltAction.FileAction -> "file"
    is BoltAction.ShellAction -> "shell"
    is BoltAction.StartAction -> "start"
    is BoltAction.ArtifactAction -> "artifact"
    is BoltAction.ToolAction -> "tool"
    is BoltAction.UnknownAction -> type
    else -> "unknown"
}

