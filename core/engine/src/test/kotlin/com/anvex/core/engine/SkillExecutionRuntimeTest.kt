package com.anvex.core.engine

import org.junit.Assert.*
import org.junit.Test

class SkillExecutionRuntimeTest {
    @Test fun compilesPromptAndAllowedToolIntents() {
        val skill = SkillDescriptor("demo", "Demo", "1.0", "", "PROMPT: build {{variant}}\nTOOL:READ_ONLY|list files", setOf("READ_ONLY"))
        val plan = SkillExecutionRuntime(SkillsRuntime(listOf(skill))).compile("demo", mapOf("variant" to "debug"))
        assertEquals(2, plan.steps.size)
        assertEquals(SkillExecutionRuntime.Type.PROMPT, plan.steps[0].type)
        assertEquals("build debug", plan.steps[0].value)
        assertEquals(ToolCapability.READ_ONLY, plan.steps[1].capability)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUndeclaredToolCapability() {
        val skill = SkillDescriptor("demo", "Demo", "1.0", "", "TOOL:SYSTEM|rm -rf x", emptySet())
        SkillExecutionRuntime(SkillsRuntime(listOf(skill))).compile("demo")
    }
}
