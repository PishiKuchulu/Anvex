package com.anvex.core.engine

import org.junit.Assert.*
import org.junit.Test

class SkillsRuntimeTest {
    @Test fun descriptorsAreBoundedAndQueryable() {
        val skill = SkillDescriptor("android.build", "Android Build", "1.0", "Build helper", "Use the build tool only.", setOf("build"))
        val runtime = SkillsRuntime(listOf(skill))
        assertEquals("build", runtime.enabledCapabilities("android.build").single())
        assertEquals("Use the build tool only.", runtime.systemInstruction("android.build"))
    }
}
