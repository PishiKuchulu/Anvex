package com.anvex.core.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractivePromptDetectorTest {

    @Test
    fun bracketChoiceIsDetected() {
        val p = InteractivePromptDetector.detect("Overwrite file? [y/n]", echoOff = false)
        assertNotNull(p)
        assertEquals(InteractivePromptDetector.Kind.CHOICE, p!!.kind)
    }

    @Test
    fun plainQuestionIsDetected() {
        val p = InteractivePromptDetector.detect("Enter the project name?", echoOff = false)
        assertNotNull(p)
        assertEquals(InteractivePromptDetector.Kind.QUESTION, p!!.kind)
    }

    @Test
    fun echoOffMeansSecret() {
        val p = InteractivePromptDetector.detect("Password:", echoOff = true)
        assertNotNull(p)
        assertTrue(p!!.secret)
    }

    @Test
    fun shellPromptIsNotAPrompt() {
        assertNull(InteractivePromptDetector.detect("user@anvex:~/project $", echoOff = false))
        assertNull(InteractivePromptDetector.detect("#", echoOff = false))
    }

    @Test
    fun ordinaryOutputIsNotAPrompt() {
        assertNull(InteractivePromptDetector.detect("Downloading packages...", echoOff = false))
        assertNull(InteractivePromptDetector.detect("", echoOff = false))
        assertNull(InteractivePromptDetector.detect("build finished in 3s", echoOff = false))
    }

    @Test
    fun answerEndsWithCarriageReturn() {
        assertEquals("y\r", String(InteractivePromptDetector.answerBytes("y"), Charsets.UTF_8))
    }
}
