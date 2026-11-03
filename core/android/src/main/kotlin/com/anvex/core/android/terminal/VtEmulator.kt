package com.anvex.core.android.terminal

/**
 * Bounded VT100/ANSI terminal emulator (F3, P2).
 *
 * Pure Kotlin by design: the parser is where terminal bugs live, so this
 * class has zero Android dependencies and is fully JVM-unit-testable. It
 * implements the subset real CLI programs actually use — printable text
 * with autowrap, CR/LF/BS/TAB, CSI cursor movement, erase in display/line,
 * insert/delete lines and characters, scroll regions, SGR (16/256/RGB
 * colours, bold, underline, reverse), saved cursor, and the alternate
 * screen (basic swap, no scrollback merge).
 *
 * Bounds (OOM discipline): the scrollback is capped at [scrollbackCap]
 * lines, the grid is fixed at construction/resize, and escape sequences
 * are length-capped — a hostile or runaway program cannot grow memory.
 * Colour encoding in cells: -1 = default, 0..255 = palette index,
 * >= 0x1000000 = RGB packed as 0x1000000 | rrggbb.
 */
data class TermCell(
    val ch: Char = ' ',
    val fg: Int = -1,
    val bg: Int = -1,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
)

class VtEmulator(
    cols: Int,
    rows: Int,
    private val scrollbackCap: Int = 2_000,
) {
    var cols: Int = cols.coerceIn(1, 500)
        private set
    var rows: Int = rows.coerceIn(1, 200)
        private set

    private var screen: Array<MutableList<TermCell>> = newGrid()
    private var altScreen: Array<MutableList<TermCell>>? = null
    private val scrollback = ArrayDeque<List<TermCell>>()

    private var cx = 0
    private var cy = 0
    private var savedX = 0
    private var savedY = 0
    private var wrapPending = false

    private var curFg = -1
    private var curBg = -1
    private var curBold = false
    private var curUnderline = false
    private var curReverse = false

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private enum class State { GROUND, ESC, CSI, OSC, CHARSET }
    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private var csiPrivate = false

    val cursorX: Int get() = cx
    val cursorY: Int get() = cy
    val scrollbackSize: Int get() = scrollback.size

    private fun newGrid(): Array<MutableList<TermCell>> =
        Array(rows) { MutableList(cols) { TermCell() } }

    // ------------------------------------------------------------- input

    fun feed(text: String) {
        for (ch in text) feed(ch)
    }

    fun feed(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESC -> {
                state = when (ch) {
                    '[' -> { csiParams.setLength(0); csiPrivate = false; State.CSI }
                    ']' -> State.OSC
                    '(', ')', '*', '+' -> State.CHARSET
                    '7' -> { saveCursor(); State.GROUND }
                    '8' -> { restoreCursor(); State.GROUND }
                    'c' -> { reset(); State.GROUND }
                    'D' -> { lineFeed(); State.GROUND }
                    'E' -> { cx = 0; lineFeed(); State.GROUND }
                    'M' -> { reverseIndex(); State.GROUND }
                    else -> State.GROUND
                }
            }
            State.CSI -> {
                if (ch in '@'..'~') {
                    csi(ch)
                    state = State.GROUND
                } else if (csiParams.length < 64) {
                    if (ch == '?') csiPrivate = true else csiParams.append(ch)
                }
            }
            State.OSC -> if (ch == '\u0007' || ch == '\u001B') state = State.GROUND
            State.CHARSET -> state = State.GROUND
        }
    }

    private fun ground(ch: Char) {
        when (ch) {
            '\u001B' -> state = State.ESC
            '\r' -> { cx = 0; wrapPending = false }
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\b' -> { if (cx > 0) cx--; wrapPending = false }
            '\t' -> { cx = minOf(cols - 1, (cx / 8 + 1) * 8); wrapPending = false }
            '\u0007', '\u0000' -> Unit // BEL + NUL: ignored
            else -> if (ch >= ' ') putChar(ch)
        }
    }

    private fun putChar(ch: Char) {
        if (wrapPending) {
            wrapPending = false
            cx = 0
            lineFeed()
        }
        screen[cy][cx] = TermCell(ch, curFg, curBg, curBold, curUnderline, curReverse)
        if (cx == cols - 1) wrapPending = true else cx++
    }

    private fun lineFeed() {
        wrapPending = false
        if (cy == scrollBottom) scrollUp(scrollTop, scrollBottom, 1) else if (cy < rows - 1) cy++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cy == scrollTop) scrollDown(scrollTop, scrollBottom, 1) else if (cy > 0) cy--
    }

    private fun scrollUp(top: Int, bottom: Int, n: Int) {
        repeat(n) {
            val removed = screen[top]
            if (top == 0 && altScreen == null) {
                scrollback.addLast(removed.toList())
                while (scrollback.size > scrollbackCap) scrollback.removeFirst()
            }
            for (r in top until bottom) screen[r] = screen[r + 1]
            screen[bottom] = MutableList(cols) { TermCell() }
        }
    }

    private fun scrollDown(top: Int, bottom: Int, n: Int) {
        repeat(n) {
            for (r in bottom downTo top + 1) screen[r] = screen[r - 1]
            screen[top] = MutableList(cols) { TermCell() }
        }
    }

    private fun saveCursor() { savedX = cx; savedY = cy }
    private fun restoreCursor() { cx = savedX.coerceIn(0, cols - 1); cy = savedY.coerceIn(0, rows - 1) }

    private fun reset() {
        screen = newGrid()
        scrollback.clear()
        cx = 0; cy = 0; wrapPending = false
        curFg = -1; curBg = -1; curBold = false; curUnderline = false; curReverse = false
        scrollTop = 0; scrollBottom = rows - 1
    }

    // ------------------------------------------------------------- CSI

    private fun params(): List<Int> =
        if (csiParams.isEmpty()) emptyList()
        else csiParams.toString().split(';').map { it.toIntOrNull() ?: 0 }

    private fun param(i: Int, default: Int): Int {
        val p = params()
        return if (i < p.size && p[i] != 0) p[i] else default
    }

    private fun csi(final: Char) {
        when (final) {
            'A' -> cy = (cy - param(0, 1)).coerceAtLeast(0)
            'B' -> cy = (cy + param(0, 1)).coerceAtMost(rows - 1)
            'C' -> cx = (cx + param(0, 1)).coerceAtMost(cols - 1)
            'D' -> cx = (cx - param(0, 1)).coerceAtLeast(0)
            'E' -> { cy = (cy + param(0, 1)).coerceAtMost(rows - 1); cx = 0 }
            'F' -> { cy = (cy - param(0, 1)).coerceAtLeast(0); cx = 0 }
            'G', '`' -> cx = (param(0, 1) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cy = (param(0, 1) - 1).coerceIn(0, rows - 1)
                cx = (param(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'd' -> cy = (param(0, 1) - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(param(0, 0))
            'K' -> eraseLine(param(0, 0))
            'L' -> scrollDown(cy, scrollBottom, param(0, 1).coerceAtMost(rows))
            'M' -> scrollUp(cy, scrollBottom, param(0, 1).coerceAtMost(rows))
            'P' -> deleteChars(param(0, 1))
            '@' -> insertBlanks(param(0, 1))
            'S' -> scrollUp(scrollTop, scrollBottom, param(0, 1))
            'T' -> scrollDown(scrollTop, scrollBottom, param(0, 1))
            'r' -> {
                scrollTop = (param(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (param(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cx = 0; cy = 0
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'm' -> sgr()
            'h' -> if (csiPrivate && params().contains(1049)) enterAlt()
            'l' -> if (csiPrivate && params().contains(1049)) exitAlt()
            else -> Unit // unknown finals are ignored by design
        }
        if (final != 'm') wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLineRange(cy, cx, cols)
                for (r in cy + 1 until rows) screen[r] = MutableList(cols) { TermCell() }
            }
            1 -> {
                for (r in 0 until cy) screen[r] = MutableList(cols) { TermCell() }
                eraseLineRange(cy, 0, cx + 1)
            }
            2, 3 -> screen = newGrid()
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseLineRange(cy, cx, cols)
            1 -> eraseLineRange(cy, 0, cx + 1)
            2 -> screen[cy] = MutableList(cols) { TermCell() }
        }
    }

    private fun eraseLineRange(row: Int, from: Int, to: Int) {
        for (c in from until to) screen[row][c] = TermCell()
    }

    private fun deleteChars(n: Int) {
        val row = screen[cy]
        val count = n.coerceIn(0, cols - cx)
        for (c in cx until cols - count) row[c] = row[c + count]
        for (c in cols - count until cols) row[c] = TermCell()
    }

    private fun insertBlanks(n: Int) {
        val row = screen[cy]
        val count = n.coerceIn(0, cols - cx)
        for (c in cols - 1 downTo cx + count) row[c] = row[c - count]
        for (c in cx until cx + count) row[c] = TermCell()
    }

    private fun sgr() {
        val p = if (csiParams.isEmpty()) listOf(0) else params()
        var i = 0
        while (i < p.size) {
            when (p[i]) {
                0 -> { curFg = -1; curBg = -1; curBold = false; curUnderline = false; curReverse = false }
                1 -> curBold = true
                4 -> curUnderline = true
                7 -> curReverse = true
                22 -> curBold = false
                24 -> curUnderline = false
                27 -> curReverse = false
                in 30..37 -> curFg = p[i] - 30
                39 -> curFg = -1
                in 40..47 -> curBg = p[i] - 40
                49 -> curBg = -1
                in 90..97 -> curFg = p[i] - 90 + 8
                in 100..107 -> curBg = p[i] - 100 + 8
                38, 48 -> {
                    val isFg = p[i] == 38
                    when {
                        i + 2 <= p.lastIndex && p[i + 1] == 5 -> {
                            if (isFg) curFg = p[i + 2] else curBg = p[i + 2]
                            i += 2
                        }
                        i + 4 <= p.lastIndex && p[i + 1] == 2 -> {
                            val rgb = (p[i + 2] shl 16) or (p[i + 3] shl 8) or p[i + 4]
                            if (isFg) curFg = 0x1000000 or rgb else curBg = 0x1000000 or rgb
                            i += 4
                        }
                    }
                }
            }
            i++
        }
    }

    private fun enterAlt() {
        if (altScreen == null) {
            altScreen = screen
            screen = newGrid()
            cx = 0; cy = 0
        }
    }

    private fun exitAlt() {
        altScreen?.let {
            screen = it
            altScreen = null
            cx = cx.coerceIn(0, cols - 1); cy = cy.coerceIn(0, rows - 1)
        }
    }

    // ------------------------------------------------------------- view

    /** The visible screen rows (defensive copies). */
    fun screenRows(): List<List<TermCell>> = screen.map { it.toList() }

    /** Scrollback rows, oldest first (defensive copies). */
    fun scrollbackRows(): List<List<TermCell>> = scrollback.toList()

    /** Plain-text rendering of a row, trailing blanks trimmed. */
    fun rowText(row: List<TermCell>): String =
        row.joinToString("") { it.ch.toString() }.trimEnd()

    /** Scrollback + screen as plain text lines (screen's trailing blank lines trimmed). */
    fun allTextLines(): List<String> {
        val out = ArrayList<String>(scrollback.size + rows)
        for (r in scrollback) out += rowText(r)
        val screenTexts = screen.map { rowText(it) }
        out += screenTexts.dropLastWhile { it.isEmpty() }
        return out
    }

    /** Text of the line the cursor is on (what prompt detection inspects). */
    fun cursorLineText(): String = rowText(screen[cy])

    /** Resize: content is preserved top-left; no reflow (v1 semantics). */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(1, 500)
        val r = newRows.coerceIn(1, 200)
        if (c == cols && r == rows) return
        val old = screen
        cols = c; rows = r
        screen = Array(rows) { y ->
            MutableList(cols) { x ->
                if (y < old.size && x < old[y].size) old[y][x] else TermCell()
            }
        }
        altScreen = null
        scrollTop = 0; scrollBottom = rows - 1
        cx = cx.coerceIn(0, cols - 1); cy = cy.coerceIn(0, rows - 1)
    }
}
