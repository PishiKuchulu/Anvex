package com.anvex.core.android.reliability

import com.anvex.core.android.db.PluginDao
import com.anvex.core.engine.SkillDescriptor
import com.anvex.core.engine.SkillExecutionRuntime
import com.anvex.core.engine.SkillsRuntime
import com.anvex.core.engine.ToolCapability
import kotlinx.serialization.json.Json

/** Durable host for installed declarative skills. Execution produces approved action intents only. */
class SkillRuntimeService(private val dao: PluginDao) {
    data class ExecutionResult(val skillId: String, val steps: List<SkillExecutionRuntime.Step>)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun compile(skillId: String, variables: Map<String, String> = emptyMap()): ExecutionResult {
        val plugin = dao.get(skillId) ?: error("Skill not installed: $skillId")
        require(plugin.enabled) { "Skill is disabled." }
        val instructions = plugin.skillInstructions ?: error("Plugin does not provide a declarative skill plan.")
        val caps = runCatching { json.decodeFromString<List<String>>(plugin.skillCapabilitiesJson).toSet() }.getOrDefault(emptySet())
        val runtime = SkillsRuntime(listOf(SkillDescriptor(plugin.id, plugin.name, plugin.version, plugin.description, instructions, caps)))
        val plan = SkillExecutionRuntime(runtime).compile(skillId, variables)
        plan.steps.filter { it.capability != null }.forEach { step ->
            val capability = requireNotNull(step.capability) { "Skill capability missing after null filter." }
            require(capability == ToolCapability.READ_ONLY || capability.name in caps) { "Skill capability not declared." }
        }
        return ExecutionResult(skillId, plan.steps)
    }
}
