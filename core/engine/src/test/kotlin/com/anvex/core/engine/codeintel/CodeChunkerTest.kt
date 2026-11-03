package com.anvex.core.engine.codeintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeChunkerTest {

    private fun source(lineCount: Int): String =
        (0 until lineCount).joinToString("\n") { "line $it" }

    @Test
    fun `empty text yields no chunks`() {
        assertTrue(CodeChunker.chunk("", emptyList()).isEmpty())
    }

    @Test
    fun `fold region becomes one chunk`() {
        val chunks = CodeChunker.chunk(source(20), listOf(FoldRange(2, 6)))
        val foldChunk = chunks.first { it.startLine == 2 }
        assertEquals(6, foldChunk.endLine)
        assertEquals("line 2\nline 3\nline 4\nline 5\nline 6", foldChunk.text)
    }

    @Test
    fun `uncovered text falls back to windows`() {
        val chunks = CodeChunker.chunk(source(10), emptyList(), windowLines = 4, overlapLines = 1)
        // windows: 0-3, 3-6, 6-9 (overlap of 1 line between consecutive windows)
        assertEquals(listOf(0, 3, 6), chunks.map { it.startLine })
        assertEquals(9, chunks.last().endLine)
    }

    @Test
    fun `huge fold splits at max chunk lines`() {
        val chunks = CodeChunker.chunk(source(200), listOf(FoldRange(0, 149)), maxChunkLines = 60)
        val foldChunks = chunks.filter { it.startLine < 150 }
        assertEquals(listOf(0, 60, 120), foldChunks.map { it.startLine })
        assertEquals(149, foldChunks.last().endLine)
    }

    @Test
    fun `nested folds keep the outermost`() {
        val chunks = CodeChunker.chunk(
            source(30),
            listOf(FoldRange(0, 20), FoldRange(3, 9)),
            maxChunkLines = 60,
        )
        assertEquals(1, chunks.count { it.startLine == 0 })
        assertTrue(chunks.none { it.startLine == 3 && it.endLine == 9 })
    }

    @Test
    fun `blank regions are dropped`() {
        val text = "a\n\n\n\nb"
        val chunks = CodeChunker.chunk(text, emptyList())
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].startLine)
        assertEquals(4, chunks[0].endLine)
    }
}
