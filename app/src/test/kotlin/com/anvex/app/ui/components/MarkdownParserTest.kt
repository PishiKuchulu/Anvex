package com.anvex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test fun `heading level and text are parsed`() {
        val blocks = parseMarkdownBlocks("## Title here")
        assertEquals(listOf<MdBlock>(MdBlock.Heading(2, "Title here")), blocks)
    }

    @Test fun `bullet and numbered items keep their markers`() {
        val blocks = parseMarkdownBlocks("- one\n* two\n1. first")
        assertEquals(
            listOf(
                MdBlock.ListItem("•", "one"),
                MdBlock.ListItem("•", "two"),
                MdBlock.ListItem("1.", "first"),
            ),
            blocks,
        )
    }

    @Test fun `fenced code keeps language and inner lines verbatim`() {
        val blocks = parseMarkdownBlocks("Intro\n```kotlin\nval x = 1\n  y()\n```\nAfter")
        assertEquals(MdBlock.Paragraph("Intro"), blocks[0])
        assertEquals(MdBlock.Code("kotlin", "val x = 1\n  y()"), blocks[1])
        assertEquals(MdBlock.Paragraph("After"), blocks[2])
    }

    @Test fun `unclosed fence during streaming runs to the end`() {
        val blocks = parseMarkdownBlocks("```py\nprint(1)")
        assertEquals(listOf<MdBlock>(MdBlock.Code("py", "print(1)")), blocks)
    }

    @Test fun `consecutive lines form one paragraph and blank lines split it`() {
        val blocks = parseMarkdownBlocks("line a\nline b\n\nline c")
        assertEquals(MdBlock.Paragraph("line a\nline b"), blocks[0])
        assertEquals(MdBlock.Paragraph("line c"), blocks[1])
    }

    @Test fun `emphasis inside a word is not treated as a list item`() {
        val blocks = parseMarkdownBlocks("*emphasis* text")
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph("*emphasis* text")), blocks)
    }

    @Test fun `inline bold and code are styled and markers removed`() {
        val text = inlineAnnotated("a **b** `c`", codeBg = androidx.compose.ui.graphics.Color.Gray, linkColor = androidx.compose.ui.graphics.Color.Blue)
        assertEquals("a b c", text.text)
        assertTrue(text.spanStyles.size >= 2)
    }

    @Test fun `unmatched markers are kept literally`() {
        val text = inlineAnnotated("2 * 3 and **open", codeBg = androidx.compose.ui.graphics.Color.Gray, linkColor = androidx.compose.ui.graphics.Color.Blue)
        assertEquals("2 * 3 and **open", text.text)
    }

    @Test fun `link label is shown without the url`() {
        val text = inlineAnnotated("see [docs](https://example.com)", codeBg = androidx.compose.ui.graphics.Color.Gray, linkColor = androidx.compose.ui.graphics.Color.Blue)
        assertEquals("see docs", text.text)
    }

    @Test fun `persian paragraph stays a paragraph`() {
        val blocks = parseMarkdownBlocks("سلام دنیا")
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph("سلام دنیا")), blocks)
    }
}
