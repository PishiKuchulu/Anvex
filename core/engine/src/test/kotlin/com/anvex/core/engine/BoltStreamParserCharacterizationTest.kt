package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STEP 0 characterization net for the BoltStreamParser correctness repair.
 *
 * These tests pin TODAY's observable behavior for the five cases the repair
 * brief names (normal file action, shell action, prose around an artifact,
 * two artifacts in one message, an .md file). They were written against the
 * pre-repair parser and must stay green after the repair; any that cannot
 * stay green must be called out explicitly with a justification.
 */
class BoltStreamParserCharacterizationTest {

    private class Recorder : ParserCallbacks {
        val events = ArrayList<String>()
        val fileActions = ArrayList<BoltAction.FileAction>()
        val shellActions = ArrayList<BoltAction.ShellAction>()
        var quickActions: List<QuickAction> = emptyList()

        override fun onArtifactOpen(artifact: AnvexArtifactData) {
            events.add("artifactOpen:${artifact.id}:${artifact.title}")
        }

        override fun onArtifactClose(artifact: AnvexArtifactData) {
            events.add("artifactClose:${artifact.id}")
        }

        override fun onActionOpen(data: ActionCallbackData) {
            events.add("actionOpen:${data.actionId}")
        }

        override fun onActionClose(data: ActionCallbackData) {
            events.add("actionClose:${data.actionId}")
            when (val a = data.action) {
                is BoltAction.FileAction -> fileActions.add(a)
                is BoltAction.ShellAction -> shellActions.add(a)
                else -> Unit
            }
        }

        override fun onQuickActions(actions: List<QuickAction>) {
            quickActions = actions
        }
    }

    @Test
    fun `characterization - normal file action`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = "Before.\n" +
            "<boltArtifact id=\"proj\" title=\"Proj\">\n" +
            "<boltAction type=\"file\" filePath=\"index.html\">\n" +
            "<html>hello</html>\n" +
            "</boltAction>\n" +
            "</boltArtifact>\n" +
            "After."
        val prose = parser.parse("c1", input)
        assertEquals(listOf("index.html"), rec.fileActions.map { it.filePath })
        assertEquals("<html>hello</html>\n", rec.fileActions[0].content)
        assertEquals(
            listOf("artifactOpen:proj:Proj", "actionOpen:0", "actionClose:0", "artifactClose:proj"),
            rec.events,
        )
        assertEquals("Before.\n\nAfter.", prose)
    }

    @Test
    fun `characterization - shell action`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = "<boltArtifact id=\"s\" title=\"S\">\n" +
            "<boltAction type=\"shell\">\n" +
            "npm install\n" +
            "</boltAction>\n" +
            "</boltArtifact>"
        parser.parse("c2", input)
        assertEquals(1, rec.shellActions.size)
        assertEquals("npm install", rec.shellActions[0].content)
    }

    @Test
    fun `characterization - prose before and after an artifact`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = "Intro text.\n" +
            "<boltArtifact id=\"p\" title=\"P\">\n" +
            "<boltAction type=\"file\" filePath=\"a.txt\">\n" +
            "A\n" +
            "</boltAction>\n" +
            "</boltArtifact>\n" +
            "Outro text."
        val prose = parser.parse("c3", input)
        assertEquals("Intro text.\n\nOutro text.", prose)
        assertEquals("A\n", rec.fileActions[0].content)
    }

    @Test
    fun `characterization - two artifacts in one message`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = "<boltArtifact id=\"art-a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"a.txt\">\nA\n</boltAction>\n" +
            "</boltArtifact>\n" +
            "middle\n" +
            "<boltArtifact id=\"art-b\" title=\"B\">\n" +
            "<boltAction type=\"file\" filePath=\"b.txt\">\nB\n</boltAction>\n" +
            "</boltArtifact>"
        val prose = parser.parse("c4", input)
        assertEquals(listOf("a.txt", "b.txt"), rec.fileActions.map { it.filePath })
        assertEquals(listOf("A\n", "B\n"), rec.fileActions.map { it.content })
        assertEquals(
            listOf(
                "artifactOpen:art-a:A", "actionOpen:0", "actionClose:0", "artifactClose:art-a",
                // Today's behavior: action ids are a per-MESSAGE counter, so the
                // second artifact's first action is id 1, not 0.
                "artifactOpen:art-b:B", "actionOpen:1", "actionClose:1", "artifactClose:art-b",
            ),
            rec.events,
        )
        assertTrue(prose.contains("middle"))
    }

    @Test
    fun `characterization - markdown file keeps fences and entities`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val input = "<boltArtifact id=\"d\" title=\"D\">\n" +
            "<boltAction type=\"file\" filePath=\"README.md\">\n" +
            "# Doc\n\n```\n&lt;tag&gt;\n```\n" +
            "</boltAction>\n" +
            "</boltArtifact>"
        parser.parse("c5", input)
        assertEquals(1, rec.fileActions.size)
        assertEquals("README.md", rec.fileActions[0].filePath)
        assertEquals("# Doc\n\n```\n&lt;tag&gt;\n```\n", rec.fileActions[0].content)
    }
}
