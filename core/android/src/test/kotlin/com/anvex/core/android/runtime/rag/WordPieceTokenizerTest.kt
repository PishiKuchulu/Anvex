package com.anvex.core.android.runtime.rag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {

    /** Vocab with the real special-token ids plus a few fixture words. */
    private fun fixtureVocab(): List<String> {
        val lines = MutableList(103) { "[unused$it]" }
        lines[0] = "[PAD]"
        lines[100] = "[UNK]"
        lines[101] = "[CLS]"
        lines[102] = "[SEP]"
        return lines + listOf(
            "hello", // 103
            "world", // 104
            "un", // 105
            "##aff", // 106
            "##able", // 107
            "!", // 108
            "fun", // 109
            "main", // 110
            "(", // 111
            ")", // 112
        )
    }

    private val tokenizer = WordPieceTokenizer(fixtureVocab())

    @Test
    fun `simple sentence encodes with cls and sep`() {
        val (ids, mask) = tokenizer.encode("Hello world!")
        assertArrayEquals(intArrayOf(101, 103, 104, 108, 102), ids)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1), mask)
    }

    @Test
    fun `wordpiece splits with continuation markers`() {
        val (ids, _) = tokenizer.encode("unaffable")
        assertArrayEquals(intArrayOf(101, 105, 106, 107, 102), ids)
    }

    @Test
    fun `unknown word becomes a single unk`() {
        val (ids, _) = tokenizer.encode("hello zqxjkvb world")
        assertArrayEquals(intArrayOf(101, 103, 100, 104, 102), ids)
    }

    @Test
    fun `code punctuation is isolated`() {
        val (ids, _) = tokenizer.encode("fun main()")
        assertArrayEquals(intArrayOf(101, 109, 110, 111, 112, 102), ids)
    }

    @Test
    fun `long input truncates to max len ending with sep`() {
        val (ids, _) = tokenizer.encode(List(600) { "hello" }.joinToString(" "))
        assertEquals(WordPieceTokenizer.MAX_LEN, ids.size)
        assertEquals(102, ids[ids.size - 1])
    }
}
