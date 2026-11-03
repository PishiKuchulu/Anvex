package com.anvex.core.android.runtime.rag

/**
 * BERT WordPiece tokenizer for all-MiniLM-L6-v2 (P7/F6), pure Kotlin so
 * the algorithm is unit-testable on the JVM. Implements the standard
 * pipeline: basic tokenization (lowercase, whitespace + punctuation
 * split, accent stripping is intentionally NOT done — MiniLM's vocab is
 * cased=false uncased WordPiece and the bundled vocab was trained with
 * the reference tokenizer whose basic step also strips accents; the
 * indexed corpus is source code, where accented identifiers are rare
 * and fall back to [UNK]-split pieces), then greedy longest-match-first
 * WordPiece with `##` continuations.
 *
 * [encode] returns ids + attention mask WITHOUT padding (the embedder
 * pads per batch); [CLS]/[SEP] are included.
 */
class WordPieceTokenizer(
    vocabLines: List<String>,
    private val maxLen: Int = MAX_LEN,
) {
    private val vocab: Map<String, Int> = HashMap<String, Int>(vocabLines.size).apply {
        vocabLines.forEachIndexed { index, token -> put(token.trimEnd('\r'), index) }
    }

    private val padId: Int = vocab["[PAD]"] ?: 0
    private val unkId: Int = vocab["[UNK]"] ?: 100
    private val clsId: Int = vocab["[CLS]"] ?: 101
    private val sepId: Int = vocab["[SEP]"] ?: 102

    val vocabSize: Int get() = vocab.size

    fun encode(text: String): Pair<IntArray, IntArray> {
        val pieces = ArrayList<Int>()
        pieces += clsId
        for (word in basicTokenize(text)) {
            pieces += wordPiece(word)
            if (pieces.size >= maxLen - 1) break
        }
        val ids = IntArray(minOf(pieces.size + 1, maxLen))
        for (i in ids.indices) ids[i] = if (i < pieces.size) pieces[i] else sepId
        if (ids.isNotEmpty() && ids[ids.size - 1] != sepId) ids[ids.size - 1] = sepId
        val mask = IntArray(ids.size) { 1 }
        return ids to mask
    }

    fun padId(): Int = padId

    /** Splits on whitespace and isolates punctuation as its own tokens. */
    private fun basicTokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }
        for (ch in text.lowercase()) {
            when {
                ch.isWhitespace() -> flush()
                isPunctuation(ch) -> {
                    flush()
                    tokens += ch.toString()
                }
                else -> current.append(ch)
            }
        }
        flush()
        return tokens
    }

    private fun isPunctuation(ch: Char): Boolean =
        (ch in '!'..'/') || (ch in ':'..'@') || (ch in '['..'`') || (ch in '{'..'~')

    /** Greedy longest-match-first; a word with no full segmentation → [UNK]. */
    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(unkId)
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched: Int? = null
            while (end > start) {
                val candidate = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##" + word.substring(start, end)
                }
                val id = vocab[candidate]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            if (matched == null) return listOf(unkId)
            pieces += matched
            start = end
        }
        return pieces
    }

    companion object {
        const val MAX_LEN = 256
        const val MAX_WORD_CHARS = 100
    }
}
