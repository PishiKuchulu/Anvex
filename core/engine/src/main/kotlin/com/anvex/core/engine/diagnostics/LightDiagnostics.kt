package com.anvex.core.engine.diagnostics

/**
 * v4.0 (GOAL 2): lightweight on-device diagnostics with LSP-shaped results.
 *
 * Honest scope (docs/KNOWN_LIMITATIONS.md): this is NOT a full Language Server
 * Protocol implementation — it is a deterministic static checker for the file kinds
 * the harness edits most (JSON, HTML, CSS, JS/TS syntax basics, bracket balance,
 * TODO/FIXME markers). Results are delivered in LSP PublishDiagnostics shape
 * (uri + list of severity/range/message) so the harness UI can render them exactly
 * like editor diagnostics; JS/JSX *parse-level* diagnostics additionally run in the
 * preview WebView through the bundled Babel parser (see docs/HARNESS.md).
 */
object LightDiagnostics {

    enum class Severity { ERROR, WARNING, INFO }

    data class Diagnostic(
        val severity: Severity,
        val line: Int,      // 1-based
        val column: Int,    // 1-based
        val message: String,
        val source: String = "bolt-lite",
    )

    data class FileDiagnostics(val uri: String, val diagnostics: List<Diagnostic>)

    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024
    const val MAX_DIAGNOSTICS_PER_FILE: Int = 200

    fun analyze(path: String, content: String): FileDiagnostics {
        val diagnostics = ArrayList<Diagnostic>()
        val ext = path.substringAfterLast('.', "").lowercase()
        when (ext) {
            "json" -> analyzeJson(content, diagnostics)
            "html", "htm" -> analyzeHtml(content, diagnostics)
            "css" -> analyzeCss(content, diagnostics)
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "kt", "java" -> analyzeJs(content, diagnostics)
            "md", "markdown" -> analyzeTodos(content, diagnostics)
            else -> return FileDiagnostics(path, emptyList())
        }
        return FileDiagnostics(path, diagnostics.take(MAX_DIAGNOSTICS_PER_FILE))
    }

    // ------------------------------------------------------------------ checkers

