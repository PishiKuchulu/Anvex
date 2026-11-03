package com.anvex.core.engine.codeintel

/**
 * Code intelligence seam (F2a, P4): the editor and (later) the symbol
 * index talk to this interface only — no Tree-sitter or JNI types leak
 * into :app or :core:engine. The Android implementation lives in
 * :core:android (`TreeSitterCodeIntelligence`); when the native library
 * is unavailable [available] is false and callers keep the regex
 * highlighter.
 *
 * Language ids are the Tree-sitter grammar names: "kotlin", "java",
 * "typescript", "tsx", "python". Anything else is unsupported.
 */
interface CodeIntelligence {

    val available: Boolean

    fun supports(language: String): Boolean

    /**
     * Parses [source] and returns highlight spans + fold ranges with
     * UTF-16 CHAR offsets into [source]. When [previous] and
     * [previousSource] are given, the implementation reparses
     * incrementally from the previous tree (single contiguous edit).
     * Returns null when unavailable, unsupported, or over budget — the
     * caller then keeps whatever highlighting it already had.
     */
    suspend fun parse(
        previous: ParsedDocument?,
        previousSource: String?,
        language: String,
        source: String,
    ): ParsedDocument?

    /** Releases the native tree behind [document] (LRU eviction also frees). */
    fun release(document: ParsedDocument)
}

enum class HighlightKind {
    KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, PROPERTY, CONSTANT;

    companion object {
        /** Wire ids emitted by ts_bridge.cpp. */
        fun fromWire(id: Int): HighlightKind? = when (id) {
            1 -> KEYWORD
            2 -> STRING
            3 -> COMMENT
            4 -> NUMBER
            5 -> TYPE
            6 -> FUNCTION
            7 -> PROPERTY
            8 -> CONSTANT
            else -> null
        }
    }
}

/** One highlight span over [start, end) UTF-16 char offsets. */
data class HighlightSpan(val start: Int, val end: Int, val kind: HighlightKind)

/** A foldable region over 0-based [startLine, endLine] (inclusive). */
data class FoldRange(val startLine: Int, val endLine: Int)

/** P8 (F2b): the kind of a symbol definition. Wire ids from ts_bridge. */
enum class SymbolKind {
    FUNCTION, TYPE, PROPERTY;

    companion object {
        fun fromWire(code: Int): SymbolKind? = when (code) {
            1 -> FUNCTION
            2 -> TYPE
            3 -> PROPERTY
            else -> null
        }
    }
}

/** A symbol definition's name range, as UTF-16 char offsets [start, end). */
data class SymbolDefinition(val start: Int, val end: Int, val kind: SymbolKind)

/**
 * A parsed document: the native tree [handle] (opaque, for incremental
 * reparses) plus the derived spans/folds for one exact source text.
 * [definitions] (P8/F2b) names the declared symbols for the index.
 */
data class ParsedDocument(
    val handle: Long,
    val spans: List<HighlightSpan>,
    val folds: List<FoldRange>,
    val definitions: List<SymbolDefinition> = emptyList(),
)
