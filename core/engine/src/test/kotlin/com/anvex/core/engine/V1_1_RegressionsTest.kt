package com.anvex.core.engine

import com.anvex.core.model.ActionStatus
import com.anvex.core.model.MutationOp
import com.anvex.core.model.MutationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1 P0 regression tests: artifact ID compatibility (P0-4), before/after
 * mutation correctness (P0-2), honest PARTIAL status, MutationLog revert,
 * capability reporting, and context bounds.
 */
class ArtifactIdCompatTest {

    private class Recorder : ParserCallbacks {
        var opened: AnvexArtifactData? = null
        val openedIds = ArrayList<String>()
        override fun onArtifactOpen(artifact: AnvexArtifactData) {
            opened = artifact; openedIds.add(artifact.id)
        }
    }

    @Test
    fun `supplied artifact id is preserved verbatim`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse("m1", """<boltArtifact id="project" title="P"><boltAction type="shell">ls</boltAction></boltArtifact>""")
        assertEquals("project", rec.opened?.id)
    }

    @Test
    fun `kebab-case ids with dots and underscores survive`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse("m2", """<boltArtifact id="expense-tracker.v2_final" title="T"><boltAction type="shell">ls</boltAction></boltArtifact>""")
        assertEquals("expense-tracker.v2_final", rec.opened?.id)
    }

    @Test
    fun `blank or missing id falls back to generated id`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse("m3", """<boltArtifact id="" title="T"><boltAction type="shell">ls</boltAction></boltArtifact>""")
        assertEquals("m3-0", rec.opened?.id)
    }

    @Test
    fun `repeated id across messages means artifact continuation`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse("m4", """<boltArtifact id="project" title="v1"><boltAction type="shell">ls</boltAction></boltArtifact>""")
        parser.parse("m5", """<boltArtifact id="project" title="v2 update"><boltAction type="shell">cat x</boltAction></boltArtifact>""")
        // Same id on the continuation — callers link the two artifacts.
        assertEquals(listOf("project", "project"), rec.openedIds)
    }

    @Test
    fun `multiple artifacts each keep their own supplied id`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val one = """<boltArtifact id="styles" title="CSS"><boltAction type="file" filePath="a.css">b{}</boltAction></boltArtifact>"""
        val two = """<boltArtifact id="logic" title="JS"><boltAction type="file" filePath="a.js">1;</boltAction></boltArtifact>"""
        parser.parse("m6", one)
        parser.parse("m6", one + two)
        assertEquals(listOf("styles", "logic"), rec.openedIds)
    }

    @Test
    fun `unsafe id characters are sanitized without dropping the id`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        parser.parse("m7", """<boltArtifact id="bad id/with spaces" title="T"><boltAction type="shell">ls</boltAction></boltArtifact>""")
        val id = rec.opened?.id ?: ""
        assertTrue(id.isNotBlank())
        assertTrue(id.all { it.isLetterOrDigit() || it in "._-" })
        assertNotEquals("m7-0", id) // supplied id still wins after sanitization
    }

    @Test
    fun `artifact tag split across arbitrary SSE chunks still yields the id`() {
        val rec = Recorder()
        val parser = BoltStreamParser(rec)
        val full = """<boltArtifact id="project" title="P"><boltAction type="file" filePath="i.html">x</boltAction></boltArtifact>"""
        var len = 0
        while (len < full.length) {
            len = minOf(len + 3, full.length)
            parser.parse("m8", full.substring(0, len))
        }
        parser.flush("m8")
        assertEquals("project", rec.opened?.id)
    }
}

class ActionDispatcherMutationTest {

    @Test
    fun `shell echo overwrite records true before and after (P0-2)`() {
        val fs = FakeFs()
        fs.writeFile("notes.txt", "OLD CONTENT\n")
        val d = ActionDispatcher(fs)
        d.applyShellAction(BoltAction.ShellAction("echo \"NEW\" > notes.txt"), "shell")

        val m = d.mutations.latestForPath("notes.txt")
        assertNotNull(m)
        assertEquals("OLD CONTENT\n", m?.before) // BEFORE the write, not after
        assertEquals("NEW\n", m?.after)
        assertEquals(MutationOp.MODIFY, m?.operation)
    }

    @Test
    fun `shell echo append records original as before`() {
        val fs = FakeFs()
        fs.writeFile("log.txt", "line1\n")
        val d = ActionDispatcher(fs)
        d.applyShellAction(BoltAction.ShellAction("echo line2 >> log.txt"), "shell")

        val m = d.mutations.latestForPath("log.txt")
        assertEquals("line1\n", m?.before)
        assertEquals("line1\nline2\n", m?.after)
    }

