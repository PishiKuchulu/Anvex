package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CommandTranslatorTest {

    private val t = CommandTranslator

    @Test
    fun `npm install is partial with dependency note`() {
        val part = t.translateOne("npm install")
        assertEquals(CommandTranslator.Tier.PARTIALLY, part.tier)
        assertTrue(part.effect is CommandTranslator.Effect.RecordDependencies)
        assertTrue(part.note.contains("recorded", ignoreCase = true))
    }

    @Test
    fun `npm run dev is unsupported`() {
        val part = t.translateOne("npm run dev")
        assertEquals(CommandTranslator.Tier.UNSUPPORTED, part.tier)
        assertTrue(part.note.contains("Android"))
    }

    @Test
    fun `npx vite dev is unsupported`() {
        assertEquals(CommandTranslator.Tier.UNSUPPORTED, t.translateOne("npx vite").tier)
    }

    @Test
    fun `mkdir translates`() {
        val part = t.translateOne("mkdir -p src/components")
        assertEquals(CommandTranslator.Tier.SUPPORTED, part.tier)
        val effect = part.effect as CommandTranslator.Effect.Mkdir
        assertEquals(listOf("src/components"), effect.paths)
    }

    @Test
    fun `mkdir with traversal is rejected`() {
        val part = t.translateOne("mkdir -p ../outside")
        assertEquals(CommandTranslator.Tier.UNSUPPORTED, part.tier)
    }

    @Test
    fun `echo redirect writes file`() {
        val part = t.translateOne("""echo "hello" > notes.txt""")
        assertEquals(CommandTranslator.Tier.SUPPORTED, part.tier)
        val effect = part.effect as CommandTranslator.Effect.Write
        assertEquals("notes.txt", effect.path)
        assertEquals("hello", effect.content)
        assertEquals(false, effect.append)
    }

    @Test
    fun `echo append`() {
        val part = t.translateOne("""echo line2 >> notes.txt""")
        val effect = part.effect as CommandTranslator.Effect.Write
        assertTrue(effect.append)
        assertEquals("line2", effect.content)
    }

    @Test
    fun `rm translates with recursion flag`() {
        val part = t.translateOne("rm -rf dist")
        val effect = part.effect as CommandTranslator.Effect.Delete
        assertTrue(effect.recursive)
        assertEquals(listOf("dist"), effect.paths)
    }

    @Test
    fun `compound commands split`() {
        val translation = t.translate("mkdir src && cd src && npm install; ls")
        assertEquals(4, translation.parts.size)
        assertEquals(CommandTranslator.Tier.PARTIALLY, translation.parts[2].tier)
        assertEquals(CommandTranslator.Tier.SUPPORTED, translation.parts[3].tier)
    }

    @Test
    fun `quoted tokens survive tokenizer`() {
        val part = t.translateOne("""mv "my file.txt" target.txt""")
        val effect = part.effect as CommandTranslator.Effect.Move
        assertEquals("my file.txt", effect.from)
        assertEquals("target.txt", effect.to)
    }

    @Test
    fun `node and python are unsupported`() {
        assertEquals(CommandTranslator.Tier.UNSUPPORTED, t.translateOne("node index.js").tier)
        assertEquals(CommandTranslator.Tier.UNSUPPORTED, t.translateOne("python3 script.py").tier)
    }

    @Test
    fun `cat and ls are supported`() {
        assertEquals(CommandTranslator.Tier.SUPPORTED, t.translateOne("ls -la src").tier)
        assertEquals(CommandTranslator.Tier.SUPPORTED, t.translateOne("cat package.json").tier)
    }
}

class PathSanitizerTest {

    @Test
    fun `traversal is rejected`() {
        assertNull(PathSanitizer.sanitizeOrNull("../evil"))
        assertNull(PathSanitizer.sanitizeOrNull("ok/../../evil"))
        assertNull(PathSanitizer.sanitizeOrNull("a/b/../../../c"))
    }

    @Test
    fun `absolute paths rejected`() {
        assertNull(PathSanitizer.sanitizeOrNull("/etc/passwd"))
        assertNull(PathSanitizer.sanitizeOrNull("C:/windows"))
    }

