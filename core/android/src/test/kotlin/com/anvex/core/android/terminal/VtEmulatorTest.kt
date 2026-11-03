package com.anvex.core.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VtEmulatorTest {

    private val esc = "\u001B"

    @Test
    fun plainTextLandsOnScreen() {
        val vt = VtEmulator(cols = 10, rows = 3)
        vt.feed("hello")
        assertEquals("hello", vt.rowText(vt.screenRows()[0]))
        assertEquals(5, vt.cursorX)
    }

    @Test
    fun carriageReturnOverwrites() {
        val vt = VtEmulator(cols = 10, rows = 3)
        vt.feed("abcdef\rXY")
        assertEquals("XYcdef", vt.rowText(vt.screenRows()[0]))
    }

    @Test
    fun lineFeedScrollsIntoScrollback() {
        val vt = VtEmulator(cols = 5, rows = 2)
        vt.feed("one\r\ntwo\r\nthree")
        assertEquals(listOf("one"), vt.scrollbackRows().map { vt.rowText(it) })
        assertEquals("three", vt.rowText(vt.screenRows()[1]))
    }

    @Test
    fun wrapAtLastColumn() {
        val vt = VtEmulator(cols = 4, rows = 3)
        vt.feed("abcdef")
        assertEquals("abcd", vt.rowText(vt.screenRows()[0]))
        assertEquals("ef", vt.rowText(vt.screenRows()[1]))
    }

    @Test
    fun csiCursorPositioning() {
        val vt = VtEmulator(cols = 10, rows = 5)
        vt.feed("$esc[3;4HZ")
        assertEquals("Z", vt.screenRows()[2][3].ch.toString())
    }

    @Test
    fun sgrColorsAndReset() {
        val vt = VtEmulator(cols = 10, rows = 2)
        vt.feed("${esc}[31;1mR${esc}[0mN")
        val row = vt.screenRows()[0]
        assertEquals(1, row[0].fg)
        assertTrue(row[0].bold)
        assertEquals(-1, row[1].fg)
    }

    @Test
    fun sgr256AndRgb() {
        val vt = VtEmulator(cols = 10, rows = 2)
        vt.feed("${esc}[38;5;196mA${esc}[38;2;1;2;3mB")
        val row = vt.screenRows()[0]
        assertEquals(196, row[0].fg)
        assertEquals(0x1000000 or 0x010203, row[1].fg)
    }

    @Test
    fun eraseInLine() {
        val vt = VtEmulator(cols = 8, rows = 2)
        vt.feed("abcdefgh\r$esc[K")
        assertEquals("", vt.rowText(vt.screenRows()[0]))
    }

    @Test
    fun promptLineIsReadable() {
        val vt = VtEmulator(cols = 40, rows = 4)
        vt.feed("Continue? [y/n] ")
        assertEquals("Continue? [y/n]", vt.cursorLineText())
    }

    @Test
    fun scrollbackIsCapped() {
        val vt = VtEmulator(cols = 4, rows = 1, scrollbackCap = 3)
        for (i in 0 until 10) vt.feed("l$i\r\n")
        assertTrue(vt.scrollbackSize <= 3)
    }
}
