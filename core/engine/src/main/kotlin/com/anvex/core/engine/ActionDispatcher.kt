package com.anvex.core.engine

import com.anvex.core.model.FileMutation
import com.anvex.core.model.MutationOp
import com.anvex.core.model.MutationSource

/**
 * Result contract for every dispatched action (v1.1 §8). Actions never fail
 * silently: [error] is populated whenever [success] is false, [changedFiles]
 * lists every file touched, and [durationMs] measures the dispatch.
 */
data class ActionResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val changedFiles: List<String> = emptyList(),
    val durationMs: Long = 0,
    /** Honest status for UI mapping: PARTIAL = translated but limited (e.g. npm install). */
    val status: com.anvex.core.model.ActionStatus = com.anvex.core.model.ActionStatus.COMPLETE,
)

/**
 * Robust action dispatcher (v1.1 §8). Conceptually:
 *
 * ```
 * ActionDispatcher
 *  ├── FileWrite      (boltAction type="file" + shell echo > file)
 *  ├── FileDelete     (rm / shell delete)
 *  ├── FileRename     (mv)
 *  ├── FileMove       (mv across dirs)
 *  ├── FileRead       (cat)
 *  ├── DirectoryCreate(mkdir)
 *  └── Command        (translated whitelist; capability-tiered)
 * ```
 *
 * ALL filesystem mutations funnel through [mutate] so `before` is captured
 * BEFORE the write and `after` AFTER the write — the P0-2 fix. Every mutation
 * is appended to the session [MutationLog] and is revertible.
 */
