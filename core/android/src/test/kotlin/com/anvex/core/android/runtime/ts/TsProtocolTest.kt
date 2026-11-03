package com.anvex.core.android.runtime.ts

import com.anvex.core.engine.codeintel.HighlightKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TsProtocolTest {

    @Test
    fun decodesHandleSpansAndFolds() {
        val doc = TsProtocol.decode("H 7\nS 0 3 1\nS 4 9 2\nF 0 5\n", "fun x() {}")!!
        assertEquals(7L, doc.handle)
        assertEquals(2, doc.spans.size)
        assertEquals(HighlightKind.KEYWORD, doc.spans[0].kind)
        assertEquals(0, doc.spans[0].start)
        assertEquals(3, doc.spans[0].end)
        assertEquals(HighlightKind.STRING, doc.spans[1].kind)
        assertEquals(1, doc.folds.size)
        assertEquals(0, doc.folds[0].startLine)
        assertEquals(5, doc.folds[0].endLine)
    }

    @Test
    fun byteOffsetsConvertAcrossMultibyteChars() {
        // "é " is 3 bytes for 2 chars; a span over bytes 3..6 covers "fun".
        val doc = TsProtocol.decode("H 1\nS 3 6 1\n", "é fun")!!
        assertEquals(2, doc.spans[0].start)
        assertEquals(5, doc.spans[0].end)
    }

    @Test
    fun emptyOrHandlelessInputIsNull() {
        assertNull(TsProtocol.decode("", "x"))
        assertNull(TsProtocol.decode("S 0 1 1\n", "x"))
    }

    @Test
    fun unknownKindsAndMalformedLinesAreSkipped() {
        val doc = TsProtocol.decode("H 2\nS 0 1 99\njunk\nS 1 2 3\n", "ab")!!
        assertEquals(1, doc.spans.size)
        assertEquals(HighlightKind.COMMENT, doc.spans[0].kind)
    }
}
