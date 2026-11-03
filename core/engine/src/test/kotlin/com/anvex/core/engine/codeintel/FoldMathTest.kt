package com.anvex.core.engine.codeintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoldMathTest {

    private val sample = "a {\n x\n}\n" // lines: 0 "a {", 1 " x", 2 "}", 3 ""

    @Test
    fun noFoldsMeansNoProjection() {
        assertNull(FoldProjection.of(sample, emptySet()))
        assertNull(FoldProjection.of(sample, setOf(FoldRange(0, 0))))
    }

    @Test
    fun foldedRegionKeepsFirstLineWithMarker() {
        val p = FoldProjection.of(sample, setOf(FoldRange(0, 2)))!!
        assertEquals("a { …\n", p.visibleText)
        assertEquals(listOf(0, 3), p.visibleLines)
    }

    @Test
    fun offsetsRoundTripOutsideFolds() {
        val p = FoldProjection.of(sample, setOf(FoldRange(0, 2)))!!
        assertEquals(3, p.originalToVisible(3)) // after "a {"
        assertEquals(6, p.originalToVisible(9)) // end of text
        assertEquals(3, p.visibleToOriginal(3))
        assertEquals(9, p.visibleToOriginal(6))
    }

    @Test
    fun offsetsInsideFoldClampToMarker() {
        val p = FoldProjection.of(sample, setOf(FoldRange(0, 2)))!!
        assertEquals(5, p.originalToVisible(5)) // inside removed region -> after "…"
        assertEquals(3, p.visibleToOriginal(5)) // on the marker -> end of first line
    }

    @Test
    fun nestedFoldsKeepOutermost() {
        val text = "a\nb\nc\nd\ne"
        val p = FoldProjection.of(text, setOf(FoldRange(0, 4), FoldRange(1, 2)))!!
        assertEquals("a …", p.visibleText)
        assertEquals(listOf(0), p.visibleLines)
    }

    @Test
    fun middleFoldKeepsSurroundingLines() {
        val text = "head\nfun {\n body\n}\ntail"
        val p = FoldProjection.of(text, setOf(FoldRange(1, 3)))!!
        assertEquals("head\nfun { …\ntail", p.visibleText)
        assertEquals(listOf(0, 1, 4), p.visibleLines)
        // 't' of tail: original offset 19, visible offset 13.
        assertEquals(13, p.originalToVisible(19))
        assertEquals(19, p.visibleToOriginal(13))
    }
}
