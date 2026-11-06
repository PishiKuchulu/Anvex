package com.anvex.core.engine

/**
 * Seam merge for chunked file writes (append continuations).
 *
 * A file too long for one model response is written in parts: the first
 * part is a normal write, later parts arrive as append chunks. The model
 * is told to continue exactly after the saved tail, but models often
 * repeat the last line or two. [merge] removes that repetition — but
 * ONLY when the overlap is unambiguous: a whole number of lines, at
 * least [MIN_OVERLAP] characters, matching the existing file's suffix
 * exactly. Anything weaker (a partial line, a short common phrase) is
 * left untouched, because stripping real content would corrupt the file
 * while a duplicated line is merely untidy.
 */
object AppendMerge {

    /**
     * Overlaps shorter than this are treated as coincidence, never stripped.
     * Twelve characters is roughly the shortest distinctive full line (a
     * Markdown heading like "## Conclusion"); the whole-line + exact-suffix
     * constraints carry the rest of the safety.
     */
    const val MIN_OVERLAP = 12

    /** Only the tail of the existing file can participate in a seam. */
    const val MAX_OVERLAP_SCAN = 4096

    fun merge(existing: String, chunk: String): String {
        if (existing.isEmpty() || chunk.isEmpty()) return existing + chunk
        val overlap = seamOverlap(existing, chunk)
        return if (overlap == 0) existing + chunk else existing + chunk.substring(overlap)
    }

    /**
     * Length of the longest chunk prefix that repeats whole lines at the
     * end of [existing], or 0 when there is no trustworthy seam. Candidate
     * seams start at a line boundary in [existing] (longest first) and
     * must end at a line boundary in [chunk] (or consume it entirely).
     */
    fun seamOverlap(existing: String, chunk: String): Int {
        if (existing.isEmpty() || chunk.isEmpty()) return 0
        val windowStart = maxOf(0, existing.length - MAX_OVERLAP_SCAN)
        // Candidate seam starts, ascending (so overlap lengths descend):
        // the window start itself when it is the file start, plus the
        // position right after every newline inside the window.
        val starts = ArrayList<Int>()
        if (windowStart == 0) starts.add(0)
        var nl = existing.indexOf('\n', windowStart)
        while (nl >= 0 && nl + 1 < existing.length) {
            starts.add(nl + 1)
            nl = existing.indexOf('\n', nl + 1)
        }
        for (start in starts) {
            val len = existing.length - start
            if (len < MIN_OVERLAP) break // later starts only get shorter
            if (len > chunk.length) continue
            val endsAtLineBoundary = len == chunk.length || chunk[len - 1] == '\n'
            if (endsAtLineBoundary && existing.regionMatches(start, chunk, 0, len)) {
                return len
            }
        }
        return 0
    }
}
