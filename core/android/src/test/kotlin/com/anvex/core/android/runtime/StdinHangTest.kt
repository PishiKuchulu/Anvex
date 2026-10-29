package com.anvex.core.android.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L-04: commands that read stdin or follow forever hang the AI terminal
 * until timeout. The shared gate must reject them in both the shell
 * runtime and the interactive terminal session.
 */
class StdinHangTest {

    @Test
    fun `bare cat and head read stdin and must be rejected`() {
        assertTrue(isStdinHangingCommand("cat"))
        assertTrue(isStdinHangingCommand("head"))
        assertTrue(isStdinHangingCommand("cat -n"))
    }

    @Test
    fun `cat and head with a file operand are fine`() {
        assertFalse(isStdinHangingCommand("cat README.md"))
        assertFalse(isStdinHangingCommand("head -n 5 README.md"))
    }

    @Test
    fun `grep needs pattern plus file`() {
        assertTrue(isStdinHangingCommand("grep"))
        assertTrue(isStdinHangingCommand("grep pattern"))
        assertFalse(isStdinHangingCommand("grep pattern file.txt"))
        assertFalse(isStdinHangingCommand("grep -i pattern file.txt"))
    }

    @Test
    fun `tail follow never terminates`() {
        assertTrue(isStdinHangingCommand("tail -f app.log"))
        assertTrue(isStdinHangingCommand("tail --follow app.log"))
        assertTrue(isStdinHangingCommand("tail -n 20 -f app.log"))
        assertFalse(isStdinHangingCommand("tail -n 20 app.log"))
    }

    @Test
    fun `unrelated commands are unaffected`() {
        assertFalse(isStdinHangingCommand("ls -la"))
        assertFalse(isStdinHangingCommand("pwd"))
        assertFalse(isStdinHangingCommand("find . -name foo"))
    }
}
