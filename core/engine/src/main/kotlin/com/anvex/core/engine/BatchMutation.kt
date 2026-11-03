package com.anvex.core.engine

import com.anvex.core.model.FileMutation

/**
 * Batch review state for one agent turn (desktop-grade upgrade, F4).
 *
 * Anvex applies mutations live through [ActionDispatcher] and journals them
 * in [MutationLog]; the desktop "composer" accept/reject therefore maps to
 * keep/revert decisions over the journal rather than a pre-apply staging
 * area. [BatchMutation] is the typed aggregate the review UI binds to: the
 * turn's latest-per-path mutations, each carrying an explicit decision.
 *
 * Decisions are review state, not storage state. KEPT means "the user
 * reviewed this file and keeps the change" — the journal entry itself is
 * deliberately NOT purged, because the same persisted journal backs the
 * Version History feature; erasing before-images on accept would silently
 * destroy file history. REVERTED is recorded only after the journal revert
 * actually succeeded (see ChatSession.revertChange), never optimistically.
 */
enum class BatchEntryStatus { PENDING, KEPT, REVERTED }

/** One file of a [BatchMutation] with its review decision. */
data class BatchEntry(
    val mutation: FileMutation,
    val status: BatchEntryStatus = BatchEntryStatus.PENDING,
) {
    val path: String get() = mutation.path
    /** A directory DELETE journals no restorable before-state. */
    val revertible: Boolean get() = mutation.revertible
}

/**
 * The mutations of a single agent turn, in journal order.
 *
 * @param batchId identifies the turn (the assistant message id when known).
 * @param droppedCount journal entries evicted by the bounds before this
 * batch was snapshotted; surfaced in the UI as "journal trimmed" so a
 * partial batch never pretends to be complete.
 */
data class BatchMutation(
    val batchId: String,
    val entries: List<BatchEntry>,
    val droppedCount: Int = 0,
) {
    val size: Int get() = entries.size
    val pending: List<BatchEntry> get() = entries.filter { it.status == BatchEntryStatus.PENDING }
    val kept: List<BatchEntry> get() = entries.filter { it.status == BatchEntryStatus.KEPT }
    val reverted: List<BatchEntry> get() = entries.filter { it.status == BatchEntryStatus.REVERTED }

    /** True when every entry has a decision (or the batch is empty). */
    val allSettled: Boolean get() = pending.isEmpty()

    /** True when at least one entry is still pending and revertible. */
    val hasRevertiblePending: Boolean get() = pending.any { it.revertible }

    fun entry(path: String): BatchEntry? = entries.firstOrNull { it.path == path }

    /** Returns a copy with [path]'s decision set; unknown paths are a no-op. */
    fun withStatus(path: String, status: BatchEntryStatus): BatchMutation =
        copy(entries = entries.map { if (it.path == path) it.copy(status = status) else it })

    /** Returns a copy with every PENDING entry's decision set. */
    fun withAllPending(status: BatchEntryStatus): BatchMutation =
        copy(entries = entries.map {
            if (it.status == BatchEntryStatus.PENDING) it.copy(status = status) else it
        })

    companion object {
        /**
         * Builds a batch from raw journal mutations. When several mutations
         * hit the same path in one turn, the LAST one wins — it is the only
         * one whose before-image describes the turn's starting state chain
         * correctly for review (same rule as [MutationLog.latestPerPath]).
         */
        fun of(
            batchId: String,
            mutations: List<FileMutation>,
            droppedCount: Int = 0,
        ): BatchMutation {
            val byPath = LinkedHashMap<String, FileMutation>()
            for (m in mutations) byPath[m.path] = m
            return BatchMutation(
                batchId = batchId,
                entries = byPath.values.map { BatchEntry(it) },
                droppedCount = droppedCount,
            )
        }
    }
}