    @Test
    fun `mv records a RENAME mutation with destination`() {
        val fs = FakeFs()
        fs.writeFile("old.js", "export default 1;\n")
        val d = ActionDispatcher(fs)
        d.applyShellAction(BoltAction.ShellAction("mv old.js new.js"), "shell")

        val m = d.mutations.all().first()
        assertEquals(MutationOp.RENAME, m.operation)
        assertEquals("old.js", m.path)
        assertEquals("new.js", m.renamedTo)
        assertTrue(fs.exists("new.js"))
        assertTrue(!fs.exists("old.js"))
    }

    @Test
    fun `rm records DELETE with before content for restore`() {
        val fs = FakeFs()
        fs.writeFile("temp.txt", "bye\n")
        val d = ActionDispatcher(fs)
        d.applyShellAction(BoltAction.ShellAction("rm temp.txt"), "shell")

        val m = d.mutations.all().first()
        assertEquals(MutationOp.DELETE, m.operation)
        assertEquals("bye\n", m.before)
        assertNull(m.after)
    }

    @Test
    fun `cp records destination mutation`() {
        val fs = FakeFs()
        fs.writeFile("a.txt", "data\n")
        val d = ActionDispatcher(fs)
        d.applyShellAction(BoltAction.ShellAction("cp a.txt b.txt"), "shell")

        val m = d.mutations.latestForPath("b.txt")
        assertEquals(MutationOp.CREATE, m?.operation)
        assertEquals("data\n", m?.after)
    }

    @Test
    fun `every action result carries duration changed files and honest status`() {
        val fs = FakeFs()
        val d = ActionDispatcher(fs)
        val result = d.applyFileAction(BoltAction.FileAction("x\n", "f.txt"))
        assertTrue(result.success)
        assertEquals(listOf("f.txt"), result.changedFiles)
        assertTrue(result.durationMs >= 0)
        assertEquals(ActionStatus.COMPLETE, result.status)

        val partial = d.applyShellAction(BoltAction.ShellAction("npm install"), "shell").first()
        assertEquals(ActionStatus.PARTIAL, partial.status) // honest, never COMPLETE

        val failed = d.applyFileAction(BoltAction.FileAction("x", "../escape"))
        assertEquals(ActionStatus.FAILED, failed.status)
        assertNotNull(failed.error)
    }

    @Test
    fun `file action over existing file keeps before for revert`() {
        val fs = FakeFs()
        fs.writeFile("App.jsx", "export default () => <div>old</div>;\n")
        val d = ActionDispatcher(fs)
        d.applyFileAction(BoltAction.FileAction("export default () => <div>new</div>;\n", "App.jsx"))

        val m = d.mutations.latestForPath("App.jsx")
        assertEquals("export default () => <div>old</div>;\n", m?.before)
        assertEquals("export default () => <div>new</div>;\n", m?.after)
    }

    @Test
    fun `create-then-overwrite journal preserves full history`() {
        val fs = FakeFs()
        val d = ActionDispatcher(fs)
        d.writeFile("f.txt", "v1", MutationSource.AI_FILE_ACTION)
        d.writeFile("f.txt", "v2", MutationSource.AI_FILE_ACTION)
        d.writeFile("f.txt", "v3", MutationSource.AI_FILE_ACTION)

        val forPath = d.mutations.forPath("f.txt")
        assertEquals(3, forPath.size)
        // First entry is the CREATE (no prior content — that IS the accurate history).
        assertEquals(MutationOp.CREATE, forPath.first().operation)
        assertNull(forPath.first().before)
        assertEquals("v1", forPath.first().after)
        // Later entries chain before/after correctly.
        assertEquals("v1", forPath[1].before)
        assertEquals("v2", forPath[2].before)
        assertEquals("v3", forPath.last().after)
    }
}

class MutationLogTest {

    @Test
    fun `revert CREATE removes the file`() {
        val fs = FakeFs()
        val d = ActionDispatcher(fs)
        d.writeFile("new.txt", "content", MutationSource.AI_FILE_ACTION)
        assertTrue(fs.exists("new.txt"))
        assertTrue(d.mutations.revert("new.txt", fs))
        assertTrue(!fs.exists("new.txt"))
    }

    @Test
    fun `revert MODIFY restores previous content`() {
        val fs = FakeFs()
        fs.writeFile("f.txt", "original")
        val d = ActionDispatcher(fs)
        d.writeFile("f.txt", "changed", MutationSource.AI_FILE_ACTION)
        assertTrue(d.mutations.revert("f.txt", fs))
        assertEquals("original", fs.readFile("f.txt"))
    }

