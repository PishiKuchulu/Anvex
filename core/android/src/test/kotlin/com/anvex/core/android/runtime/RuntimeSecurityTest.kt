package com.anvex.core.android.runtime

import com.anvex.core.engine.ActionSafetyPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeSecurityTest {
    @Test fun aiShellRejectsCredentialFiles() = runTest {
        val runtime = AndroidShellRuntime(File("/tmp/does-not-matter"))
        val method = runtime.javaClass.getDeclaredMethod("isAiSafeCommand", String::class.java).apply { isAccessible = true }
        assertFalse(method.invoke(runtime, "cat local.properties") as Boolean)
        assertFalse(method.invoke(runtime, "grep -R password .") as Boolean)
        assertFalse(method.invoke(runtime, "cat .git/config") as Boolean)
        assertFalse(method.invoke(runtime, "ls\nrm -rf build") as Boolean)
        assertFalse(method.invoke(runtime, "ls\r\nrm -rf build") as Boolean)
    }

    @Test fun aiShellRejectsGlobsRecursiveGrepAndFindWrites() = runTest {
        val runtime = AndroidShellRuntime(File("/tmp/does-not-matter"))
        val method = runtime.javaClass.getDeclaredMethod("isAiSafeCommand", String::class.java).apply { isAccessible = true }
        assertFalse(method.invoke(runtime, "cat *") as Boolean)
        assertFalse(method.invoke(runtime, "cat *.properties") as Boolean)
        assertFalse(method.invoke(runtime, "grep -rn token .") as Boolean)
        assertFalse(method.invoke(runtime, "grep --recursive token .") as Boolean)
        assertFalse(method.invoke(runtime, "find . -name x -fprint out.sh") as Boolean)
        assertTrue(method.invoke(runtime, "ls") as Boolean)
        assertTrue(method.invoke(runtime, "grep pattern README.md") as Boolean)
    }

    @Test fun nodeScriptAlwaysRequiresApprovalForAi() = runTest {
        val runtime = NodeRuntime(File("/tmp/does-not-matter"))
        val method = runtime.javaClass.getDeclaredMethod("isAiSafe", List::class.java).apply { isAccessible = true }
        assertFalse(method.invoke(runtime, listOf("build.js")) as Boolean)
        assertTrue(method.invoke(runtime, listOf("--version")) as Boolean)
    }

    @Test fun safetyPolicyStillClassifiesCompoundCommands() {
        assertTrue(ActionSafetyPolicy.assess("cat local.properties; echo ok").requiresApproval)
    }
}
