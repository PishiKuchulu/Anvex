package com.anvex.core.engine

/** Capability classes shared by tools, agent plans and runtime gateways. */
enum class ToolCapability {
    READ_ONLY, WORKSPACE_WRITE, BUILD, NETWORK, DEVICE, SYSTEM, DESTRUCTIVE
}
