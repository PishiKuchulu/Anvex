package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSearchTest {
    @Test fun `search ignores generated roots and binary content`() {
        val fs = FakeFs().apply {
            writeFile("src/Main.kt", "hello\nhello")
            writeFile("build/generated.kt", "hello")
            writeFileBytes("src/image.bin", byteArrayOf(0, 1, 2))
        }
        assertEquals(2, ProjectSearch.search(fs, "hello").size)
    }

    @Test fun `replace respects file size guard through metadata`() {
        val fs = FakeFs().apply { writeFile("src/a.txt", "a b") }
        val result = ProjectSearch.replaceAll(fs, "a", "b")
        assertEquals(1, result.single().replacements)
        assertEquals("b b", fs.readFile("src/a.txt"))
    }

    @Test fun `search and replace take sizes from the walk without per-file lookups`() {
        // Regression (2026-11-03): search/replaceAll asked fileSize() for
        // every walked path — on SAF workspaces one provider round-trip
        // per file in the project. Sizes must ride along with the walk.
        var fileSizeCalls = 0
        val backing = FakeFs().apply {
            writeFile("src/a.txt", "needle here")
            writeFile("src/b.txt", "nothing")
        }
        val fs = object : ProjectFileSystem by backing {
            override fun walkWithSizes(): List<Pair<String, Long?>> =
                backing.files.map { (path, content) -> path to content.toByteArray().size.toLong() }

            override fun fileSize(relativePath: String): Long? {
                fileSizeCalls++
                return backing.fileSize(relativePath)
            }
        }
        assertEquals(1, ProjectSearch.search(fs, "needle").size)
        assertEquals(1, ProjectSearch.replaceAll(fs, "needle", "pin").size)
        assertEquals(0, fileSizeCalls)
    }
}
