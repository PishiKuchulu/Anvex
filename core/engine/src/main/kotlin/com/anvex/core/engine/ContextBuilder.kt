package com.anvex.core.engine

/**
 * Bounded project context for LLM requests (role of `select-context.ts` upstream,
 * simplified and hard-capped for mobile memory constraints).
 */
object ContextBuilder {

    const val MAX_TOTAL_CHARS = 48_000
    const val MAX_FILE_CHARS = 14_000
    private val PRIORITY_FILES = listOf(
        "package.json", "index.html", "src/main.jsx", "src/main.tsx", "src/App.jsx",
        "src/App.tsx", "src/index.css", "app.js", "index.js", "styles.css", "style.css",
    )

    fun build(fs: ProjectFileSystem, openFilePath: String? = null, diffNote: String? = null): String {
        return runCatching {
            val all = fs.walk().filter { !it.startsWith(".bolt/") }
            if (all.isEmpty()) {
                return@runCatching "The project is currently empty. Create all files from scratch.".let {
                    if (diffNote != null) "$it\n$diffNote" else it
                }
            }

            val sb = StringBuilder()
            sb.append("Project file list (").append(all.size).append(" files):\n")
            // Use filesystem metadata rather than loading every file into RAM.
            all.sorted().forEach { path ->
                sb.append("- ").append(path).append(" (").append(fs.fileSize(path) ?: 0L).append(" bytes)\n")
                if (sb.length >= MAX_TOTAL_CHARS) return@forEach
            }

            val priority = mutableListOf<String>()
            openFilePath?.let { if (all.contains(it)) priority.add(it) }
            PRIORITY_FILES.filter { all.contains(it) && !priority.contains(it) }.forEach { priority.add(it) }

            sb.append("\nKey file contents:\n")
            for (path in priority) {
                if (sb.length >= MAX_TOTAL_CHARS) break
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
