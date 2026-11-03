package com.anvex.core.engine.codeintel

/** A symbol definition extracted from one file (P8/F2b). */
data class IndexedSymbol(
    val name: String,
    val kind: SymbolKind,
    val path: String,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
)

/** One occurrence of an identifier (definition site or reference). */
data class SymbolOccurrence(
    val path: String,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val isDefinition: Boolean,
)

/**
 * Pure symbol-index logic for F2b: definitions come from Tree-sitter
 * (see [SymbolDefinition] in a [ParsedDocument]); references are found
 * by an identifier-boundary scan — the deterministic lightweight model
 * the plan specifies (no LSP). All functions are JVM-tested.
 */
object SymbolIndexCore {

    /** Extracts named definitions from a parsed document. */
    fun extract(path: String, text: String, document: ParsedDocument?): List<IndexedSymbol> {
        if (document == null) return emptyList()
        val out = ArrayList<IndexedSymbol>(document.definitions.size)
        for (definition in document.definitions) {
            if (definition.start < 0 || definition.end > text.length ||
                definition.end <= definition.start
            ) {
                continue
            }
            val name = text.substring(definition.start, definition.end)
            if (name.isBlank()) continue
            out += IndexedSymbol(
                name = name,
                kind = definition.kind,
                path = path,
                startOffset = definition.start,
                endOffset = definition.end,
                line = lineOf(text, definition.start),
            )
        }
        return out
    }

    /** 0-based line of a char offset. */
    fun lineOf(text: String, offset: Int): Int {
        var line = 0
        var i = 0
        val stop = minOf(offset, text.length)
        while (i < stop) {
            if (text[i] == '\n') line++
            i++
        }
        return line
    }

    /**
     * Every occurrence of [name] in [text] bounded by non-identifier
     * characters (letters, digits, `_`, `$` are identifier parts).
     * Occurrences inside strings/comments are included by design — the
     * lightweight model reports candidates, and the rename planner
     * lets the user review the count before applying.
     */
    fun findOccurrences(text: String, name: String): List<Int> {
        if (name.isEmpty()) return emptyList()
        val starts = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = text.indexOf(name, from)
            if (at < 0) break
            val before = if (at == 0) null else text[at - 1]
            val afterAt = at + name.length
            val after = if (afterAt >= text.length) null else text[afterAt]
            if ((before == null || !isIdentifierPart(before)) &&
                (after == null || !isIdentifierPart(after))
            ) {
                starts += at
            }
            from = at + 1
        }
        return starts
    }

    fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private val IDENTIFIER = Regex("[A-Za-z_\$][A-Za-z0-9_\$]*")

    /** A rename target must be a plain identifier in every grammar. */
    fun isValidIdentifier(name: String): Boolean = IDENTIFIER.matches(name)

    /**
     * Applies a rename to one file's text: replaces every occurrence
     * (from [findOccurrences]) with [newName], returning the new text,
     * or null when there is nothing to change.
     */
    fun renamedText(text: String, name: String, newName: String): String? {
        val starts = findOccurrences(text, name)
        if (starts.isEmpty()) return null
        val out = StringBuilder(text.length + starts.size * (newName.length - name.length).coerceAtLeast(0))
        var cursor = 0
        for (start in starts) {
            out.append(text, cursor, start)
            out.append(newName)
            cursor = start + name.length
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }
}
