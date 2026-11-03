package com.anvex.core.android.runtime.ts

import com.anvex.core.engine.codeintel.CodeIntelligence
import com.anvex.core.engine.codeintel.ParsedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CodeIntelligence] backed by libanvex-ts.so (F2a, P4). Budgets from
 * the plan: sources over 512 KB of UTF-8 are refused (the caller keeps
 * the regex highlighter). Parsing itself runs on [Dispatchers.Default].
 */
class TreeSitterCodeIntelligence : CodeIntelligence {

    override val available: Boolean get() = TsBridge.available

    override fun supports(language: String): Boolean = language in SUPPORTED

    override suspend fun parse(
        previous: ParsedDocument?,
        previousSource: String?,
        language: String,
        source: String,
    ): ParsedDocument? {
        if (!available || !supports(language)) return null
        return withContext(Dispatchers.Default) {
            if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) return@withContext null
            val edit = if (previous != null && previousSource != null) {
                TsEdit.between(previousSource, source)
            } else {
                null
            }
            // Identical text: the previous document is already exact.
            if (previous != null && previousSource == source) return@withContext previous
            val raw = TsBridge.parse(
                handle = previous?.handle ?: 0L,
                language = language,
                source = source,
                edit = edit,
            ) ?: return@withContext null
            TsProtocol.decode(raw, source)
        }
    }

    override fun release(document: ParsedDocument) = TsBridge.release(document.handle)

    companion object {
        val SUPPORTED = setOf("kotlin", "java", "typescript", "tsx", "python")
        const val MAX_SOURCE_BYTES = 512 * 1024
    }
}
