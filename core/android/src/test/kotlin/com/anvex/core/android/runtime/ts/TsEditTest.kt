package com.anvex.core.android.runtime.ts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TsEditTest {

    @Test
    fun identicalTextsHaveNoEdit() {
        assertNull(TsEdit.between("abc", "abc"))
    }

    @Test
    fun insertionAtEnd() {
        val edit = TsEdit.between("fun a()", "fun a() {}")!!
        assertEquals(7, edit.startByte)
        assertEquals(7, edit.oldEndByte)
        assertEquals(10, edit.newEndByte)
        assertEquals(0, edit.startRow)
        assertEquals(7, edit.startCol)
    }

    @Test
    fun replacementInMiddle() {
        val edit = TsEdit.between("val x = 1", "val x = 42")!!
        assertEquals(8, edit.startByte)
        assertEquals(9, edit.oldEndByte)
        assertEquals(10, edit.newEndByte)
    }

    @Test
    fun multilineEditPoints() {
        val edit = TsEdit.between("a\nb\nc", "a\nB\nc")!!
        assertEquals(2, edit.startByte)
        assertEquals(1, edit.startRow)
        assertEquals(0, edit.startCol)
        assertEquals(1, edit.oldEndRow)
        assertEquals(1, edit.oldEndCol)
        assertEquals(1, edit.newEndRow)
        assertEquals(1, edit.newEndCol)
    }

    @Test
    fun utf8OffsetsCountBytesNotChars() {
        // 'é' is 2 UTF-8 bytes: the edit after it starts at byte 3.
        val edit = TsEdit.between("aéx", "aéy")!!
        assertEquals(3, edit.startByte)
        assertEquals(4, edit.oldEndByte)
        assertEquals(4, edit.newEndByte)
        assertEquals(3, edit.startCol) // TS columns are bytes: 'a' + 'é'(2)
    }
}
