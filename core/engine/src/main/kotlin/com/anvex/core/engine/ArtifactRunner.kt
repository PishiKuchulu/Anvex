package com.anvex.core.engine

import com.anvex.core.model.ActionRecord
import com.anvex.core.model.ActionStatus
import com.anvex.core.model.ArtifactSummary
import com.anvex.core.model.FileMutation

/**
 * Port of the execution half of bolt.diy's `ActionRunner`
 * (file actions + translated shell actions), rebuilt in v1.1 on top of
 * [ActionDispatcher]:
 *  - every filesystem mutation is a journaled [FileMutation] with accurate
 *    before/after (P0-2 fix) and is revertible;
 *  - PARTIAL command tiers are reported honestly (never COMPLETE);
 *  - action errors are surfaced, never swallowed.
 */
class ArtifactRunner(private val fs: ProjectFileSystem) {

    val dispatcher = ActionDispatcher(fs)

    /** View over the dispatcher journal, for the diff/review UI (brief §22). */
    val pendingChanges: List<FileMutation>
        get() = dispatcher.mutations.latestPerPath()

    val mutations: List<FileMutation>
        get() = dispatcher.mutations.all()

    private val records = ArrayList<ActionRecord>()
    private var idCounter = 0

    fun reset() {
        records.clear()
        dispatcher.mutations.clear()
    }

    val actionRecords: List<ActionRecord> get() = records.toList()

    fun summaryFor(artifactId: String, title: String): ArtifactSummary =
        ArtifactSummary(artifactId, title, actionRecords.toList())

    /** Execute a closed `<boltAction type="file">` — port of `#runFileAction`. */
    fun applyFileAction(action: BoltAction.FileAction, messageId: String? = null): ActionRecord {
        val result = dispatcher.applyFileAction(action, messageId)
        val record = ActionRecord(
            nextId(), "file", action.filePath.takeIf { result.success } ?: action.filePath,
            null, result.status.name, result.output ?: result.error,
            result.changedFiles.firstOrNull()?.let { fs.readFileBytes(it)?.size?.toLong() } ?: 0L,
        )
        return record(record)
    }

    /** Execute shell / start actions via the command translation layer. */
    fun applyShellAction(action: BoltAction, typeLabel: String, messageId: String? = null): List<ActionRecord> {
        val results = dispatcher.applyShellAction(action, typeLabel, messageId)
        return results.map { result ->
            record(
                ActionRecord(
                    nextId(), typeLabel, result.changedFiles.firstOrNull(), action.content.take(120),
                    result.status.name, result.output ?: result.error ?: "No output", 0,
                )
            )
        }
    }

    /** Revert one journaled mutation (undo an AI write). */
    fun revert(path: String): Boolean = dispatcher.mutations.revert(path, fs)

    private fun record(r: ActionRecord): ActionRecord {
        records.add(r)
        return r
    }

    private fun nextId(): String = "a${idCounter++}"
}
