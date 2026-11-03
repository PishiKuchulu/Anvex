package com.anvex.core.android.runtime.rag

import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.engine.codeintel.CodeChunker
import com.anvex.core.engine.codeintel.CodeIntelligence
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one indexing pass (P7/F6). */
data class IndexReport(
    val filesScanned: Int,
    val filesIndexed: Int,
    val filesSkipped: Int,
    val chunksStored: Int,
    val capped: Boolean,
    val message: String? = null,
)

/**
 * Incremental codebase indexer: walks the project, skips files whose
 * content hash is unchanged, chunks the rest (Tree-sitter fold-aware
 * when a grammar exists, line windows otherwise), embeds each chunk,
 * and stores them in the per-project vec0 tables. Caps from plan §6:
 * files ≤ 256 KB, ≤ 20 000 chunks per project.
 */
class CodebaseIndexer(
    private val store: VecStore,
    private val embedder: OnnxEmbedder,
    private val codeIntelligence: CodeIntelligence?,
) {

    suspend fun index(
        projectId: String,
        fs: ProjectFileSystem,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): IndexReport = withContext(Dispatchers.IO) {
        val entries = runCatching { fs.walkWithSizes() }.getOrDefault(emptyList())
        val candidates = entries.filter { (path, size) ->
            val ext = path.substringAfterLast('.', "").lowercase()
            ext in INDEXABLE_EXTENSIONS && (size == null || size <= MAX_FILE_BYTES) &&
                !path.split('/').contains("node_modules") &&
                !path.split('/').contains(".git")
        }
        var indexed = 0
        var skipped = 0
        var chunks = 0
        var capped = false
        for ((index, entry) in candidates.withIndex()) {
            onProgress(index, candidates.size)
            if (!capped && store.count(projectId) >= MAX_CHUNKS_PER_PROJECT) capped = true
            val path = entry.first
            val text = runCatching { fs.readFile(path) }.getOrNull()
            if (text == null) {
                skipped++
                continue
            }
            val hash = sha256(text)
            if (store.fileHash(projectId, path) == hash) {
                skipped++
                continue
            }
            if (capped) {
                skipped++
                continue
            }
            val grammar = grammarFor(path)
            val folds = if (grammar != null && codeIntelligence != null) {
                runCatching {
                    val doc = codeIntelligence.parse(null, null, grammar, text)
                    val found = doc?.folds ?: emptyList()
                    if (doc != null) codeIntelligence.release(doc)
                    found
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val pieces = CodeChunker.chunk(text, folds)
            val ragChunks = ArrayList<RagChunk>(pieces.size)
            for (piece in pieces) {
                val embedding = embedder.embed(piece.text) ?: continue
                ragChunks += RagChunk(
                    chunkId = "$path:${piece.startLine}:${piece.endLine}",
                    startLine = piece.startLine,
                    endLine = piece.endLine,
                    text = piece.text,
                    embedding = embedding,
                )
            }
            if (store.replaceFile(projectId, path, hash, ragChunks)) {
                indexed++
                chunks += ragChunks.size
            } else {
                skipped++
            }
        }
        onProgress(candidates.size, candidates.size)
        IndexReport(
            filesScanned = candidates.size,
            filesIndexed = indexed,
            filesSkipped = skipped,
            chunksStored = chunks,
            capped = capped,
        )
    }

    private fun grammarFor(path: String): String? =
        when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "ts" -> "typescript"
            "tsx" -> "tsx"
            "py" -> "python"
            else -> null
        }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_FILE_BYTES = 256L * 1024L
        const val MAX_CHUNKS_PER_PROJECT = 20_000
        val INDEXABLE_EXTENSIONS = setOf(
            "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "json", "md",
            "xml", "html", "css", "scss", "c", "h", "cpp", "hpp", "rs", "go",
            "sh", "yaml", "yml", "toml", "gradle", "properties", "sql",
        )
    }
}
