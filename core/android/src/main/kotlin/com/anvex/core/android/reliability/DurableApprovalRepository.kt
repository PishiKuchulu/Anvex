package com.anvex.core.android.reliability

import com.anvex.core.android.db.ApprovalGrantDao
import com.anvex.core.android.db.ApprovalGrantEntity
import com.anvex.core.engine.ToolCapability
import java.security.MessageDigest

/** Persistent approval boundary. Grants are scoped, expiring, and optionally bound to an exact action fingerprint. */
class DurableApprovalRepository(
    private val dao: ApprovalGrantDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun grant(sessionId: String, projectId: String?, capabilities: Set<ToolCapability>, ttlMs: Long, resourceFingerprint: String? = null): Boolean {
        require(sessionId.isNotBlank() && sessionId.length <= 160)
        require(projectId == null || projectId.length <= 160)
        require(capabilities.isNotEmpty() && capabilities.size <= ToolCapability.entries.size)
        require(ttlMs in 1_000L..24 * 60 * 60 * 1_000L)
        require(resourceFingerprint == null || resourceFingerprint.matches(Regex("[A-Fa-f0-9]{64}")))
        dao.upsert(ApprovalGrantEntity(sessionId.trim(), projectId, capabilities.joinToString(",") { it.name }, resourceFingerprint = resourceFingerprint, maxRisk = "REQUIRES_APPROVAL", expiresAt = now() + ttlMs))
        return true
    }
    suspend fun revoke(sessionId: String) = dao.delete(sessionId)
    suspend fun grantFingerprint(
        sessionId: String,
        projectId: String?,
        capability: ToolCapability,
        resourceFingerprint: String,
        ttlMs: Long,
    ): Boolean {
        require(resourceFingerprint.matches(Regex("[A-Fa-f0-9]{64}")))
        return grant(sessionId, projectId, setOf(capability), ttlMs, resourceFingerprint)
    }

    suspend fun allows(sessionId: String, projectId: String?, capability: ToolCapability, resourceFingerprint: String? = null): Boolean {
        val grant = dao.get(sessionId) ?: return false
        if (grant.expiresAt <= now()) { dao.delete(sessionId); return false }
        if (grant.projectId != null && grant.projectId != projectId) return false
        val caps = grant.capabilities.split(',').mapNotNull { runCatching { ToolCapability.valueOf(it) }.getOrNull() }
        if (capability !in caps) return false
        return grant.resourceFingerprint == null || (resourceFingerprint != null && MessageDigest.isEqual(grant.resourceFingerprint.lowercase().toByteArray(), resourceFingerprint.lowercase().toByteArray()))
    }
    suspend fun purgeExpired(): Int = dao.deleteExpired(now())
}