    @Test
    fun `dots normalize`() {
        assertEquals("src/App.tsx", PathSanitizer.sanitize("./src//App.tsx"))
        assertEquals("src/App.tsx", PathSanitizer.sanitize("src/./App.tsx"))
    }

    @Test
    fun `inner traversal resolves inside root`() {
        assertEquals("b/c.txt", PathSanitizer.sanitize("a/../b/c.txt"))
    }

    @Test
    fun `null bytes rejected`() {
        assertNull(PathSanitizer.sanitizeOrNull("a\u0000b"))
    }

    @Test
    fun `backslashes converted`() {
        assertEquals("src/app.js", PathSanitizer.sanitize("src\\app.js"))
    }
}

class DiffEngineTest {

    @Test
    fun `basic modification diff`() {
        val old = "a\nb\nc\nd"
        val new = "a\nB\nc\nd\ne"
        val lines = DiffEngine.diff(old, new)
        val stats = DiffEngine.stats(old, new)
        assertTrue(stats.added >= 1)
        assertTrue(stats.removed >= 1)
        assertTrue(lines.any { it.op == DiffEngine.Op.EQUAL && it.text == "a" })
    }

    @Test
    fun `identical text is all equal`() {
        val lines = DiffEngine.diff("x\ny", "x\ny")
        assertTrue(lines.all { it.op == DiffEngine.Op.EQUAL })
    }

    @Test
    fun `empty to content is inserts plus empty-line delete`() {
        val lines = DiffEngine.diff("", "a\nb")
        // splitting "" yields [""] so the empty line is deleted, rest inserted
        assertTrue(lines.count { it.op == DiffEngine.Op.INSERT } == 2)
        assertTrue(lines.all { it.op == DiffEngine.Op.INSERT || (it.op == DiffEngine.Op.DELETE && it.text.isEmpty()) })
    }

    @Test
    fun `huge inputs fall back without exploding`() {
        val big = (1..10_000).joinToString("\n") { "line$it" }
        val big2 = (1..10_000).joinToString("\n") { "line$it-mut" }
        val lines = DiffEngine.diff(big, big2)
        assertTrue(lines.isNotEmpty())
    }
}

class ZipCodecTest {

    @Test
    fun `round trip export import`() {
        val fs = FakeFs()
        fs.writeFile("index.html", "<html></html>")
        fs.writeFile("src/app.js", "console.log(1);")

        val out = ByteArrayOutputStream()
        ZipCodec.export(fs, out)

        val target = FakeFs()
        val count = ZipCodec.import(ByteArrayInputStream(out.toByteArray()), target)

        assertEquals(2, count)
        assertEquals("<html></html>", target.readFile("index.html"))
        assertEquals("console.log(1);", target.readFile("src/app.js"))
    }

    @Test
    fun `zip slip rejected`() {
        val bytes = zipOf("../evil.txt" to "bad", "ok.txt" to "good")
        val fs = FakeFs()
        try {
            ZipCodec.import(ByteArrayInputStream(bytes), fs)
            throw AssertionError("expected ZipSecurityException")
        } catch (_: ZipCodec.ZipSecurityException) {
        }
    }

    @Test
    fun `import streams entry to filesystem boundary`() {
        val bytes = zipOf("large.txt" to "hello")
        val fs = FakeFs()
        val count = ZipCodec.import(ByteArrayInputStream(bytes), fs)
        assertEquals(1, count)
        assertEquals("hello", fs.readFile("large.txt"))
    }

