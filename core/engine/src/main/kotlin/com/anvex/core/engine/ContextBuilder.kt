package com.anvex.core.engine

/**
 * Bounded project context for LLM requests (role of `select-context.ts` upstream,
 * simplified and hard-capped for mobile memory constraints).
 */
object ContextBuilder {

    const val MAX_TOTAL_CHARS = 48_000
    const val MAX_FILE_CHARS = 14_000
    /** The file LIST gets its own budget so key-file contents always fit (BUG-07). */
    const val MAX_LIST_CHARS = 12_000
    private const val DIFF_RESERVE_CHARS = 2_000
    private val PRIORITY_FILES = listOf(
        "package.json", "index.html", "src/main.jsx", "src/main.tsx", "src/App.jsx",
        "src/App.tsx", "src/index.css", "app.js", "index.js", "styles.css", "style.css",
    )

    /** Directories that never belong in model context (deps, VCS, app-private state, build output). */
    private fun excluded(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it == ".git" || it == "node_modules" || it == ".anvex" || it == ".gradle" || it == ".idea" }) return true
        val buildIndex = parts.indexOfFirst { it == "build" }
        if (buildIndex >= 0 && parts.take(buildIndex).none { it == "src" }) return true
        return false
    }

    fun build(fs: ProjectFileSystem, openFilePath: String? = null, diffNote: String? = null): String {
        return runCatching {
            val all = fs.walk().filter { !excluded(it) }
            if (all.isEmpty()) {
                return@runCatching "The project is currently empty. Create all files from scratch.".let {
                    if (diffNote != null) "$it\n$diffNote" else it
                }
            }

            val sb = StringBuilder()
            sb.append("Project file list (").append(all.size).append(" files):\n")
            // v4.10.1 (BUG-07): the old loop's `return@forEach` never stopped
            // appending, so on large projects the list alone blew the whole
            // budget and the key-file contents below were never reached.
            var listed = 0
            for (path in all.sorted()) {
                val line = "- $path (${fs.fileSize(path) ?: 0L} bytes)\n"
                if (sb.length + line.length > MAX_LIST_CHARS) break
                sb.append(line)
                listed++
            }
            if (listed < all.size) sb.append("… and ${all.size - listed} more files (list truncated)\n")

            val priority = mutableListOf<String>()
            openFilePath?.let { if (all.contains(it)) priority.add(it) }
            PRIORITY_FILES.filter { all.contains(it) && !priority.contains(it) }.forEach { priority.add(it) }

            val contentBudget = MAX_TOTAL_CHARS - DIFF_RESERVE_CHARS
            sb.append("\nKey file contents:\n")
            for (path in priority) {
                if (sb.length >= contentBudget) break
                val content = runCatching { fs.readFile(path) }.getOrNull() ?: continue
                if (content.length > MAX_FILE_CHARS) {
                    sb.append("### ").append(path).append(" (truncated to first ")
                        .append(MAX_FILE_CHARS).append(" chars)\n")
                    sb.append(content, 0, MAX_FILE_CHARS).append("\n...[truncated]\n\n")
                } else {
                    sb.append("### ").append(path).append("\n").append(content).append("\n\n")
                }
            }
            if (diffNote != null && sb.length < MAX_TOTAL_CHARS) sb.append(diffNote).append('\n')
            sb.toString().take(MAX_TOTAL_CHARS)
        }.getOrElse {
            // Context failure must never leave the chat stuck in a streaming state.
            "Project context could not be read safely. Continue using the user's request and create or update the required files." +
                (diffNote?.let { "\n$it" } ?: "")
        }
    }
}
