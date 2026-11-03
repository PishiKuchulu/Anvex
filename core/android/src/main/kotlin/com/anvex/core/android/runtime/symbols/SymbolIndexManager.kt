package com.anvex.core.android.runtime.symbols

import com.anvex.core.android.runtime.AndroidRuntimeGateway
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.engine.codeintel.IndexedSymbol
import com.anvex.core.engine.codeintel.SymbolIndexCore
import com.anvex.core.engine.codeintel.SymbolOccurrence
import java.security.MessageDigest

/** A rename ready to apply: path → full replacement text. */
data class RenamePlan(
    val edits: Map<String, String>,
    val occurrenceCount: Int,
    val definitionCount: Int,
)

/**
 * P8 (F2b): incremental symbol indexing + references + rename planning.
 *
 * Indexing walks the project's indexable files, skips files whose
 * content hash matches the stored one, parses the rest through the
 * Tree-sitter seam and stores the extracted definitions. References
 * and rename work on live file texts with the pure identifier scan
 * ([SymbolIndexCore]); definition sites are marked from the index.
 * Applying a rename is the caller's job (through ActionDispatcher, so
 * every edit lands in a MutationLog and stays revertible).
 */
class SymbolIndexManager(
    private val gateway: AndroidRuntimeGateway,
    val store: SymbolStore,
) {

    /** Re-indexes changed files; returns how many files were reparsed. */
    suspend fun indexProject(projectId: String): Int {
        val fs = gateway.fileSystem(projectId) ?: return 0
        val codeIntelligence = gateway.codeIntelligence()
        var reparsed = 0
        for (path in indexablePaths(fs)) {
            val text = fs.readFile(path) ?: continue
            if (text.length > MAX_FILE_CHARS) continue
            val hash = sha256(text)
            if (store.fileHash(projectId, path) == hash) continue
            val grammar = grammarFor(path)
            val document = if (grammar != null) {
                codeIntelligence.parse(null, null, grammar, text)
            } else {
                null
            }
            val defs = SymbolIndexCore.extract(path, text, document)
            if (document != null) codeIntelligence.release(document)
            store.replaceFile(projectId, path, hash, defs)
            reparsed++
        }
        return reparsed
    }

    /** Stored definitions of [name] in this project. */
    fun definitions(projectId: String, name: String): List<IndexedSymbol> =
        store.definitions(projectId, name)

    /**
     * All occurrences of [name] across the project's indexable files,
     * with definition sites (from the index) marked. Call
     * [indexProject] first for fresh definition marks.
     */
    suspend fun findReferences(projectId: String, name: String): List<SymbolOccurrence> {
        val fs = gateway.fileSystem(projectId) ?: return emptyList()
        val definitionSites = store.definitions(projectId, name)
            .map { it.path to it.startOffset }
            .toHashSet()
        val out = ArrayList<SymbolOccurrence>()
        for (path in indexablePaths(fs)) {
            val text = fs.readFile(path) ?: continue
            if (text.length > MAX_FILE_CHARS) continue
            for (start in SymbolIndexCore.findOccurrences(text, name)) {
                out += SymbolOccurrence(
                    path = path,
                    startOffset = start,
                    endOffset = start + name.length,
                    line = SymbolIndexCore.lineOf(text, start),
                    isDefinition = (path to start) in definitionSites,
                )
            }
        }
        return out
    }

    /**
     * Builds the rename plan against live texts. Returns null when
     * [newName] is not a valid identifier, equals [name], or when
     * nothing would change.
     */
    suspend fun renamePlan(projectId: String, name: String, newName: String): RenamePlan? {
        if (newName == name || !SymbolIndexCore.isValidIdentifier(newName)) return null
        val fs = gateway.fileSystem(projectId) ?: return null
        val edits = LinkedHashMap<String, String>()
        var occurrences = 0
        for (path in indexablePaths(fs)) {
            val text = fs.readFile(path) ?: continue
            if (text.length > MAX_FILE_CHARS) continue
            val renamed = SymbolIndexCore.renamedText(text, name, newName) ?: continue
            occurrences += SymbolIndexCore.findOccurrences(text, name).size
            edits[path] = renamed
        }
        if (edits.isEmpty()) return null
        return RenamePlan(
            edits = edits,
            occurrenceCount = occurrences,
            definitionCount = store.definitions(projectId, name).size,
        )
    }

    private fun indexablePaths(fs: ProjectFileSystem): List<String> =
        fs.walk().filter { path ->
            grammarFor(path) != null &&
                !path.split('/').any { it == "node_modules" || it == ".git" }
        }

    companion object {
        const val MAX_FILE_CHARS = 512 * 1024

        fun grammarFor(path: String): String? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "ts" -> "typescript"
                "tsx", "jsx" -> "tsx"
                "js", "mjs", "cjs" -> "typescript"
                "py" -> "python"
                else -> null
            }
        }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
