package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserIntentClassifierTest {
    @Test fun `plain greeting is conversational`() {
        assertEquals(UserIntentClassifier.Intent.CONVERSATION, UserIntentClassifier.classify("سلام"))
    }

    @Test fun `explicit Persian file edit requires execution`() {
        assertTrue(UserIntentClassifier.requiresExecution("فایل سورس رو اصلاح کن"))
    }

    @Test fun `explicit English edit requires execution`() {
        assertTrue(UserIntentClassifier.requiresExecution("Fix the source file"))
    }

    @Test fun `question about fixing is not forced by absence of mutation command`() {
        assertEquals(UserIntentClassifier.Intent.CONVERSATION, UserIntentClassifier.classify("چطور این فایل رو اصلاح کنم؟"))
    }

    @Test fun `build question remains conversational`() {
        assertEquals(UserIntentClassifier.Intent.CONVERSATION, UserIntentClassifier.classify("What is a build?"))
    }

    @Test fun `build imperative requires execution`() {
        assertTrue(UserIntentClassifier.requiresExecution("Build the project"))
    }

    @Test fun `prompt contains execution contract only when requested`() {
        val normal = SystemPromptFactory.buildSystemPrompt()
        val mutation = SystemPromptFactory.buildSystemPrompt(executionRequired = true)
        assertTrue(!normal.contains("EXECUTION CONTRACT FOR THIS TURN"))
        assertTrue(mutation.contains("EXECUTION CONTRACT FOR THIS TURN"))
        assertTrue(mutation.contains("A conversational acknowledgement without executable actions is an INVALID response"))
    }
}
