package com.anvex.core.engine

import java.util.concurrent.ConcurrentHashMap

/** Capability classes shared by tools, agent plans and runtime gateways. */
enum class ToolCapability {
    READ_ONLY, WORKSPACE_WRITE, BUILD, NETWORK, DEVICE, SYSTEM, DESTRUCTIVE
}

data class ToolDescriptor(
    val id: String,
    val description: String,
    val capabilities: Set<ToolCapability>,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank() && id.length <= 120)
        require(description.length <= 1_000)
        require(capabilities.isNotEmpty())
    }
}

/** In-memory registry; execution remains behind explicit runtime gateways. */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, ToolDescriptor>()

    fun register(tool: ToolDescriptor) {
        check(tools.putIfAbsent(tool.id, tool) == null) { "Tool already registered: ${tool.id}" }
    }

    fun get(id: String): ToolDescriptor? = tools[id]
    fun all(): List<ToolDescriptor> = tools.values.sortedBy { it.id }
}
