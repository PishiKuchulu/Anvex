package com.anvex.core.engine

import com.anvex.core.model.MutationOp
import com.anvex.core.model.MutationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-10-23 external review findings
 * (BUG-01/02/03/07, SEC-03, SEC-11). Each test names the finding it pins.
 */
class ExternalReviewRegressionTest {

    // ------------------------------------------------------------- BUG-01

    @Test
    fun `BUG-01 undoing a directory rename restores the directory instead of deleting it`() {
        val fs = FakeFs()
        fs.mkdirs("src")
        fs.writeFile("src/app.txt", "content")
        val dispatcher = ActionDispatcher(fs)
        val renamed = dispatcher.rename("src", "lib", MutationSource.AI_SHELL_COMMAND)
        assertTrue(renamed.success)
        assertTrue(fs.exists("lib/app.txt"))

        val mutation = dispatcher.mutations.latestForPath("src")
        assertNotNull(mutation)
        assertEquals(MutationOp.RENAME, mutation!!.operation)
        assertTrue("directory rename must revert by moving back", dispatcher.mutations.revertMutation(mutation, fs))
        assertTrue(fs.exists("src/app.txt"))
        assertEquals("content", fs.readFile("src/app.txt"))
        assertFalse(fs.exists("lib/app.txt"))
    }

    @Test
    fun `BUG-01 undoing a file rename restores the original path`() {
        val fs = FakeFs()
        fs.writeFile("a.txt", "hello")
        val dispatcher = ActionDispatcher(fs)
        assertTrue(dispatcher.rename("a.txt", "b.txt", MutationSource.AI_SHELL_COMMAND).success)
        val mutation = dispatcher.mutations.latestForPath("a.txt")!!
        assertTrue(dispatcher.mutations.revertMutation(mutation, fs))
        assertEquals("hello", fs.readFile("a.txt"))
        assertFalse(fs.exists("b.txt"))
    }

    // ------------------------------------------------------------- BUG-02

    @Test
    fun `BUG-02 approved recursive delete actually deletes without a second approval`() {
        val fs = FakeFs()
        fs.mkdirs("build")
        fs.writeFile("build/out.bin", "x")
        // No approval callback at all (ArtifactRunner/AgentOrchestrator shape):
        // the pre-granted approval must be threaded into the delete effect.
        val dispatcher = ActionDispatcher(fs)
        val results = dispatcher.applyShellAction(
            BoltAction.ShellAction("rm -rf build"), "shell", approvalGranted = true,
        )
        assertTrue(results.first().success)
        assertFalse(fs.exists("build/out.bin"))
    }

    @Test
    fun `BUG-02 unapproved recursive delete fails honestly`() {
        val fs = FakeFs()
        fs.mkdirs("build")
        fs.writeFile("build/out.bin", "x")
        val dispatcher = ActionDispatcher(fs)
        val results = dispatcher.applyShellAction(BoltAction.ShellAction("rm -rf build"), "shell")
        assertFalse(results.first().success)
        assertTrue(fs.exists("build/out.bin"))
    }

    // ------------------------------------------------------------- BUG-03

    @Test
    fun `BUG-03 failed move reports failure not success`() {
        val fs = FakeFs()
        val dispatcher = ActionDispatcher(fs)
        val results = dispatcher.applyShellAction(BoltAction.ShellAction("mv missing.txt dest.txt"), "shell")
        assertFalse(results.first().success)
        assertNotNull(results.first().error)
    }

    @Test
    fun `BUG-03 failed copy reports failure not success`() {
        val fs = FakeFs()
        val dispatcher = ActionDispatcher(fs)
        val results = dispatcher.applyShellAction(BoltAction.ShellAction("cp missing.txt dest.txt"), "shell")
        assertFalse(results.first().success)
        assertNotNull(results.first().error)
    }

    @Test
    fun `BUG-03 deleting a missing path reports failure`() {
        val fs = FakeFs()
        val dispatcher = ActionDispatcher(fs)
        val results = dispatcher.applyShellAction(BoltAction.ShellAction("rm ghost.txt"), "shell")
        assertFalse(results.first().success)
    }

    // ------------------------------------------------------------- BUG-07

    @Test
    fun `BUG-07 key file contents survive a huge file list`() {
        val fs = FakeFs()
        fs.writeFile("package.json", "{\"name\":\"demo\"}")
        repeat(3000) { i -> fs.writeFile("node_modules/pkg$i/index.js", "x") }
        repeat(2000) { i -> fs.writeFile("src/file$i.txt", "x") }
        val context = ContextBuilder.build(fs)
        assertTrue(context.contains("Key file contents:"))
        assertTrue(context.contains("\"name\":\"demo\""))
        assertFalse("node_modules must not enter model context", context.contains("node_modules/pkg0"))
        assertTrue(context.length <= ContextBuilder.MAX_TOTAL_CHARS)
    }

    // ------------------------------------------------------------- SEC-03

    @Test
    fun `SEC-03 assessor bypass table now requires approval`() {
        val approval = ActionSafetyPolicy.Risk.REQUIRES_APPROVAL
        listOf(
            "/bin/rm -rf build",
            "env rm -rf .",
            "busybox rm -rf .",
            "xargs rm -rf",
            "rm\${IFS}-rf\${IFS}build",
            "rm -f *",
            "rm *",
            "find . -delete",
            "git -C . reset --hard",
            "git checkout -- .",
            "git push --force",
            "echo hi > ../../x",
            "echo hi > ~/.bashrc",
        ).forEach { cmd ->
            assertEquals("expected approval for: $cmd", approval, ActionSafetyPolicy.assess(cmd).risk)
        }
    }

    @Test
    fun `SEC-03 everyday commands stay safe`() {
        val safe = ActionSafetyPolicy.Risk.SAFE
        listOf(
            "ls src",
            "cat package.json",
            "echo hello > note.txt",
            "echo hello >> note.txt",
            "git status",
            "git checkout -b feature",
            "git commit -m init",
            "mv a.txt b.txt",
            "rm old.txt",
            "npm run build",
        ).forEach { cmd ->
            assertEquals("expected SAFE for: $cmd", safe, ActionSafetyPolicy.assess(cmd).risk)
        }
    }

    // ------------------------------------------------------------- SEC-11

    @Test
    fun `SEC-11 agent writes into git metadata are refused`() {
        val fs = FakeFs()
        val dispatcher = ActionDispatcher(fs)
        val result = dispatcher.writeFile(".git/config", "x", MutationSource.AI_FILE_ACTION)
        assertFalse(result.success)
        assertFalse(fs.exists(".git/config"))
    }

    @Test
    fun `SEC-11 bidi override in a path segment is rejected`() {
        assertEquals(null, PathSanitizer.sanitizeOrNull("evil\u202Etxt.exe"))
    }

    // ------------------------------------------------------------- BUG-11

    @Test
    fun `BUG-11 export filter drops junk but keeps user files`() {
        assertFalse(ZipCodec.isExportable("node_modules/pkg/index.js"))
        assertFalse(ZipCodec.isExportable(".git/config"))
        assertFalse(ZipCodec.isExportable("build/outputs/app.apk"))
        assertFalse(ZipCodec.isExportable(".anvex-shell-123.log"))
        assertTrue(ZipCodec.isExportable("src/main.kt"))
        assertTrue(ZipCodec.isExportable(".anvex/attachments/photo.png"))
        assertTrue(ZipCodec.isExportable("core/src/build/notes.txt"))
    }
}
