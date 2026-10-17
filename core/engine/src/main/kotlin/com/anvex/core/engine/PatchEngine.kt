package com.anvex.core.engine

/**
 * v4.4 (architect feature 2 — targeted edits): Search & Replace patch blocks.
 *
 * Contract (added to the artifact instructions in SystemPromptFactory):
 * instead of re-sending a full file, the model may emit a file action whose
 * content is one or more blocks:
 *
 * ```
 * <<<< SEARCH
 * exact existing lines
 * ====
 * replacement lines
 * >>>> REPLACE
 * ```
 *
 * All blocks are applied to the CURRENT file content in order. A block whose
 * SEARCH text is not present fails the whole patch — the file is left
 * untouched and the error names the offending block honestly.
 */
object PatchEngine {

    const val SEARCH_MARKER = "<<<< SEARCH"
    const val DIVIDER_MARKER = "===="
    const val REPLACE_MARKER = ">>>> REPLACE"

    sealed class PatchOutcome {
        /** Not a patch — the content does not start with a SEARCH marker. Write it as a full file. */
        object NotAPatch : PatchOutcome()
        data class Applied(val newContent: String, val blocksApplied: Int) : PatchOutcome()
        data class Failed(val reason: String) : PatchOutcome()
    }

    data class Block(val search: String, val replacement: String, val index: Int)

    /** True when [content] starts (after optional whitespace) with the SEARCH marker. */
    fun looksLikePatch(content: String): Boolean =
        content.trimStart().startsWith(SEARCH_MARKER)

    /** Parse all blocks; null when structurally invalid (error returned via [PatchOutcome.Failed] by [apply]). */
    fun parse(content: String): Pair<List<Block>, String?> {
        val blocks = mutableListOf<Block>()
        val lines = content.split("\n")
        var i = 0
        var index = 0
        while (i < lines.size) {
            if (lines[i].trim() != SEARCH_MARKER) { i++; continue }
            var j = i + 1
            val search = StringBuilder()
            val dividerAt: Int
            while (true) {
                if (j >= lines.size) return emptyList<Block>() to "Block ${index + 1}: missing '====' divider."
                if (lines[j].trim() == DIVIDER_MARKER) { dividerAt = j; break }
                search.append(lines[j]).append('\n')
                j++
            }
            j = dividerAt + 1
            val replacement = StringBuilder()
            while (true) {
                if (j >= lines.size) return emptyList<Block>() to "Block ${index + 1}: missing '>>>> REPLACE' terminator."
                if (lines[j].trim() == REPLACE_MARKER) break
                replacement.append(lines[j]).append('\n')
                j++
            }
            blocks.add(Block(search.toString().trimEnd('\n'), replacement.toString().trimEnd('\n'), index))
            index++
            i = j + 1
        }
        return blocks to (if (blocks.isEmpty()) "no SEARCH blocks found" else null)
    }

    /**
     * Apply [patch] to [existing]. The caller has already verified the content
     * is a patch ([looksLikePatch]).
     */
    fun apply(existing: String, patch: String): PatchOutcome {
        val (blocks, parseError) = parse(patch)
        if (parseError != null || blocks.isEmpty()) {
            return PatchOutcome.Failed(parseError ?: "no blocks")
        }
        var content = existing
        for (block in blocks) {
            // Exact match first.
            var at = content.indexOf(block.search)
            if (at < 0) {
                // Lenient pass: match ignoring leading/trailing blank lines on both sides.
                val search = block.search.trim()
                at = content.indexOf(search)
                if (at < 0) {
                    return PatchOutcome.Failed(
                        "Block ${block.index + 1}: the SEARCH text was not found in the current file " +
                            "(first line: \"${block.search.lineSequence().firstOrNull() ?: ""}\"). The file was NOT modified."
                    )
                }
                val before = content.substring(0, at)
                val after = content.substring(at + search.length)
                content = before + block.replacement.trimEnd('\n') + after
                continue
            }
            val before = content.substring(0, at)
            val after = content.substring(at + block.search.length)
            content = before + block.replacement.trimEnd('\n') + after
        }
        return PatchOutcome.Applied(content, blocks.size)
    }
}