    @Test
    fun `revert DELETE restores content`() {
        val fs = FakeFs()
        fs.writeFile("f.txt", "keep me")
        val d = ActionDispatcher(fs)
        d.deleteFile("f.txt", recursive = false, source = MutationSource.AI_SHELL_COMMAND)
        assertTrue(!fs.exists("f.txt"))
        assertTrue(d.mutations.revert("f.txt", fs))
        assertEquals("keep me", fs.readFile("f.txt"))
    }

    @Test
    fun `revert RENAME restores source and removes destination`() {
        val fs = FakeFs()
        fs.writeFile("a.txt", "payload")
        val d = ActionDispatcher(fs)
        d.rename("a.txt", "b.txt", MutationSource.AI_SHELL_COMMAND)
        assertTrue(fs.exists("b.txt"))
        val m = d.mutations.latestForPath("a.txt")!!
        assertTrue(d.mutations.revertMutation(m, fs))
        assertEquals("payload", fs.readFile("a.txt"))
        assertTrue(!fs.exists("b.txt"))
    }

    @Test
    fun `journal is bounded (memory guard for 4GB devices)`() {
        val fs = FakeFs()
        val log = MutationLog(maxEntries = 5)
        repeat(20) { i -> log.record(com.anvex.core.model.FileMutation("f$i", MutationOp.CREATE, null, "x", timestamp = i.toLong())) }
        assertEquals(5, log.size)
        // Oldest dropped (f0..f14 gone); the deque keeps f15..f19 oldest-first.
        assertEquals("f15", log.all().first().path)
        assertEquals("f19", log.all().last().path)
    }

    @Test
    fun `latestPerPath collapses multiple writes to one diff entry`() {
        val fs = FakeFs()
        val d = ActionDispatcher(fs)
        d.writeFile("a.txt", "1", MutationSource.AI_FILE_ACTION)
        d.writeFile("a.txt", "2", MutationSource.AI_FILE_ACTION)
        d.writeFile("b.txt", "x", MutationSource.AI_FILE_ACTION)
        val latest = d.mutations.latestPerPath()
        assertEquals(2, latest.size)
        assertEquals("2", latest.first { it.path == "a.txt" }.after)
    }
}

class RuntimeCapabilityTest {

    @Test
    fun `capability report tells the model the truth`() {
        val report = RuntimeCapabilityReport.describe()
        // v4.6.1: the report now tells the two-tier truth — no Node inside
        // Preview, but the bundled Node.js 18 runtime exists for Terminal/Agent.
        assertTrue(report.contains("Node.js:           NOT AVAILABLE in the Preview"))
        assertTrue(report.contains("bundled Node.js 18 runtime IS available"))
        assertTrue(report.contains("Vite:              NOT AVAILABLE"))
        assertTrue(report.contains("Filesystem:        SUPPORTED"))
        // The matrix must be embedded in the actual system prompt.
        val prompt = SystemPromptFactory.buildSystemPrompt()
        assertTrue(prompt.contains("Android Runtime Capabilities"))
        assertTrue(prompt.contains("NOT AVAILABLE"))
    }

    @Test
    fun `command capability query agrees with translation tiers`() {
        assertEquals(CommandTranslator.Tier.SUPPORTED, CommandTranslator.capabilityOf("mkdir src"))
        assertEquals(CommandTranslator.Tier.PARTIALLY, CommandTranslator.capabilityOf("npm install"))
        assertEquals(CommandTranslator.Tier.UNSUPPORTED, CommandTranslator.capabilityOf("npm run dev"))
    }

    @Test
    fun `runtime capabilities object never claims node or vite`() {
        val caps = RuntimeCapabilities.CURRENT
        assertEquals(CommandCapability.UNSUPPORTED, caps.nodeJs)
        assertEquals(CommandCapability.UNSUPPORTED, caps.vite)
        assertEquals(CommandCapability.SUPPORTED, caps.offlineReactPreview)
        assertEquals(CommandCapability.PARTIAL, caps.npm)
    }

    @Test
    fun `preview runtime reports honest tiers for unsupported operations`() {
        val rt = WebViewPreviewRuntime(FakeFs())
        val build = kotlinx.coroutines.runBlocking { rt.build() }
        assertEquals(CommandCapability.UNSUPPORTED, build.capability)
        assertTrue(build.error != null)
        assertEquals(CommandCapability.PARTIAL, kotlinx.coroutines.runBlocking { rt.installDependencies() }.capability)
        assertEquals(CommandCapability.PARTIAL, kotlinx.coroutines.runBlocking { rt.startDevServer() }.capability)
    }

