package com.anvex.core.android

import com.anvex.core.android.reliability.SelfHealingEngine
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SelfHealingEngineTest {
    @Test fun repairCreatesRuntimeDirectory() = runTest {
        val fs = object : ProjectFileSystem {
            private val dirs = mutableSetOf<String>()
            override fun mkdirs(relativePath: String) { dirs += relativePath }
            override fun exists(relativePath: String) = dirs.contains(relativePath)
            override fun isDirectory(relativePath: String) = dirs.contains(relativePath)
            override fun writeFile(relativePath: String, content: String) = Unit
            override fun writeFileBytes(relativePath: String, bytes: ByteArray) = Unit
            override fun readFile(relativePath: String): String? = null
            override fun readFileBytes(relativePath: String): ByteArray? = null
            override fun appendFile(relativePath: String, content: String) = Unit
            override fun delete(relativePath: String, recursive: Boolean) = dirs.remove(relativePath)
            override fun list(relativePath: String) = emptyList<ProjectFileSystem.FileEntry>()
            override fun move(from: String, to: String) = false
            override fun copy(from: String, to: String) = false
            override fun walk() = emptyList<String>()
        }
        val engine = SelfHealingEngine(fs)
        assertTrue(engine.repair("runtime-dir"))
        assertTrue(fs.exists(".anvex"))
    }
}
