package com.anvex.core.engine

/** A declarative skill descriptor. Code execution is deliberately outside this layer. */
data class SkillDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val instructions: String,
    val capabilities: Set<String> = emptySet(),
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}")))
        require(name.length in 1..120)
        require(version.length in 1..40)
        require(description.length <= 2_000)
        require(instructions.length <= 32_000)
        require(capabilities.size <= 32 && capabilities.all { it.length in 1..80 })
    }
}

class SkillsRuntime(skills: Collection<SkillDescriptor>) {
    private val byId = skills.associateBy { it.id }
    fun get(id: String): SkillDescriptor? = byId[id]
    fun enabledCapabilities(id: String): Set<String> = byId[id]?.capabilities.orEmpty()
    fun systemInstruction(id: String): String? = byId[id]?.instructions
}
