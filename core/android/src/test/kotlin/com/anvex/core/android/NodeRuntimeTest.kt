package com.anvex.core.android

import com.anvex.core.android.runtime.NodeRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NodeRuntimeTest {
    @Test fun missingNodeIsReportedWithoutThrowing() = runTest {
        val root = createTempDir()
        val runtime = NodeRuntime(root, nodeCandidates = listOf(File(root, "missing-node")))
        val result = runtime.execute(listOf("--version"))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("not installed"))
        root.deleteRecursively()
    }
}
