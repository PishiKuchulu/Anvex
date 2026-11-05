package com.anvex.core.android.reliability

import com.anvex.core.engine.ActionSafetyPolicy
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.ToolCapability
import java.security.MessageDigest

/** Unified risk + capability + resource-scoped approval authority. */
class CommandApprovalService(private val grants: DurableApprovalRepository) {
    data class Decision(val allowed: Boolean, val requiresApproval: Boolean, val reason: String, val capability: ToolCapability, val fingerprint: String)

    suspend fun decide(sessionId: String, projectId: String?, action: BoltAction): Decision {
        val assessment = ActionSafetyPolicy.assess(action)
        val capability = capabilityFor(action)
        val fingerprint = fingerprintFor(action)
        if (!assessment.requiresApproval) return Decision(true, false, assessment.reason, capability, fingerprint)
        val allowed = grants.allows(sessionId, projectId, capability, fingerprint)
        return if (allowed) Decision(true, true, "Durable scoped approval matched.", capability, fingerprint)
        else Decision(false, true, assessment.reason, capability, fingerprint)
    }

    suspend fun grantForAction(sessionId: String, projectId: String?, action: BoltAction, ttlMs: Long) =
        grants.grant(sessionId, projectId, setOf(capabilityFor(action)), ttlMs, fingerprintFor(action))

    suspend fun grant(sessionId: String, projectId: String?, capability: ToolCapability, ttlMs: Long) =
        grants.grant(sessionId, projectId, setOf(capability), ttlMs, null)

    suspend fun grantFingerprint(sessionId: String, projectId: String?, capability: ToolCapability, fingerprint: String, ttlMs: Long) =
        grants.grantFingerprint(sessionId, projectId, capability, fingerprint, ttlMs)


    private fun capabilityFor(action: BoltAction): ToolCapability = when (action) {
        is BoltAction.ShellAction, is BoltAction.StartAction -> ToolCapability.SYSTEM
        is BoltAction.FileAction -> if (action.content.length > 2_000_000) ToolCapability.DESTRUCTIVE else ToolCapability.WORKSPACE_WRITE
        is BoltAction.ArtifactAction -> if (action.paths.size > 20_000 || action.content.toByteArray().size > 20 * 1024 * 1024) ToolCapability.DESTRUCTIVE else ToolCapability.WORKSPACE_WRITE
        is BoltAction.ToolAction -> if (action.name == "fetch" || action.name == "search") ToolCapability.NETWORK else ToolCapability.READ_ONLY
        is BoltAction.UnknownAction -> ToolCapability.DESTRUCTIVE
    }
    fun fingerprintFor(action: BoltAction): String {
        // M-02 (external audit, 2026-11-05): the material used to be cut
        // at 1,000,000 chars BEFORE hashing, so two different actions
        // sharing a 1M-char prefix produced the SAME fingerprint and a
        // scoped grant for the first silently covered the second. The
        // content is already fully in memory at this point — hash all
        // of it.
        val material = when (action) {
            is BoltAction.FileAction -> "FILE:${action.filePath}:${action.content}"
            is BoltAction.ShellAction -> "SHELL:${action.content}"
            is BoltAction.StartAction -> "START:${action.content}"
            is BoltAction.ArtifactAction -> "ARTIFACT:${action.format}:${action.outputName}:${action.title}:${action.paths.joinToString("|")}:${action.content}"
            is BoltAction.ToolAction -> "TOOL:${action.name}:${action.argument}"
            is BoltAction.UnknownAction -> "UNKNOWN:${action.type}:${action.content}"
        }
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