    private fun analyzeJson(content: String, out: ArrayList<Diagnostic>) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            out += Diagnostic(Severity.WARNING, 1, 1, "File is empty.")
            return
        }
        val balance = balanceScan(content, brackets = listOf('{' to '}', '[' to ']'))
        balance.forEach { out += it }
        if (balance.isEmpty()) {
            duplicateTopLevelKeys(trimmed)?.let { (line, key) ->
                out += Diagnostic(Severity.WARNING, line, 1, "Duplicate top-level key \"$key\".")
            }
        }
        analyzeTodos(content, out)
    }

    private fun analyzeHtml(content: String, out: ArrayList<Diagnostic>) {
        val tags = Regex("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*?)(/?)>")
        val stack = ArrayDeque<Pair<String, Int>>()
        var matchCount = 0
        for (m in tags.findAll(content)) {
            matchCount++
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val selfClosing = m.groupValues[4] == "/" || name in VOID_TAGS
            val line = content.lineOf(m.range.first)
            if (selfClosing) { /* no stack entry */ }
            else if (!closing) {
                stack.addLast(name to line)
            } else {
                val open = stack.removeLastOrNull()
                if (open == null) {
                    out += Diagnostic(Severity.ERROR, line, 1, "Unexpected closing tag </$name> — no matching open tag.")
                } else if (open.first != name) {
                    out += Diagnostic(Severity.ERROR, line, 1, "Mismatched closing tag </$name>; expected </${open.first}> (opened at line ${open.second}).")
                }
            }
            if (matchCount > 5_000) break
        }
        stack.forEach { (name, line) ->
            out += Diagnostic(Severity.ERROR, line, 1, "Unclosed tag <$name>.")
        }
        if (!content.contains("<!doctype html", ignoreCase = true) && content.contains("<html", ignoreCase = true)) {
            out += Diagnostic(Severity.INFO, 1, 1, "Missing <!DOCTYPE html> declaration.")
        }
        analyzeTodos(content, out)
    }

    private fun analyzeCss(content: String, out: ArrayList<Diagnostic>) {
        balanceScan(content, brackets = listOf('{' to '}')).forEach { out += it }
        analyzeTodos(content, out)
    }

    private fun analyzeJs(content: String, out: ArrayList<Diagnostic>) {
        // Brace/bracket/paren balance over a string/comment-stripped copy.
        val stripped = stripStringsAndComments(content)
        balanceScan(stripped, brackets = listOf('(' to ')', '{' to '}', '[' to ']')).forEach { diag ->
            out += if (diag.severity == Severity.ERROR) diag.copy(message = "Unbalanced bracket: ${diag.message}") else diag
        }
        analyzeTodos(content, out)
    }

    private fun analyzeTodos(content: String, out: ArrayList<Diagnostic>) {
        Regex("(TODO|FIXME|HACK)\\b(.{0,80})").findAll(content).take(50).forEach { m ->
            val line = content.lineOf(m.range.first)
            val column = m.range.first - (content.lastIndexOf('\n', m.range.first).takeIf { it >= 0 } ?: -1)
            out += Diagnostic(Severity.INFO, line, column, m.value.trim())
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun balanceScan(content: String, brackets: List<Pair<Char, Char>>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val stack = ArrayDeque<Triple<Char, Int, Int>>() // open char, line, column
        val opens = brackets.map { it.first }.toSet()
        val closes = brackets.associate { it.second to it.first } // closer -> opener
        var line = 1
        var col = 0
        for (c in content) {
            if (c == '\n') { line++; col = 0; continue }
            col++
            if (c in opens) {
                stack.addLast(Triple(c, line, col))
            } else if (closes.containsKey(c)) {
                val open = stack.removeLastOrNull()
                if (open == null) {
                    out += Diagnostic(Severity.ERROR, line, col, "Extra closing '$c' with no matching open.")
                } else if (closes[c] != open.first) {
                    out += Diagnostic(Severity.ERROR, line, col, "Closing '$c' does not match '${open.first}' (opened at line ${open.second}).")
                }
            }
            if (out.size >= MAX_DIAGNOSTICS_PER_FILE) break
        }
        stack.forEach { (c, l, c2) ->
            out += Diagnostic(Severity.ERROR, l, c2, "Unclosed '$c'.")
        }
        return out
    }

    private fun duplicateTopLevelKeys(json: String): Pair<Int, String>? {
        return runCatching {
            var depth = 0
            val topLevelKeys = ArrayList<Pair<Int, String>>()
            var inString = false
            var escape = false
            var keyStart = -1
            var line = 1
            var keyLine = 1
            var i = 0
            while (i < json.length) {
                val c = json[i]
                if (c == '\n') line++
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> {
                            inString = false
                            if (depth == 1 && keyStart >= 0) topLevelKeys += keyLine to json.substring(keyStart, i)
                            keyStart = -1
                        }
                    }
                } else when (c) {
                    '"' -> { inString = true; keyStart = i + 1; keyLine = line }
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
                i++
            }
            topLevelKeys.groupBy { it.second }.entries.firstOrNull { it.value.size > 1 }?.let { entry ->
                entry.value.first().first to entry.key
            }
        }.getOrNull()
    }

    /** Replaces string/comment interiors with spaces so bracket scanning is safe. */
    private fun stripStringsAndComments(content: String): String {
        val out = StringBuilder(content.length)
        var i = 0
        var mode = 0 // 0 code, 1 line comment, 2 block comment, 3 double-quoted, 4 template, 5 single-quoted
        while (i < content.length) {
            val c = content[i]
            val next = if (i + 1 < content.length) content[i + 1] else ' '
            when (mode) {
                0 -> when {
                    c == '/' && next == '/' -> { mode = 1; out.append("  "); i += 2; continue }
                    c == '/' && next == '*' -> { mode = 2; out.append("  "); i += 2; continue }
                    c == '"' -> { mode = 3; out.append(' ') }
                    c == '\'' -> { mode = 5; out.append(' ') }
                    c == '`' -> { mode = 4; out.append(' ') }
                    else -> out.append(c)
                }
                1 -> if (c == '\n') { mode = 0; out.append('\n') } else out.append(' ')
                2 -> when {
                    c == '*' && next == '/' -> { mode = 0; out.append("  "); i += 2; continue }
                    c == '\n' -> out.append('\n')
                    else -> out.append(' ')
                }
                3, 5 -> when {
                    c == '\\' -> { out.append("  "); i += 2; continue }
                    (mode == 3 && c == '"') || (mode == 5 && c == '\'') -> { mode = 0; out.append(' ') }
                    c == '\n' -> out.append('\n')
                    else -> out.append(' ')
                }
                4 -> when {
                    c == '\\' -> { out.append("  "); i += 2; continue }
                    c == '`' -> { mode = 0; out.append(' ') }
                    c == '\n' -> out.append('\n')
                    else -> out.append(' ')
                }
            }
            i++
        }
        return out.toString()
    }

    private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

    private fun String.lineOf(index: Int): Int {
        var line = 1
        var i = 0
        val limit = index.coerceAtMost(length)
        while (i < limit) {
            if (this[i] == '\n') line++
            i++
        }
        return line
    }
}