    @Test
    fun `absolute path entries rejected`() {
        val bytes = zipOf("/abs.txt" to "x")
        try {
            ZipCodec.import(ByteArrayInputStream(bytes), FakeFs())
            throw AssertionError("expected ZipSecurityException")
        } catch (_: ZipCodec.ZipSecurityException) {
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

class ArtifactRunnerTest {

    @Test
    fun `file action content convention matches parser output`() {
        val fs = FakeFs()
        val runner = ArtifactRunner(fs)
        // The parser appends a trailing newline to file actions (upstream behavior)
        runner.applyFileAction(BoltAction.FileAction("console.log('x')\n", "app/main.js"))

        assertEquals("console.log('x')\n", fs.readFile("app/main.js"))
        val change = runner.pendingChanges.first()
        assertEquals("app/main.js", change.path)
        assertEquals(com.anvex.core.model.MutationOp.CREATE, change.operation)
        assertNull(change.before)
    }

    @Test
    fun `modification keeps before content for revert`() {
        val fs = FakeFs()
        fs.writeFile("a.txt", "old\n")
        val runner = ArtifactRunner(fs)
        runner.applyFileAction(BoltAction.FileAction("new\n", "a.txt"))

        val change = runner.pendingChanges.first()
        assertEquals(com.anvex.core.model.MutationOp.MODIFY, change.operation)
        assertEquals("old\n", change.before)
        assertEquals("new\n", change.after)
    }

    @Test
    fun `unsafe path fails gracefully`() {
        val fs = FakeFs()
        val runner = ArtifactRunner(fs)
        val record = runner.applyFileAction(BoltAction.FileAction("x", "../../etc/passwd"))
        assertEquals(com.anvex.core.model.ActionStatus.FAILED.name, record.status)
    }

    @Test
    fun `shell action translates and applies effects`() {
        val fs = FakeFs()
        val runner = ArtifactRunner(fs)
        runner.applyShellAction(BoltAction.ShellAction("mkdir -p src && echo hello > src/note.txt"), "shell")

        assertTrue(fs.exists("src/note.txt"))
        assertEquals("hello\n", fs.readFile("src/note.txt"))
    }

    @Test
    fun `npm install is honestly PARTIAL not COMPLETE`() {
        val fs = FakeFs()
        val runner = ArtifactRunner(fs)
        val records = runner.applyShellAction(BoltAction.ShellAction("npm install"), "shell")
        assertEquals(com.anvex.core.model.ActionStatus.PARTIAL.name, records.first().status)
    }

    @Test
    fun `unsupported command marked failed with note`() {
        val fs = FakeFs()
        val runner = ArtifactRunner(fs)
        val records = runner.applyShellAction(BoltAction.StartAction("npm run dev"), "start")
        assertEquals(com.anvex.core.model.ActionStatus.FAILED.name, records.first().status)
        assertNotNull(records.first().note)
    }
}

class PromptTest {

    @Test
    fun `system prompt contains artifact protocol`() {
        val prompt = SystemPromptFactory.buildSystemPrompt()
        assertTrue(prompt.contains("<boltArtifact>"))
        assertTrue(prompt.contains("filePath"))
        assertTrue(prompt.contains("npm install"))
        assertTrue(prompt.contains("/home/project"))
        // Android constraints replace WebContainer constraints
        assertTrue(prompt.contains("Android"))
        assertTrue(!prompt.contains("WebContainer, an in-browser Node.js runtime"))
    }

    @Test
    fun `system prompt keeps normal conversation out of artifact protocol`() {
        val prompt = SystemPromptFactory.buildSystemPrompt()
        assertTrue(prompt.contains("For greetings, casual conversation, questions, explanations"))
        assertTrue(prompt.contains("DO NOT emit <boltArtifact>, <boltAction>, or generated files"))
        assertTrue(prompt.contains("A project context being attached does NOT by itself mean the user wants a file change"))
        assertTrue(prompt.contains("prefer a normal conversational response"))
    }

    @Test
    fun `context builder lists files with cap`() {
        val fs = FakeFs()
        fs.writeFile("package.json", """{"name":"x"}""")
        fs.writeFile("index.html", "<html>")
        fs.writeFile("src/App.jsx", "export default 1;")

        val context = ContextBuilder.build(fs, openFilePath = "src/App.jsx")
        assertTrue(context.contains("package.json"))
        assertTrue(context.contains("### src/App.jsx"))
        assertTrue(context.contains("<html>"))
    }
}


class CommandTranslatorSafetyTest {
    @org.junit.Test fun `compound unsupported syntax is not partially executed`() {
        val t = CommandTranslator.translate("ls || rm -rf project")
        org.junit.Assert.assertEquals(CommandTranslator.Tier.UNSUPPORTED, t.parts.single().tier)
    }

    @org.junit.Test fun `capability reports the worst part`() {
        org.junit.Assert.assertEquals(CommandTranslator.Tier.UNSUPPORTED, CommandTranslator.capabilityOf("ls && npm run dev"))
    }
}
