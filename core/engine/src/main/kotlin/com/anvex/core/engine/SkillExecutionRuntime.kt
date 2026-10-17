package com.anvex.core.engine

/** Executable skill plan runtime. It orchestrates prompts/tools but never loads arbitrary code. */
class SkillExecutionRuntime(private val skills: SkillsRuntime) {
    /** Safe declarative runtime: compiles prompts/tool intents only; never executes arbitrary code. */
    data class Step(val type: Type, val value: String, val capability: ToolCapability? = null)
    enum class Type { PROMPT, TOOL }
    data class Plan(val skillId: String, val steps: List<Step>)

    fun compile(skillId: String, variables: Map<String, String> = emptyMap()): Plan {
        val skill = skills.get(skillId) ?: error("Unknown skill: $skillId")
        val raw = skill.instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(raw.size <= 64) { "Skill contains too many steps." }
        val allowed = skill.capabilities.mapNotNull { runCatching { ToolCapability.valueOf(it.uppercase()) }.getOrNull() }.toSet()
        val steps = raw.map { line ->
            val expanded = variables.entries.fold(line) { acc, (k, v) -> acc.replace("{{$k}}", v.take(4_000)) }
            when {
                expanded.startsWith("PROMPT:", true) -> Step(Type.PROMPT, expanded.substringAfter(':').trim().take(4_000))
                expanded.startsWith("TOOL:", true) -> {
                    val parts = expanded.substringAfter(':').split('|', limit = 2)
                    require(parts.size == 2) { "TOOL step must be TOOL:<capability>|<value>." }
                    val cap = runCatching { ToolCapability.valueOf(parts[0].trim().uppercase()) }.getOrNull() ?: error("Unknown skill capability.")
                    require(cap == ToolCapability.READ_ONLY || cap in allowed) { "Skill capability not granted: $cap" }
                    Step(Type.TOOL, parts[1].trim().take(4_000), cap)
                }
                else -> Step(Type.PROMPT, expanded.take(4_000))
            }
        }
        return Plan(skillId, steps)
    }
}
