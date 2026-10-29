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
    // M-17: the old list was web-only, so Kotlin/Android/Python projects
    // got no file contents at all and the model had to guess with reads.
    private val PRIORITY_FILES = listOf(
        "package.json", "index.html", "src/main.jsx", "src/main.tsx", "src/App.jsx",
        "src/App.tsx", "src/index.css", "app.js", "index.js", "styles.css", "style.css",
        "src/index.ts", "wrangler.toml", "pyproject.toml", "requirements.txt",
        "Cargo.toml", "go.mod",
        "AndroidManifest.xml", "app/src/main/AndroidManifest.xml",
        "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle",
        "gradle/libs.versions.toml",
    )

    /** Directories that never belong in model context (deps, VCS, app-private state, build output). */
    private fun excluded(path: String): Boolean {
        // M-17: secret files must never reach the model — neither as list
        // lines (which used to leak .env/credentials paths) nor as contents.
        if (SensitivePaths.isSensitive(path)) return true
        val parts = path.split('/')
        if (parts.any { it == ".git" || it == "node_modules" || it == ".anvex" || it == ".anvex-pi" || it == ".gradle" || it == ".idea" }) return true
        val buildIndex = parts.indexOfFirst { it == "build" }
        if (buildIndex >= 0 && parts.take(buildIndex).none { it == "src" }) return true
        return false
    }

    /**
     * M-17: [maxTotalChars] scales the budget with the model's context
     * window (callers pass it; the 48k default is kept when unknown).
     * The file list gets a quarter of the budget so key-file contents
     * always fit.
     */
    fun build(
        fs: ProjectFileSystem,
        openFilePath: String? = null,
        diffNote: String? = null,
        maxTotalChars: Int = MAX_TOTAL_CHARS,
        // 15.2: files the user's message names (backtick spans or
        // path-like tokens) outrank the generic priority list — the model
        // no longer has to spend a read round-trip on the file the user is
        // already talking about. Membership in `all` is the only gate, so
        // sensitive files mentioned by name are still excluded.
        mentionedFiles: List<String> = emptyList(),
    ): String {
        val maxListChars = (maxTotalChars / 4).coerceAtLeast(4_000)
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
                if (sb.length + line.length > maxListChars) break
                sb.append(line)
                listed++
            }
            if (listed < all.size) sb.append("… and ${all.size - listed} more files (list truncated)\n")

            val priority = mutableListOf<String>()
            openFilePath?.let { if (all.contains(it)) priority.add(it) }
            mentionedFiles.filter { all.contains(it) && !priority.contains(it) }.forEach { priority.add(it) }
            PRIORITY_FILES.filter { all.contains(it) && !priority.contains(it) }.forEach { priority.add(it) }

            val contentBudget = maxTotalChars - DIFF_RESERVE_CHARS
            sb.append("\nKey file contents:\n")
            // The open file is filtered from `all` above when sensitive, so
            // it never reaches the priority loop — say so explicitly instead
            // of silently dropping the file the user is looking at.
            if (openFilePath != null && SensitivePaths.isSensitive(openFilePath)) {
                sb.append("### ").append(openFilePath)
                    .append(" (contents withheld: sensitive file — open in the editor but never sent to the provider)\n\n")
            }
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
            if (diffNote != null && sb.length < maxTotalChars) sb.append(diffNote).append('\n')
            sb.toString().take(maxTotalChars)
        }.getOrElse {
            // Context failure must never leave the chat stuck in a streaming state.
            "Project context could not be read safely. Continue using the user's request and create or update the required files." +
                (diffNote?.let { "\n$it" } ?: "")
        }
    }
}