    @Test
    fun `preview runtime executes commands via translator`() {
        val rt = WebViewPreviewRuntime(FakeFs())
        val result = kotlinx.coroutines.runBlocking { rt.execute("mkdir src") }
        assertEquals(CommandCapability.SUPPORTED, result.capability)
        val bad = kotlinx.coroutines.runBlocking { rt.execute("node server.js") }
        assertEquals(CommandCapability.UNSUPPORTED, bad.capability)
    }
}

class ContextBoundsTest {

    @Test
    fun `context builder never exceeds the hard cap`() {
        val fs = FakeFs()
        // ~10 files x 14k chars each — far beyond MAX_TOTAL_CHARS
        repeat(12) { i ->
            fs.writeFile(
                "src/file$i.tsx",
                "// padding\n" + "export const x$i = \"" + "p".repeat(13_000) + "\";\n"
            )
        }
        val context = ContextBuilder.build(fs)
        assertTrue("context size ${context.length} exceeds cap", context.length <= ContextBuilder.MAX_TOTAL_CHARS + 2_000)
    }

    @Test
    fun `changed files and open file take priority`() {
        val fs = FakeFs()
        fs.writeFile("package.json", "{}")
        fs.writeFile("src/App.jsx", "app code")
        val context = ContextBuilder.build(fs, openFilePath = "src/App.jsx", diffNote = "changed: src/App.jsx")
        assertTrue(context.contains("### src/App.jsx"))
        assertTrue(context.contains("changed: src/App.jsx"))
    }

    @Test
    fun `empty project context is explicit`() {
        val context = ContextBuilder.build(FakeFs())
        assertTrue(context.contains("empty"))
    }
}

class PreviewHarnessOfflineTest {

    @Test
    fun `harness loads the OFFLINE runtime bundle - no CDN for babel or react`() {
        val html = PreviewHarness.generate("[]", "src/main.jsx", emptyList(), "{}")
        // Babel + React UMDs come from the local server, not a CDN (v1.1 §11).
        assertTrue(html.contains("src=\"${PreviewHarness.RUNTIME_ROUTE}${PreviewHarness.ASSET_REACT_UMD}\""))
        assertTrue(html.contains("src=\"${PreviewHarness.RUNTIME_ROUTE}${PreviewHarness.ASSET_REACT_DOM_UMD}\""))
        assertTrue(html.contains("src=\"${PreviewHarness.RUNTIME_ROUTE}${PreviewHarness.ASSET_BABEL}\""))
        assertTrue(!html.contains("cdn.jsdelivr.net"))
        // esm.sh remains ONLY as the documented fallback for third-party bare
        // imports — react itself resolves through the local shims.
        assertTrue(html.contains("localShim"))
    }

    @Test
    fun `offline specifiers cover the react family`() {
        val specs = PreviewHarness.OFFLINE_SPECIFIERS
        assertEquals(
            listOf("react", "react-dom", "react-dom/client", "react/jsx-dev-runtime", "react/jsx-runtime"),
            specs.keys.sorted(),
        )
        assertTrue(specs.values.all { it.startsWith(PreviewHarness.RUNTIME_ROUTE) })
    }

    @Test
    fun `runtime asset whitelist covers exactly the bundled set`() {
        val whitelist = PreviewHarness.WRAPPER_ASSETS + setOf(
            PreviewHarness.ASSET_BABEL,
            PreviewHarness.ASSET_REACT_UMD,
            PreviewHarness.ASSET_REACT_DOM_UMD,
        )
        val bundled = setOf(
            "babel.min.js", "react.umd.js", "react-dom.umd.js",
            "react.js", "react-dom.js", "react-dom-client.js", "react-jsx-runtime.js",
        )
        assertEquals(bundled, whitelist) // whitelist == APK contents, nothing more
    }

    @Test
    fun `jsx runtime shim exports jsx jsxs and Fragment for the require path`() {
        val html = PreviewHarness.generate("[]", "src/main.jsx", emptyList(), "{}")
        // The CJS shim must implement the automatic-runtime signature (key folded
        // into config, not passed as a child) and export the named members.
        assertTrue(html.contains("function jsxImpl(type, props, key)"))
        assertTrue(html.contains("jsx: jsxImpl"))
        assertTrue(html.contains("jsxs: jsxImpl"))
        assertTrue(html.contains("jsxDEV: jsxImpl"))
        assertTrue(html.contains("config.key = key"))
    }

    @Test
    fun `harness keeps the console bridge and error forwarding`() {
        val html = PreviewHarness.generate("[]", "src/main.jsx", emptyList(), "{}")
        assertTrue(html.contains("__anvex"))
        assertTrue(html.contains("window.onerror"))
        assertTrue(html.contains("unhandledrejection"))
        assertTrue(html.contains("\"ready\""))
    }
}
