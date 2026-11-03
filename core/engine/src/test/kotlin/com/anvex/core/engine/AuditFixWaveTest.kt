package com.anvex.core.engine

import com.anvex.core.model.ActionStatus
import com.anvex.core.model.FileMutation
import com.anvex.core.model.MutationOp
import com.anvex.core.model.MutationSource
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-11-03 audit fix wave (engine partition):
 * the approval dead-end, write read-back verification, journal honesty,
 * protected-path depth, and ZIP pre-validation.
 */
class AuditFixWaveTest {

    // ---- approval dead-end (Logic #1) ----

    @Test
    fun shellRefusalWithoutCallbackExplainsSurfaceCannotGrant() {
        val dispatcher = ActionDispatcher(FakeFs())
        val result = dispatcher.applyShellAction(BoltAction.ShellAction("rm -rf src"), "shell").single()
        assertFalse(result.success)
        val error = result.error ?: ""
        assertTrue(error.startsWith("Approval required"))
        assertTrue(error.contains("cannot grant it"))
        assertTrue(error.contains("NOT executed"))
    }

    @Test
    fun shellRefusalWithDenyingCallbackSaysNotGranted() {
        val dispatcher = ActionDispatcher(FakeFs(), approval = { false })
        val result = dispatcher.applyShellAction(BoltAction.ShellAction("rm -rf src"), "shell").single()
        assertFalse(result.success)
        val error = result.error ?: ""
        assertTrue(error.contains("not granted"))
        assertFalse(error.contains("cannot grant it"))
    }

    @Test
    fun artifactRunnerForwardsApprovalCallback() {
        val fs = FakeFs()
        fs.files["src/a.txt"] = "x"
        fs.dirs.add("src")
        val denied = ArtifactRunner(fs)
            .applyShellAction(BoltAction.ShellAction("rm -rf src"), "shell").single()
        assertEquals(ActionStatus.FAILED.name, denied.status)
        assertTrue((denied.note ?: "").contains("cannot grant it"))
        assertTrue(fs.files.containsKey("src/a.txt"))

        val granted = ArtifactRunner(fs, approval = { true })
            .applyShellAction(BoltAction.ShellAction("rm -rf src"), "shell").single()
        assertEquals(ActionStatus.COMPLETE.name, granted.status)
        assertFalse(fs.files.containsKey("src/a.txt"))
    }

    // ---- write read-back verification (Logic: silent write corruption) ----

    @Test
    fun writeThatStorageAltersIsReportedPartial() {
        val backing = FakeFs()
        val truncating = object : ProjectFileSystem by backing {
            override fun writeFile(relativePath: String, content: String) {
                backing.writeFile(relativePath, content.take(4))
            }
        }
        val result = ActionDispatcher(truncating).applyFileAction(
            BoltAction.FileAction(content = "0123456789", filePath = "a.txt")
        )
        assertTrue(result.success)
        assertEquals(ActionStatus.PARTIAL, result.status)
        assertTrue((result.output ?: "").contains("differs from the intended content"))
    }

    // ---- journal eviction is visible (Logic: MutationLog silent drop) ----

    @Test
    fun mutationLogCountsEvictedEntriesAndClearResets() {
        val log = MutationLog(maxEntries = 2)
        repeat(3) { i -> log.record(FileMutation("f$i.txt", MutationOp.CREATE, null, "x")) }
        assertEquals(2, log.size)
        assertEquals(1, log.droppedCount)
        log.clear()
        assertEquals(0, log.droppedCount)
        assertEquals(0, log.size)
    }

    // ---- directory delete journal marking (Logic #2) ----

    @Test
    fun directoryDeleteIsJournaledAsNotRevertible() {
        val fs = FakeFs()
        fs.files["dir/inner.txt"] = "data"
        fs.dirs.add("dir")
        val dispatcher = ActionDispatcher(fs, approval = { true })
        val result = dispatcher.deleteFile("dir", recursive = true, source = MutationSource.AI_FILE_ACTION)
        assertTrue(result.success)
        val entry = dispatcher.mutations.all().last { it.operation == MutationOp.DELETE }
        assertFalse(entry.revertible)
    }

    @Test
    fun fileDeleteIsJournaledAsRevertible() {
        val fs = FakeFs()
        fs.files["f.txt"] = "data"
        val dispatcher = ActionDispatcher(fs)
        val result = dispatcher.deleteFile("f.txt", recursive = false, source = MutationSource.AI_FILE_ACTION)
        assertTrue(result.success)
        val entry = dispatcher.mutations.all().last { it.operation == MutationOp.DELETE }
        assertTrue(entry.revertible)
    }

    // ---- workflow protection at any depth (Security, informational) ----

    @Test
    fun nestedWorkflowFileIsProtected() {
        val result = ActionDispatcher(FakeFs()).applyFileAction(
            BoltAction.FileAction(content = "x", filePath = "sub/proj/.github/workflows/ci.yml")
        )
        assertFalse(result.success)
    }

    @Test
    fun plainWorkflowsDocIsNotProtected() {
        val result = ActionDispatcher(FakeFs()).applyFileAction(
            BoltAction.FileAction(content = "x", filePath = "docs/workflows/readme.md")
        )
        assertTrue(result.success)
    }

    // ---- ZIP writer validates before the first byte (Logic: ArtifactPackager) ----

    @Test
    fun zipToRejectsDuplicatesBeforeWritingAnyByte() {
        val out = ByteArrayOutputStream()
        val files = listOf(
            ArtifactFileSpec("a.txt", "one".toByteArray()),
            ArtifactFileSpec("./a.txt", "two".toByteArray()),
        )
        var threw = false
        try {
            ArtifactPackager.zipTo("x.zip", files.asSequence(), out)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(0, out.size())
    }
}
