package com.anvex.core.engine

import com.anvex.core.model.MutationSource

/**
 * Bounded workspace API used by the agent and terminal layers. All paths are
 * relative to the selected project and are validated before touching storage.
 */
class WorkspaceRuntime(
    private val fs: ProjectFileSystem,
    private val dispatcher: ActionDispatcher = ActionDispatcher(fs),
) {
    companion object {
        const val MAX_READ_BYTES = 2_000_000
        const val MAX_WRITE_BYTES = 2_000_000
        const val MAX_LIST_ENTRIES = 2_000
    }

    fun read(path: String): WorkspaceResult {
        val safe = PathSanitizer.sanitizeOrNull(path) ?: return WorkspaceResult(false, error = "Unsafe path")
        val bytes = fs.readFileBytes(safe) ?: return WorkspaceResult(false, error = "File not found: $safe")
        if (bytes.size > MAX_READ_BYTES) return WorkspaceResult(false, error = "File exceeds the read limit.")
        return WorkspaceResult(true, String(bytes, Charsets.UTF_8))
    }

    fun list(path: String = ""): WorkspaceResult {
        val safe = if (path.isBlank()) "" else PathSanitizer.sanitizeOrNull(path)
            ?: return WorkspaceResult(false, error = "Unsafe path")
        val entries = fs.list(safe).take(MAX_LIST_ENTRIES)
            .map { if (it.isDirectory) "${it.name}/" else "${it.name} (${it.size} B)" }
        return WorkspaceResult(true, entries.joinToString("\n"))
    }

    fun write(path: String, content: String): WorkspaceResult {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) return WorkspaceResult(false, error = "File exceeds the write limit.")
        val result = dispatcher.writeFile(path, content, MutationSource.AI_FILE_ACTION)
        return WorkspaceResult(result.success, result.output, result.error, result.changedFiles)
    }

    fun delete(path: String, recursive: Boolean = false): WorkspaceResult {
        val result = dispatcher.deleteFile(path, recursive, MutationSource.AI_SHELL_COMMAND)
        return WorkspaceResult(result.success, result.output, result.error, result.changedFiles)
    }

    fun move(from: String, to: String): WorkspaceResult {
        val result = dispatcher.rename(from, to, MutationSource.AI_SHELL_COMMAND)
        return WorkspaceResult(result.success, result.output, result.error, result.changedFiles)
    }

    fun copy(from: String, to: String): WorkspaceResult {
        val result = dispatcher.copyFile(from, to, MutationSource.AI_SHELL_COMMAND)
        return WorkspaceResult(result.success, result.output, result.error, result.changedFiles)
    }
}

data class WorkspaceResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val changedFiles: List<String> = emptyList(),
)
