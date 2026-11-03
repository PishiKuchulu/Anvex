package com.anvex.core.android.runtime.ts

/**
 * One contiguous edit between two versions of a text, in the exact
 * shape tree-sitter's TSInputEdit needs: UTF-8 byte offsets and
 * (row, byte-column) points. Pure and unit-tested.
 */
data class TsEdit(
    val startByte: Int,
    val oldEndByte: Int,
    val newEndByte: Int,
    val startRow: Int,
    val startCol: Int,
    val oldEndRow: Int,
    val oldEndCol: Int,
    val newEndRow: Int,
    val newEndCol: Int,
) {
    companion object {
        /**
         * Computes the single contiguous edit turning [old] into [new]
         * via the longest common prefix/suffix. Returns null when the
         * texts are identical. Typing is exactly this shape (one region
         * changed), so incremental reparse covers the editing hot path.
         */
        fun between(old: String, new: String): TsEdit? {
            if (old == new) return null
            val minLen = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < minLen && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < (minLen - prefix) &&
                old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
            ) suffix++
            val oldEnd = old.length - suffix
            val newEnd = new.length - suffix
            val (sRow, sCol) = pointIn(old, prefix)
            val (oRow, oCol) = pointIn(old, oldEnd)
            val (nRow, nCol) = pointIn(new, newEnd)
            return TsEdit(
                startByte = utf8Length(old, 0, prefix),
                oldEndByte = utf8Length(old, 0, oldEnd),
                newEndByte = utf8Length(new, 0, newEnd),
                startRow = sRow, startCol = sCol,
                oldEndRow = oRow, oldEndCol = oCol,
                newEndRow = nRow, newEndCol = nCol,
            )
        }

        /** (row, byte-column) of the char offset [charOffset] in [text]. */
        internal fun pointIn(text: String, charOffset: Int): Pair<Int, Int> {
            var row = 0
            var lineStart = 0
            var i = 0
            while (i < charOffset) {
                if (text[i] == '\n') {
                    row++
                    lineStart = i + 1
                }
                i++
            }
            return row to utf8Length(text, lineStart, charOffset)
        }

        /** UTF-8 byte length of text[from, until). Surrogate pairs count 4. */
        internal fun utf8Length(text: String, from: Int, until: Int): Int {
            var bytes = 0
            var i = from
            while (i < until) {
                val c = text[i]
                when {
                    c.code < 0x80 -> bytes += 1
                    c.code < 0x800 -> bytes += 2
                    c.isHighSurrogate() && i + 1 < until && text[i + 1].isLowSurrogate() -> {
                        bytes += 4
                        i++
                    }
                    else -> bytes += 3
                }
                i++
            }
            return bytes
        }
    }
}
