package com.anvex.core.engine

import com.anvex.core.model.MutationSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-03/H-07: renaming or copying a secret file is a read bypass by
 * another name, and the old copy path checked the destination only —
 * so `cp .env out.txt` and `cp .git/config out.txt` both sailed
 * through. Workflows and the Pi agent dir join the protected set.
 */
class DispatcherProtectionTest {

    private fun dispatcherWithSecrets(): ActionDispatcher {
        val fs = FakeFs()
        fs.writeFile(".env", "OPENAI_KEY=sk-live")
        fs.writeFile(".git/config", "[core]")
        fs.writeFile("notes.txt", "hi")
        return ActionDispatcher(fs)
    }

    @Test
    fun `rename of a secret file is refused`() {
        val d = dispatcherWithSecrets()
        val r = d.rename(".env", "config.txt", MutationSource.AI_SHELL_COMMAND)
        assertFalse(r.success)
        assertTrue(r.error!!.contains("Refused"))
    }

    @Test
    fun `copy from a secret or protected file is refused`() {
        val d = dispatcherWithSecrets()
        assertFalse(d.copyFile(".env", "config.txt", MutationSource.AI_SHELL_COMMAND).success)
        val git = d.copyFile(".git/config", "out.txt", MutationSource.AI_SHELL_COMMAND)
        assertFalse(git.success)
        assertTrue(git.error!!.contains("Protected"))
    }

    @Test
    fun `copy to a secret destination is refused`() {
        val d = dispatcherWithSecrets()
        assertFalse(d.copyFile("notes.txt", ".env", MutationSource.AI_SHELL_COMMAND).success)
    }

    @Test
    fun `ordinary rename still works`() {
        val d = dispatcherWithSecrets()
        assertTrue(d.rename("notes.txt", "notes2.txt", MutationSource.AI_SHELL_COMMAND).success)
    }

    @Test
    fun `workflow and pi agent dir writes are refused`() {
        val d = dispatcherWithSecrets()
        assertFalse(
            d.writeFile(".github/workflows/ci.yml", "x", MutationSource.AI_FILE_ACTION).success
        )
        assertFalse(
            d.writeFile(".anvex-pi/agent/gateway.js", "x", MutationSource.AI_FILE_ACTION).success
        )
    }

    @Test
    fun `translated cat of a secret is refused`() {
        val d = dispatcherWithSecrets()
        val results = d.applyShellAction(BoltAction.ShellAction("cat .env"), "shell")
        assertTrue(results.isNotEmpty())
        assertFalse(results.first().success)
        assertTrue(results.first().error!!.contains("Refused"))
    }

    @Test
    fun `translated cat of an ordinary file still works`() {
        val d = dispatcherWithSecrets()
        val results = d.applyShellAction(BoltAction.ShellAction("cat notes.txt"), "shell")
        assertTrue(results.first().success)
        assertTrue(results.first().output!!.contains("hi"))
    }
}
