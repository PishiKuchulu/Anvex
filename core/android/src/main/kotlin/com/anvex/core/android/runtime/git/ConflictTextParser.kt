package com.anvex.core.android.runtime.git

/**
 * Parser/renderer for git conflict-marker text (F1, P3) — the
 * `<<<<<<<` / `|||||||` / `=======` / `>>>>>>>` blocks a merge leaves in a
 * working file. Pure Kotlin, unit-tested: the MergeConflictResolver UI is
 * a thin shell over [parse] (display) and [render] (write-back), so the
 * text surgery itself is verified without a device.
 *
 * Lines are handled without terminators; [parse] takes the file text and
 * [render] rejoins with '\n' (a trailing newline on the input is preserved
 * via a final empty text line, matching how editors treat it).
 */
sealed interface ConflictSegment {
    data class Text(val lines: List<String>) : ConflictSegment
    data class Conflict(
        val oursLabel: String,
        val ours: List<String>,
        val base: List<String>?,
        val theirsLabel: String,
        val theirs: List<String>,
    ) : ConflictSegment
}

object ConflictTextParser {

    enum class Pick { OURS, THEIRS, BASE, OURS_THEN_THEIRS, THEIRS_THEN_OURS }

    private const val OPEN = "<<<<<<<"
    private const val BASE = "|||||||"
    private const val SEP = "======="
    private const val CLOSE = ">>>>>>>"

    fun hasConflicts(text: String): Boolean =
        text.lineSequence().any { it.startsWith(OPEN) }

    fun parse(text: String): List<ConflictSegment> {
        val segments = mutableListOf<ConflictSegment>()
        val plain = mutableListOf<String>()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                segments += ConflictSegment.Text(plain.toList())
                plain.clear()
            }
        }
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith(OPEN)) {
                plain += line
                i++
                continue
            }
            // Conflict block: collect ours, optional base, theirs.
            flushPlain()
            val oursLabel = line.removePrefix(OPEN).trim()
            val ours = mutableListOf<String>()
            var base: MutableList<String>? = null
            val theirs = mutableListOf<String>()
            var theirsLabel = ""
            var bucket = ours
            i++
            while (i < lines.size) {
                val l = lines[i]
                when {
                    l.startsWith(BASE) -> {
                        base = mutableListOf()
                        bucket = base
                    }
                    l.startsWith(SEP) -> bucket = theirs
                    l.startsWith(CLOSE) -> {
                        theirsLabel = l.removePrefix(CLOSE).trim()
                        i++
                        break
                    }
                    else -> bucket += l
                }
                i++
            }
            segments += ConflictSegment.Conflict(oursLabel, ours.toList(), base?.toList(), theirsLabel, theirs.toList())
        }
        flushPlain()
        return segments
    }

    /**
     * Rebuilds the file with each conflict block replaced by its [Pick].
     * [picks] is indexed by conflict order; missing picks default to OURS
     * (the resolver UI requires an explicit choice per block before it
     * enables saving, so the default never silently ships).
     */
    fun render(segments: List<ConflictSegment>, picks: List<Pick>): String {
        val out = StringBuilder()
        var conflictIndex = 0
        var first = true
        fun emit(line: String) {
            if (!first) out.append('\n')
            out.append(line)
            first = false
        }
        for (segment in segments) {
            when (segment) {
                is ConflictSegment.Text -> segment.lines.forEach(::emit)
                is ConflictSegment.Conflict -> {
                    val pick = picks.getOrElse(conflictIndex) { Pick.OURS }
                    conflictIndex++
                    val chosen = when (pick) {
                        Pick.OURS -> segment.ours
                        Pick.THEIRS -> segment.theirs
                        Pick.BASE -> segment.base ?: segment.ours
                        Pick.OURS_THEN_THEIRS -> segment.ours + segment.theirs
                        Pick.THEIRS_THEN_OURS -> segment.theirs + segment.ours
                    }
                    chosen.forEach(::emit)
                }
            }
        }
        return out.toString()
    }
}
