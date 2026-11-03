package com.anvex.core.engine.codeintel

/**
 * Fold projection math (F2a, P4): given a source text and a set of
 * folded [FoldRange]s, computes the VISIBLE text — each folded region
 * keeps its first line with an ellipsis marker appended, its remaining
 * lines are removed — plus bidirectional offset mapping between
 * original and visible coordinates. Pure and unit-tested; the Compose
 * VisualTransformation in :app is a thin shell over this.
 *
 * Line model: lines split on '\n'; a line's span includes its trailing
 * newline except for the last line. Fold ranges are 0-based lines and
 * collapse lines (startLine+1 .. endLine).
 */
class FoldProjection private constructor(
    val visibleText: String,
    /** Original line numbers that remain visible, in order. */
    val visibleLines: List<Int>,
    private val folds: List<FoldSpan>,
) {

    /** Per-fold geometry in original offsets + the marker's visible offset. */
    private data class FoldSpan(
        val firstLineContentEnd: Int, // exclusive end of the fold's first-line content (before '\n')
        val removeStart: Int,         // first removed char (start of line startLine+1)
        val removeEnd: Int,           // exclusive end of the removed region
        val markerVisibleStart: Int,  // visible offset where the ellipsis begins
    ) {
        val removedLength: Int get() = removeEnd - removeStart
    }

    /** Maps an original char offset to its visible offset; offsets inside a folded region clamp to just after its marker. */
    fun originalToVisible(offset: Int): Int {
        var removedBefore = 0
        var markersBefore = 0
        for (f in folds) {
            if (offset <= f.firstLineContentEnd) break
            if (offset < f.removeEnd) {
                return f.firstLineContentEnd - removedBefore +
                    markersBefore * ELLIPSIS.length + ELLIPSIS.length
            }
            removedBefore += f.removedLength
            markersBefore++
        }
        return offset - removedBefore + markersBefore * ELLIPSIS.length
    }

    /** Maps a visible char offset back to the original text; offsets inside a marker land at the end of the fold's first line. */
    fun visibleToOriginal(offset: Int): Int {
        var removedBefore = 0
        var markersBefore = 0
        for (f in folds) {
            val markerStart = f.markerVisibleStart
            val markerEnd = markerStart + ELLIPSIS.length
            if (offset <= markerStart) break
            if (offset <= markerEnd) return f.firstLineContentEnd
            removedBefore += f.removedLength
            markersBefore++
        }
        return offset + removedBefore - markersBefore * ELLIPSIS.length
    }

    companion object {
        const val ELLIPSIS = " …"

        /** Builds the projection; returns null when nothing valid is folded. */
        fun of(text: String, folded: Set<FoldRange>): FoldProjection? {
            if (folded.isEmpty()) return null
            val lineStarts = ArrayList<Int>()
            lineStarts += 0
            for (i in text.indices) if (text[i] == '\n') lineStarts += i + 1
            val lineCount = lineStarts.size
            fun lineEnd(line: Int): Int =
                if (line + 1 < lineCount) lineStarts[line + 1] else text.length
            fun contentEnd(line: Int): Int {
                val end = lineEnd(line)
                return if (end > lineStarts[line] && text[end - 1] == '\n') end - 1 else end
            }

            val valid = folded
                .filter { it.startLine in 0 until lineCount && it.endLine > it.startLine }
                .sortedBy { it.startLine }
            if (valid.isEmpty()) return null
            // Overlapping/nested folds: keep the outermost only.
            val outer = ArrayList<FoldRange>()
            var coveredUntil = -1
            for (f in valid) {
                if (f.startLine > coveredUntil) {
                    outer += f
                    coveredUntil = f.endLine
                }
            }

            val removedLines = HashSet<Int>()
            for (f in outer) {
                for (l in (f.startLine + 1)..minOf(f.endLine, lineCount - 1)) removedLines += l
            }

            val sb = StringBuilder()
            val visibleLines = ArrayList<Int>()
            val spans = ArrayList<FoldSpan>()
            val foldByStartLine = outer.associateBy { it.startLine }
            var first = true
            for (line in 0 until lineCount) {
                if (line in removedLines) continue
                if (!first) sb.append('\n')
                first = false
                visibleLines += line
                sb.append(text, lineStarts[line], contentEnd(line))
                val fold = foldByStartLine[line]
                if (fold != null) {
                    spans += FoldSpan(
                        firstLineContentEnd = contentEnd(line),
                        removeStart = lineEnd(line),
                        removeEnd = lineEnd(minOf(fold.endLine, lineCount - 1)),
                        markerVisibleStart = sb.length,
                    )
                    sb.append(ELLIPSIS)
                }
            }
            return FoldProjection(sb.toString(), visibleLines, spans)
        }
    }
}
