package com.anvex.core.android.runtime.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VecStoreTest {

    @Test
    fun `packEmbeddings writes little endian floats`() {
        val vector = FloatArray(VecStore.EMBEDDING_DIM) { it.toFloat() }
        val packed = VecStore.packEmbeddings(listOf(vector, vector))
        assertEquals(2 * VecStore.EMBEDDING_DIM * 4, packed.size)
        val buffer = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, buffer.getFloat(0), 0.001f)
        assertEquals(383f, buffer.getFloat((VecStore.EMBEDDING_DIM - 1) * 4), 0.001f)
        assertEquals(0f, buffer.getFloat(VecStore.EMBEDDING_DIM * 4), 0.001f)
    }

    @Test
    fun `normalize produces a unit vector`() {
        val out = VecStore.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, out[0], 0.001f)
        assertEquals(0.8f, out[1], 0.001f)
    }

    @Test
    fun `unescape reverses the native escaping`() {
        assertEquals("a\tb", VecStore.unescape("a\\tb"))
        assertEquals("a\nb", VecStore.unescape("a\\nb"))
        assertEquals("a\\b", VecStore.unescape("a\\\\b"))
        assertEquals("plain", VecStore.unescape("plain"))
    }

    @Test
    fun `parseHits reads the line protocol`() {
        val raw = "id1\tsrc/Main.kt\t10\t20\t0.1234\tfun main() {\\n\\tgo()\\n}"
        val hits = VecStore.parseHits(raw)
        assertEquals(1, hits.size)
        assertEquals("id1", hits[0].chunkId)
        assertEquals("src/Main.kt", hits[0].path)
        assertEquals(10, hits[0].startLine)
        assertEquals(20, hits[0].endLine)
        assertEquals(0.1234f, hits[0].distance, 0.0001f)
        assertEquals("fun main() {\n\tgo()\n}", hits[0].text)
    }

    @Test
    fun `parseHits of empty output is empty`() {
        assertTrue(VecStore.parseHits("").isEmpty())
    }
}
