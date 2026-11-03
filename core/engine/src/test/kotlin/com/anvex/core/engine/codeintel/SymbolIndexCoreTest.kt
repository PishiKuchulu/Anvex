package com.anvex.core.engine.codeintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolIndexCoreTest {

    @Test
    fun `extract names definitions with lines`() {
        val text = "class Foo {\n    fun bar() {}\n}\n"
        val doc = ParsedDocument(
            handle = 1,
            spans = emptyList(),
            folds = emptyList(),
            definitions = listOf(
                SymbolDefinition(6, 9, SymbolKind.TYPE),
                SymbolDefinition(20, 23, SymbolKind.FUNCTION),
            ),
        )
        val symbols = SymbolIndexCore.extract("A.kt", text, doc)
        assertEquals(2, symbols.size)
        assertEquals("Foo", symbols[0].name)
        assertEquals(SymbolKind.TYPE, symbols[0].kind)
        assertEquals(0, symbols[0].line)
        assertEquals("bar", symbols[1].name)
        assertEquals(1, symbols[1].line)
    }

    @Test
    fun `occurrences respect identifier boundaries`() {
        val text = "foo fooBar barfoo foo.bar " + '$' + "foo foo1"
        val starts = SymbolIndexCore.findOccurrences(text, "foo")
        // Bounded occurrences only: index 0 ("foo") and 18 (in "foo.bar").
        // Excluded: "fooBar" (4), "barfoo" (14), "$foo" (27, $ is an
        // identifier part), "foo1" (31).
        assertEquals(listOf(0, 18), starts)
    }

    @Test
    fun `dollar prefix counts as identifier part`() {
        assertTrue(SymbolIndexCore.findOccurrences("\$foo", "foo").isEmpty())
        assertEquals(listOf(0), SymbolIndexCore.findOccurrences("foo", "foo"))
    }

    @Test
    fun `identifier validation`() {
        assertTrue(SymbolIndexCore.isValidIdentifier("newName"))
        assertTrue(SymbolIndexCore.isValidIdentifier("_x1"))
        assertFalse(SymbolIndexCore.isValidIdentifier("1abc"))
        assertFalse(SymbolIndexCore.isValidIdentifier("has space"))
        assertFalse(SymbolIndexCore.isValidIdentifier(""))
        assertFalse(SymbolIndexCore.isValidIdentifier("with-dash"))
    }

    @Test
    fun `renamedText replaces only bounded occurrences`() {
        val text = "val foo = foo + fooBar"
        assertEquals("val baz = baz + fooBar", SymbolIndexCore.renamedText(text, "foo", "baz"))
        assertNull(SymbolIndexCore.renamedText(text, "missing", "baz"))
    }

    @Test
    fun `lineOf counts newlines before the offset`() {
        val text = "a\nbb\nccc"
        assertEquals(0, SymbolIndexCore.lineOf(text, 0))
        assertEquals(1, SymbolIndexCore.lineOf(text, 3))
        assertEquals(2, SymbolIndexCore.lineOf(text, 6))
    }
}
