package com.anvex.core.engine.codeintel

/** A chunk of a source file for embedding (P7/F6). Lines are 0-based. */
data class CodeChunk(
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

/**
 * Symbol-aware chunking for the RAG indexer: Tree-sitter fold ranges
 * (function/class bodies) become chunks first; the remaining uncovered
 * regions fall back to sliding line windows. Pure logic, JVM-tested.
 *
 * Guarantees: chunks are sorted by start line, non-overlapping except
 * for the fallback windows' deliberate overlap inside one region, and
 * blank-only chunks are dropped.
 */
object CodeChunker {

    fun chunk(
        text: String,
        folds: List<FoldRange>,
        maxChunkLines: Int = 60,
        windowLines: Int = 48,
        overlapLines: Int = 8,
    ): List<CodeChunk> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        val covered = BooleanArray(lines.size)
        val chunks = ArrayList<CodeChunk>()

        fun emit(start: Int, endInclusive: Int) {
            if (start > endInclusive || start >= lines.size) return
            val end = minOf(endInclusive, lines.size - 1)
            val slice = lines.subList(start, end + 1)
            if (slice.all { it.isBlank() }) return
            chunks += CodeChunk(start, end, slice.joinToString("\n"))
        }

        // 1. Fold regions (symbol bodies), outermost first, split if huge.
        val sortedFolds = folds
            .filter { it.endLine >= it.startLine && it.startLine < lines.size }
            .sortedWith(compareBy({ it.startLine }, { -it.endLine }))
        var lastFoldEnd = -1
        for (fold in sortedFolds) {
            if (fold.startLine <= lastFoldEnd) continue // nested: outermost wins
            var start = fold.startLine
            val end = minOf(fold.endLine, lines.size - 1)
            while (start <= end) {
                val pieceEnd = minOf(start + maxChunkLines - 1, end)
                emit(start, pieceEnd)
                start = pieceEnd + 1
            }
            for (i in fold.startLine..end) covered[i] = true
            lastFoldEnd = end
        }

        // 2. Uncovered regions → sliding windows with overlap.
        var i = 0
        while (i < lines.size) {
            if (covered[i]) {
                i++
                continue
            }
            var regionEnd = i
            while (regionEnd + 1 < lines.size && !covered[regionEnd + 1]) regionEnd++
            var start = i
            while (start <= regionEnd) {
                val pieceEnd = minOf(start + windowLines - 1, regionEnd)
                emit(start, pieceEnd)
                if (pieceEnd >= regionEnd) break
                start = pieceEnd - overlapLines + 1
            }
            i = regionEnd + 1
        }

        return chunks.sortedBy { it.startLine }
    }
}