class ActionDispatcher(
    private val fs: ProjectFileSystem,
    /** Approval is required only for actions classified as high impact. */
    private val approval: ((ActionSafetyPolicy.Assessment) -> Boolean)? = null,
) {

    /** Bounded journal of every file mutation performed through this dispatcher. */
    val mutations = MutationLog()

    // ------------------------------------------------------------ bolt actions

    /** Execute a closed `<boltAction type="file">` — port of upstream `#runFileAction`. */
    fun applyFileAction(action: BoltAction.FileAction, messageId: String? = null, approvalGranted: Boolean = false): ActionResult {
        val t0 = System.nanoTime()
        val assessment = ActionSafetyPolicy.assess(action)
        if (assessment.requiresApproval && !approvalGranted && approval?.invoke(assessment) != true) {
            return ActionResult(false, null, "Approval required: ${assessment.reason}", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        }
        val path = PathSanitizer.sanitizeOrNull(action.filePath)
            ?: return ActionResult(
                false, null, "Invalid or unsafe file path: '${action.filePath}'",
                emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED,
            )

        // v4.4 (architect feature 2 — targeted edits): when the model emits a
        // SEARCH/REPLACE patch instead of the full file, apply it to the current
        // content. The file is left untouched when the patch does not apply.
        if (PatchEngine.looksLikePatch(action.content)) {
            val existing = fs.readFile(path)
            if (existing == null) {
                return ActionResult(
                    false, null,
                    "Targeted edit (SEARCH/REPLACE) needs the file to already exist: '${action.filePath}'",
                    emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED,
                )
            }
            return when (val outcome = PatchEngine.apply(existing, action.content)) {
                is PatchEngine.PatchOutcome.Applied -> {
                    val written = writeFile(path, outcome.newContent, MutationSource.AI_FILE_ACTION, messageId)
                    written.copy(
                        output = "Applied ${outcome.blocksApplied} SEARCH/REPLACE block(s) to ${action.filePath}.",
                        durationMs = elapsed(t0),
                    )
                }
                is PatchEngine.PatchOutcome.Failed ->
                    ActionResult(false, null, outcome.reason, emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
                PatchEngine.PatchOutcome.NotAPatch ->
                    ActionResult(false, null, "Unrecognized patch content.", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
            }
        }

        val result = writeFile(path, action.content, MutationSource.AI_FILE_ACTION, messageId)
        return result.copy(durationMs = elapsed(t0))
    }

    /** Execute shell / start actions via the command translation layer. */
    fun applyShellAction(
        action: BoltAction,
        typeLabel: String,
        messageId: String? = null,
        approvalGranted: Boolean = false,
    ): List<ActionResult> {
        val translation = CommandTranslator.translate(action.content)
        val source = if (typeLabel == "start") MutationSource.AI_START_COMMAND else MutationSource.AI_SHELL_COMMAND
        return translation.parts.map { part ->
            val t0 = System.nanoTime()
            val output = StringBuilder()
            val changed = ArrayList<String>()
            var error: String? = null

            val assessment = ActionSafetyPolicy.assess(part.original)
            if (assessment.requiresApproval && !approvalGranted && approval?.invoke(assessment) != true) {
                error = "Approval required: ${assessment.reason}"
            } else if (part.tier == CommandTranslator.Tier.UNSUPPORTED) {
                error = part.note
            } else {
                try {
                    applyEffect(part.effect, source, messageId, output, changed)
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }

            val status = when (part.tier) {
                CommandTranslator.Tier.SUPPORTED ->
                    if (error == null) com.anvex.core.model.ActionStatus.COMPLETE
                    else com.anvex.core.model.ActionStatus.FAILED
                // Honest reporting: translated-but-limited commands surface as PARTIAL.
                CommandTranslator.Tier.PARTIALLY ->
                    if (error == null) com.anvex.core.model.ActionStatus.PARTIAL
                    else com.anvex.core.model.ActionStatus.FAILED
                CommandTranslator.Tier.UNSUPPORTED -> com.anvex.core.model.ActionStatus.FAILED
            }
            ActionResult(
                success = error == null && part.tier != CommandTranslator.Tier.UNSUPPORTED,
                output = output.toString().ifBlank { part.note },
                error = error,
                changedFiles = changed,
                durationMs = elapsed(t0),
                status = status,
            )
        }
    }

    // ------------------------------------------------------- primitive operations

    fun writeFile(
        path: String,
        content: String,
        source: MutationSource,
        messageId: String? = null,
    ): ActionResult {
        val t0 = System.nanoTime()
        val safe = PathSanitizer.sanitizeOrNull(path)
            ?: return ActionResult(false, null, "Invalid path: '$path'", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        return mutate(safe, MutationOp.MODIFY, content, source, messageId).copy(durationMs = elapsed(t0))
    }

    fun deleteFile(
        path: String,
        recursive: Boolean,
        source: MutationSource,
        messageId: String? = null,
        approvalGranted: Boolean = false,
    ): ActionResult {
        val t0 = System.nanoTime()
        if (recursive) {
            val assessment = ActionSafetyPolicy.assess("rm -rf $path")
            if (!approvalGranted && approval?.invoke(assessment) != true) {
                return ActionResult(false, null, "Approval required: ${assessment.reason}", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
            }
        }
        val safe = PathSanitizer.sanitizeOrNull(path)
            ?: return ActionResult(false, null, "Invalid path: '$path'", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        val before = if (fs.isDirectory(safe)) null else fs.readFile(safe)
        val existed = fs.exists(safe)
        val ok = fs.delete(safe, recursive)
        return if (ok) {
            mutations.record(
                FileMutation(safe, MutationOp.DELETE, before, null, source = source, messageId = messageId)
            )
            ActionResult(true, "Deleted $safe", null, listOf(safe), elapsed(t0))
        } else {
            ActionResult(false, null, "Could not delete $safe${if (existed) "" else " (not found)"}", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        }
    }

    fun rename(from: String, to: String, source: MutationSource, messageId: String? = null): ActionResult {
        val t0 = System.nanoTime()
        val safeFrom = PathSanitizer.sanitizeOrNull(from)
        val safeTo = PathSanitizer.sanitizeOrNull(to)
        if (safeFrom == null || safeTo == null) {
            return ActionResult(false, null, "Invalid rename path", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        }
        val before = fs.readFile(safeFrom)
        val existed = fs.exists(safeFrom)
        val ok = fs.move(safeFrom, safeTo)
        return if (ok) {
            // P0-2 fix: renames are first-class mutations (diffable + revertible).
            mutations.record(
                FileMutation(
                    safeFrom, MutationOp.RENAME, before, before,
                    source = source, renamedTo = safeTo, messageId = messageId,
                )
            )
            ActionResult(true, "Renamed $safeFrom -> $safeTo", null, listOf(safeFrom, safeTo), elapsed(t0))
        } else {
            ActionResult(false, null, "Rename failed${if (existed) "" else " ($safeFrom not found)"}", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        }
    }

    fun copyFile(from: String, to: String, source: MutationSource, messageId: String? = null): ActionResult {
        val t0 = System.nanoTime()
        val safeFrom = PathSanitizer.sanitizeOrNull(from)
        val safeTo = PathSanitizer.sanitizeOrNull(to)
        if (safeFrom == null || safeTo == null) {
            return ActionResult(false, null, "Invalid copy path", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        }
        val before = fs.readFile(safeTo) // destination's previous content
        val ok = fs.copy(safeFrom, safeTo)
        return if (ok) {
            val after = fs.readFile(safeTo)
            mutations.record(
                FileMutation(safeTo, if (before == null) MutationOp.CREATE else MutationOp.MODIFY, before, after, source = source, messageId = messageId)
            )
            ActionResult(true, "Copied $safeFrom -> $safeTo", null, listOf(safeTo), elapsed(t0))
        } else {
            ActionResult(false, null, "Copy failed ($safeFrom not found or unreadable)", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        }
    }

    fun createDirectory(path: String, source: MutationSource, messageId: String? = null): ActionResult {
        val t0 = System.nanoTime()
        val safe = PathSanitizer.sanitizeOrNull(path)
            ?: return ActionResult(false, null, "Invalid path: '$path'", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        fs.mkdirs(safe)
        return ActionResult(true, "Created directory $safe", null, emptyList(), elapsed(t0))
    }

    fun touch(path: String, source: MutationSource, messageId: String? = null): ActionResult {
        val t0 = System.nanoTime()
        val safe = PathSanitizer.sanitizeOrNull(path)
            ?: return ActionResult(false, null, "Invalid path: '$path'", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        if (!fs.exists(safe)) {
            return mutate(safe, MutationOp.CREATE, "", source, messageId).copy(durationMs = elapsed(t0))
        }
        return ActionResult(true, "File exists: $safe", null, emptyList(), elapsed(t0))
    }

    fun readFile(path: String): ActionResult {
        val t0 = System.nanoTime()
        val safe = PathSanitizer.sanitizeOrNull(path)
            ?: return ActionResult(false, null, "Invalid path: '$path'", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        val content = fs.readFile(safe)
            ?: return ActionResult(false, null, "File not found: $safe", emptyList(), elapsed(t0), com.anvex.core.model.ActionStatus.FAILED)
        return ActionResult(true, content, null, emptyList(), elapsed(t0))
    }

    // ----------------------------------------------------------------- internals

    /**
     * THE single mutation chokepoint (P0-2). Order is strictly:
     * `before = current content` -> perform mutation -> `after = resulting content`.
     */
    private fun mutate(
        path: String,
        intendedOp: MutationOp,
        newContent: String,
        source: MutationSource,
        messageId: String?,
    ): ActionResult {
        val before = fs.readFile(path) // BEFORE the mutation — never after
        PathSanitizer.parent(path)?.let(fs::mkdirs)
        fs.writeFile(path, newContent)
        val after = fs.readFile(path) // AFTER the mutation
        val op = if (before == null) MutationOp.CREATE else intendedOp
        mutations.record(FileMutation(path, op, before, after, source = source, messageId = messageId))
        return ActionResult(
            success = true,
            output = "${newContent.toByteArray().size} bytes written to $path",
            changedFiles = listOf(path),
        )
    }

    private fun applyEffect(
        effect: CommandTranslator.Effect,
        source: MutationSource,
        messageId: String?,
        output: StringBuilder,
        changed: ArrayList<String>,
    ) {
        when (effect) {
            is CommandTranslator.Effect.Mkdir -> {
                var made = 0
                effect.paths.forEach { p -> createDirectory(p, source, messageId).let { if (it.success) made++ } }
                output.append("Created $made directory/directories")
            }
            is CommandTranslator.Effect.Touch -> {
                effect.paths.forEach { p ->
                    touch(p, source, messageId).let { if (it.success) changed.addAll(it.changedFiles) }
                }
                output.append("Touched ${effect.paths.size} file(s)")
            }
            is CommandTranslator.Effect.Write -> {
                val safe = PathSanitizer.sanitizeOrNull(effect.path)
                    ?: throw IllegalArgumentException("Invalid write path: ${effect.path}")
                val before = fs.readFile(safe) // BEFORE — P0-2 fix (was read after write)
                PathSanitizer.parent(safe)?.let(fs::mkdirs)
                if (effect.append) fs.appendFile(safe, effect.content + "\n") else fs.writeFile(safe, effect.content + "\n")
                val after = fs.readFile(safe)
                mutations.record(
                    FileMutation(
                        safe, if (before == null) MutationOp.CREATE else MutationOp.MODIFY,
                        before, after, source = source, messageId = messageId,
                    )
                )
                changed.add(safe)
                output.append("Wrote ${effect.content.length + 1} bytes to $safe")
            }
            is CommandTranslator.Effect.Delete -> {
                var deleted = 0
                effect.paths.forEach { p ->
                    if (deleteFile(p, effect.recursive, source, messageId).success) { deleted++; changed.add(p) }
                }
                output.append("Deleted $deleted path(s)")
            }
            is CommandTranslator.Effect.Move -> {
                if (rename(effect.from, effect.to, source, messageId).success) {
                    changed.add(effect.from); changed.add(effect.to)
                    output.append("Moved ${effect.from} -> ${effect.to}")
                } else output.append("Move failed")
            }
            is CommandTranslator.Effect.Copy -> {
                if (copyFile(effect.from, effect.to, source, messageId).success) {
                    changed.add(effect.to)
                    output.append("Copied ${effect.from} -> ${effect.to}")
                } else output.append("Copy failed")
            }
            is CommandTranslator.Effect.ListDir -> {
                val entries = fs.list(effect.path)
                output.append(if (entries.isEmpty()) "(empty)" else entries.joinToString("\n") { e ->
                    (if (e.isDirectory) "${e.name}/" else e.name)
                })
            }
            is CommandTranslator.Effect.ReadFile -> {
                output.append(fs.readFile(effect.path) ?: "(file not found)")
            }
            is CommandTranslator.Effect.RecordDependencies -> {
                output.append(effect.note)
            }
            CommandTranslator.Effect.NoOp -> output.append("No-op")
        }
    }

    private fun elapsed(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000
}

/**
 * Bounded journal of file mutations (v1.1 §22/§24). Backs diff/review, revert,
 * and persistence of action results. Bounded to [MAX_ENTRIES] to protect memory
 * on 4 GB devices (v1.1 §29) — the oldest entries are dropped first.
 */
class MutationLog(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val log = ArrayDeque<FileMutation>()

    fun record(mutation: FileMutation) {
        log.addLast(mutation)
        while (log.size > maxEntries) log.removeFirst()
    }

    fun all(): List<FileMutation> = log.toList()

    fun forPath(path: String): List<FileMutation> = log.filter { it.path == path }

    fun latestForPath(path: String): FileMutation? = log.lastOrNull { it.path == path }

    /** Most recent mutation per path — the diff/review view. */
    fun latestPerPath(): List<FileMutation> {
        val out = LinkedHashMap<String, FileMutation>()
        for (m in log) out[m.path] = m
        return out.values.toList()
    }

    /** Revert a single mutation (undo one AI write). Returns false if not revertible. */
    fun revert(path: String, fs: ProjectFileSystem): Boolean {
        val m = latestForPath(path) ?: return false
        return revertMutation(m, fs)
    }

    fun revertMutation(m: FileMutation, fs: ProjectFileSystem): Boolean = try {
        when (m.operation) {
            MutationOp.CREATE -> fs.delete(m.path, recursive = false) || !fs.exists(m.path)
            MutationOp.MODIFY -> m.before?.let { fs.writeFile(m.path, it); true } ?: run {
                fs.delete(m.path, false); true
            }
            MutationOp.DELETE -> m.before?.let { fs.writeFile(m.path, it); true } ?: false
            MutationOp.RENAME -> {
                val restored = m.before?.let { fs.writeFile(m.path, it); true } ?: false
                val removed = fs.delete(m.renamedTo ?: "", recursive = true)
                restored && removed
            }
        }
    } catch (_: Exception) {
        false
    }

    fun clear() = log.clear()

    val size: Int get() = log.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
