package com.anvex.core.ai

import com.anvex.core.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wire mappings for the Thinking menu. Each provider has its own
 * documented vocabulary, so the mapping lives on ThinkingLevel itself
 * and is pinned here — a verbatim-label regression (the old "normal"
 * sent to OpenAI) must fail loudly.
 */
class ThinkingLevelMappingTest {

    @Test
    fun `openai map is one to one except normal becomes medium`() {
        assertEquals("low", ThinkingLevel.LOW.toOpenAiEffort())
        assertEquals("medium", ThinkingLevel.NORMAL.toOpenAiEffort())
        assertEquals("high", ThinkingLevel.HIGH.toOpenAiEffort())
        assertEquals("xhigh", ThinkingLevel.XHIGH.toOpenAiEffort())
        assertEquals("max", ThinkingLevel.MAX.toOpenAiEffort())
    }

    @Test
    fun `deepseek map uses only low high max`() {
        assertEquals("low", ThinkingLevel.LOW.toDeepSeekEffort())
        assertEquals("high", ThinkingLevel.NORMAL.toDeepSeekEffort())
        assertEquals("high", ThinkingLevel.HIGH.toDeepSeekEffort())
        assertEquals("max", ThinkingLevel.XHIGH.toDeepSeekEffort())
        assertEquals("max", ThinkingLevel.MAX.toDeepSeekEffort())
    }

    @Test
    fun `groq map caps the top levels at high`() {
        assertEquals("low", ThinkingLevel.LOW.toGroqEffort())
        assertEquals("medium", ThinkingLevel.NORMAL.toGroqEffort())
        assertEquals("high", ThinkingLevel.HIGH.toGroqEffort())
        assertEquals("high", ThinkingLevel.XHIGH.toGroqEffort())
        assertEquals("high", ThinkingLevel.MAX.toGroqEffort())
    }

    @Test
    fun `pi map matches pi native levels except normal becomes medium`() {
        assertEquals("low", ThinkingLevel.LOW.toPiLevel())
        assertEquals("medium", ThinkingLevel.NORMAL.toPiLevel())
        assertEquals("high", ThinkingLevel.HIGH.toPiLevel())
        assertEquals("xhigh", ThinkingLevel.XHIGH.toPiLevel())
        assertEquals("max", ThinkingLevel.MAX.toPiLevel())
    }
}
