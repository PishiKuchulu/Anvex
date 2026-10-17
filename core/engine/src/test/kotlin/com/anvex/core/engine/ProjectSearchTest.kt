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
}
