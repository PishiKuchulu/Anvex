package com.anvex.core.engine

import com.anvex.core.model.FileMutation
import com.anvex.core.model.MutationOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F4 (desktop-grade upgrade): BatchMutation aggregation + decision logic. */
class BatchMutationTest {

    private fun mut(path: String, op: MutationOp = MutationOp.MODIFY, ts: Long = 0L) =
        FileMutation(path, op, before = "old-$path", after = "new-$path", timestamp = ts)

    @Test
    fun ofKeepsLastMutationPerPathInJournalOrder() {
        val batch = BatchMutation.of(
            "turn-1",
            listOf(
                mut("a.kt", ts = 1),
                mut("b.kt", ts = 2),
                mut("a.kt", ts = 3), // same path again — the later entry wins
            ),
        )
        assertEquals(2, batch.size)
        assertEquals(listOf("a.kt", "b.kt"), batch.entries.map { it.path })
        assertEquals(3L, batch.entry("a.kt")!!.mutation.timestamp)
        assertTrue(batch.entries.all { it.status == BatchEntryStatus.PENDING })
    }

    @Test
    fun withStatusChangesOnlyTheNamedPath() {
        val batch = BatchMutation.of("t", listOf(mut("a.kt"), mut("b.kt")))
            .withStatus("a.kt", BatchEntryStatus.KEPT)
        assertEquals(BatchEntryStatus.KEPT, batch.entry("a.kt")!!.status)
        assertEquals(BatchEntryStatus.PENDING, batch.entry("b.kt")!!.status)
        assertFalse(batch.allSettled)
        // Unknown path: no-op, no crash.
        assertEquals(batch, batch.withStatus("nope.kt", BatchEntryStatus.KEPT))
        assertNull(batch.entry("nope.kt"))
    }

    @Test
    fun withAllPendingSettlesTheBatch() {
        val batch = BatchMutation.of("t", listOf(mut("a.kt"), mut("b.kt")))
            .withStatus("a.kt", BatchEntryStatus.REVERTED)
            .withAllPending(BatchEntryStatus.KEPT)
        assertEquals(BatchEntryStatus.REVERTED, batch.entry("a.kt")!!.status)
        assertEquals(BatchEntryStatus.KEPT, batch.entry("b.kt")!!.status)
        assertTrue(batch.allSettled)
        assertEquals(1, batch.kept.size)
        assertEquals(1, batch.reverted.size)
        assertTrue(batch.pending.isEmpty())
    }

    @Test
    fun nonRevertibleEntriesDoNotCountAsRevertiblePending() {
        val dirDelete = FileMutation(
            "dir/", MutationOp.DELETE, before = null, after = null, revertible = false,
        )
        val batch = BatchMutation.of("t", listOf(dirDelete, mut("a.kt")))
        assertTrue(batch.hasRevertiblePending) // a.kt is pending + revertible
        val settled = batch.withStatus("a.kt", BatchEntryStatus.KEPT)
        assertFalse(settled.hasRevertiblePending) // only the non-revertible entry remains
        assertFalse(settled.allSettled) // ...but it is still pending a decision
    }

    @Test
    fun emptyBatchIsSettledAndCarriesDroppedCount() {
        val batch = BatchMutation.of("t", emptyList(), droppedCount = 7)
        assertTrue(batch.allSettled)
        assertEquals(0, batch.size)
        assertEquals(7, batch.droppedCount)
    }
}
