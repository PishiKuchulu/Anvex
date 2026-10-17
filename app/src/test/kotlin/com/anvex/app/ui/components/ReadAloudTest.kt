package com.anvex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTest {
    @Test fun `markdown symbols are removed before speaking`() {
        assertEquals("bold and code", ReadAloud.plainForSpeech("**bold** and `code`"))
    }

    @Test fun `code blocks are replaced with a short phrase`() {
        val out = ReadAloud.plainForSpeech("Before\n```kotlin\nval x = 1\n```\nAfter")
        assertEquals("Before code block. After", out)
    }

    @Test fun `headings lose their hash marks`() {
        assertEquals("Title text", ReadAloud.plainForSpeech("## Title text"))
    }
}
