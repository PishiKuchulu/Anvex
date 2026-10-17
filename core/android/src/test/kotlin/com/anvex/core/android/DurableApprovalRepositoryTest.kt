package com.anvex.core.android

import com.anvex.core.android.db.ApprovalGrantDao
import com.anvex.core.android.db.ApprovalGrantEntity
import com.anvex.core.android.reliability.DurableApprovalRepository
import com.anvex.core.engine.ToolCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DurableApprovalRepositoryTest {
    private class FakeDao : ApprovalGrantDao {
        private val rows = linkedMapOf<String, ApprovalGrantEntity>()
        override suspend fun get(sessionId: String) = rows[sessionId]
        override suspend fun upsert(grant: ApprovalGrantEntity) { rows[grant.sessionId] = grant }
        override suspend fun delete(sessionId: String) { rows.remove(sessionId) }
        override suspend fun deleteExpired(now: Long): Int {
            val before = rows.size
            rows.values.removeIf { it.expiresAt <= now }
            return before - rows.size
        }
        override suspend fun recent(limit: Int): List<ApprovalGrantEntity> = rows.values.toList().take(limit)
    }

    @Test fun `grant is scoped and expires`() = runTest {
        var now = 1_000L
        val repo = DurableApprovalRepository(FakeDao()) { now }
        repo.grant("s1", "p1", setOf(ToolCapability.WORKSPACE_WRITE), 1_000L)
        assertTrue(repo.allows("s1", "p1", ToolCapability.WORKSPACE_WRITE))
        assertFalse(repo.allows("s1", "p2", ToolCapability.WORKSPACE_WRITE))
        assertFalse(repo.allows("s1", "p1", ToolCapability.SYSTEM))
        now = 2_001L
        assertFalse(repo.allows("s1", "p1", ToolCapability.WORKSPACE_WRITE))
    }
}
