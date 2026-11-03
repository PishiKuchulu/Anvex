package com.anvex.core.android.runtime.ts

import com.anvex.core.engine.codeintel.FoldRange
import com.anvex.core.engine.codeintel.HighlightKind
import com.anvex.core.engine.codeintel.HighlightSpan
import com.anvex.core.engine.codeintel.ParsedDocument

/**
 * Decodes the ts_bridge.cpp line protocol into a [ParsedDocument] with
 * UTF-16 char offsets. The bridge speaks UTF-8 BYTE offsets; span
 * boundaries always land on char boundaries (they are token edges), so
 * a boundary map converts them exactly. Pure and unit-tested.
 */
object TsProtocol {

    fun decode(raw: String, source: String): ParsedDocument? {
        if (raw.isEmpty()) return null
        val byteToChar = byteToCharMap(source)
        var handle = 0L
        val spans = ArrayList<HighlightSpan>()
        val folds = ArrayList<FoldRange>()
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            val parts = line.split(' ')
            when (parts[0]) {
                "H" -> handle = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                "S" -> {
                    if (parts.size != 4) continue
                    val startByte = parts[1].toIntOrNull() ?: continue
                    val endByte = parts[2].toIntOrNull() ?: continue
                    val kind = HighlightKind.fromWire(parts[3].toIntOrNull() ?: -1) ?: continue
                    val start = byteToChar[startByte] ?: continue
                    val end = byteToChar[endByte] ?: continue
                    if (end > start) spans += HighlightSpan(start, end, kind)
                }
                "F" -> {
                    if (parts.size != 3) continue
                    val startRow = parts[1].toIntOrNull() ?: continue
                    val endRow = parts[2].toIntOrNull() ?: continue
                    if (endRow > startRow) folds += FoldRange(startRow, endRow)
                }
            }
        }
        if (handle <= 0) return null
        return ParsedDocument(handle, spans, folds)
    }

    /**
     * Maps every UTF-8 byte offset that is a char boundary to its
     * UTF-16 char index. Includes both 0 and the total byte length.
     */
    internal fun byteToCharMap(text: String): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        var bytes = 0
        var i = 0
        map[0] = 0
        while (i < text.length) {
            val c = text[i]
            val charLen: Int
            when {
                c.code < 0x80 -> { bytes += 1; charLen = 1 }
                c.code < 0x800 -> { bytes += 2; charLen = 1 }
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    bytes += 4; charLen = 2
                }
                else -> { bytes += 3; charLen = 1 }
            }
            i += charLen
            map[bytes] = i
        }
        return map
    }
}
