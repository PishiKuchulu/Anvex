package com.anvex.core.engine

/**
 * CommandExecutor — the internal command/runtime layer required by the brief
 * (§17). Translates whitelisted shell semantics into filesystem effects.
 *
 * IMPORTANT: nothing is ever spawned as a process. No Runtime.exec, no Termux,
 * no node. Commands outside the whitelist produce an explicit tiered result.
 */
object CommandTranslator {

    enum class Tier { SUPPORTED, PARTIALLY, UNSUPPORTED }

    sealed class Effect {
        data class Mkdir(val paths: List<String>) : Effect()
        data class Touch(val paths: List<String>) : Effect()
        data class Write(val path: String, val content: String, val append: Boolean) : Effect()
        data class Delete(val paths: List<String>, val recursive: Boolean) : Effect()
        data class Move(val from: String, val to: String) : Effect()
        data class Copy(val from: String, val to: String) : Effect()
        data class ListDir(val path: String) : Effect()
        data class ReadFile(val path: String) : Effect()
        data class RecordDependencies(val note: String) : Effect()
        object NoOp : Effect()
    }

    data class Part(
        val original: String,
        val tier: Tier,
        val effect: Effect,
        val note: String,
    )

    data class Translation(val parts: List<Part>) {
        val allSupported: Boolean get() = parts.all { it.tier != Tier.UNSUPPORTED }
    }

    /**
     * Public capability query (v1.1 §9 CommandCapability) — used by the
     * capability-aware system prompt and the Debug screen so the AI and the user
     * always see the truth about what this runtime can execute.
     */
    fun capabilityOf(command: String): Tier = translate(command).parts
        .maxByOrNull { it.tier.ordinal }?.tier ?: Tier.SUPPORTED

    /** Human-readable capability matrix for prompts/UI (v1.1 §9/§28). */
    val capabilityMatrix: List<Pair<String, Tier>> = listOf(
        "mkdir" to Tier.SUPPORTED,
        "touch" to Tier.SUPPORTED,
        "cat" to Tier.SUPPORTED,
        "cp" to Tier.SUPPORTED,
        "mv" to Tier.SUPPORTED,
        "rm" to Tier.SUPPORTED,
        "ls" to Tier.SUPPORTED,
        "echo (with > / >>)" to Tier.SUPPORTED,
        "cd / pwd" to Tier.SUPPORTED,
        "npm install" to Tier.PARTIALLY,
        "yarn / pnpm / bun install" to Tier.PARTIALLY,
        "npm run dev" to Tier.UNSUPPORTED,
        "npx" to Tier.UNSUPPORTED,
        "node" to Tier.UNSUPPORTED,
        "vite" to Tier.UNSUPPORTED,
        "tsc" to Tier.UNSUPPORTED,
        "git" to Tier.UNSUPPORTED,
        "python" to Tier.UNSUPPORTED,
    )

    /** Split compound commands on `&&` and `;` (shell && semantics simplified). */
    fun translate(command: String): Translation {
        val segments = command
            .replace("&&", "\u0001")
            .replace(";", "\u0001")
            .split('\u0001')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val parts = segments.map(::translateOne)
        return Translation(parts)
    }

    fun translateOne(cmdRaw: String): Part {
        val cmd = cmdRaw.trim()
        if (cmd.isEmpty()) return Part(cmd, Tier.SUPPORTED, Effect.NoOp, "Empty command")

        // Do not partially interpret shell syntax that this translator cannot model.
        // In particular, `ls || rm ...` must never degrade into a harmless-looking `ls`.
        val unsupportedShellSyntax = listOf("||", "|", "`", "$(", "<")
        if (unsupportedShellSyntax.any { cmd.contains(it) } || (cmd.contains('>') && !cmd.startsWith("echo "))) {
            return Part(cmd, Tier.UNSUPPORTED, Effect.NoOp, "Unsupported shell syntax; use a simple allowlisted command or the real Agent/Terminal runtime.")
        }

        // --- npm / pnpm / yarn / bun family -----------------------------------
        if (cmd == "npm install" || cmd == "npm i" || cmd.matches(NPM_INSTALL_RE)) {
            return Part(
                cmd, Tier.PARTIALLY, Effect.RecordDependencies(
                    "Dependencies recorded from package.json. On Android, dependencies resolve " +
                        "at preview time via CDN import maps (esm.sh) — no node_modules is created."
                ), "Dependencies recorded"
            )
        }
        if (cmd == "yarn" || cmd == "yarn install" || cmd == "pnpm install" || cmd == "bun install") {
            return Part(cmd, Tier.PARTIALLY, Effect.RecordDependencies(
                "Dependencies recorded from package.json; resolved at preview time via CDN."
            ), "Dependencies recorded")
        }
        if (NPM_RUN_RE.matches(cmd) || cmd.startsWith("npx ") || cmd.startsWith("vite") ||
            cmd.startsWith("node ") || cmd.startsWith("python") || cmd.startsWith("git ") ||
            cmd.startsWith("tsc ") || cmd.startsWith("wrangler") || cmd.startsWith("remix")
        ) {
            return Part(
                cmd, Tier.UNSUPPORTED, Effect.NoOp,
                "Not executable in the Preview/Chat translation runtime: Android does not run Node.js/npm processes in this command layer. " +
                    "Use the Preview tab instead — supported project types run directly in the embedded WebView runtime."
            )
        }

        // --- file operations whitelist ------------------------------------------
        val tokens = tokenize(cmd)
        if (tokens.isEmpty()) return Part(cmd, Tier.SUPPORTED, Effect.NoOp, "No-op")
        val head = tokens.first()

        when (head) {
            "mkdir" -> {
                val raw = tokens.drop(1).filter { !it.startsWith("-") }
                val dirs = raw.mapNotNull(PathSanitizer::sanitizeOrNull)
                val rejected = raw.filter { PathSanitizer.sanitizeOrNull(it) == null }
                if (dirs.isEmpty()) return bad(cmd, "mkdir: missing or invalid directory operand" +
                    if (rejected.isNotEmpty()) " (rejected: ${rejected.joinToString(", ")})" else "")
                return Part(cmd, Tier.SUPPORTED, Effect.Mkdir(dirs), "Created ${dirs.size} director" +
                    (if (dirs.size == 1) "y" else "ies") +
                    if (rejected.isEmpty()) "" else " (rejected unsafe path(s): ${rejected.size})")
            }
            "touch" -> {
                val raw = tokens.drop(1)
                val files = raw.mapNotNull(PathSanitizer::sanitizeOrNull)
                if (files.isEmpty()) return bad(cmd, "touch: missing or invalid operand")
                return Part(cmd, Tier.SUPPORTED, Effect.Touch(files), "Created ${files.size} file(s)")
            }
            "rm" -> {
                val recursive = tokens.contains("-r") || tokens.contains("-rf") || tokens.contains("-fr") || tokens.contains("-R")
                val raw = tokens.drop(1).filter { !it.startsWith("-") }
                val paths = raw.mapNotNull(PathSanitizer::sanitizeOrNull)
                if (paths.isEmpty()) return bad(cmd, "rm: missing or invalid operand")
                return Part(cmd, Tier.SUPPORTED, Effect.Delete(paths, recursive), "Deleted ${paths.size} path(s)" +
                    if (paths.size < raw.size) " (rejected ${raw.size - paths.size} unsafe path(s))" else "")
            }
            "mv" -> if (tokens.size == 3) {
                val from = PathSanitizer.sanitizeOrNull(tokens[1])
                val to = PathSanitizer.sanitizeOrNull(tokens[2])
                if (from == null || to == null) return bad(cmd, "mv: invalid path")
                return Part(cmd, Tier.SUPPORTED, Effect.Move(from, to), "Moved $from")
            } else return bad(cmd, "mv: expected 2 operands")
            "cp" -> if (tokens.size == 3) {
                val from = PathSanitizer.sanitizeOrNull(tokens[1])
                val to = PathSanitizer.sanitizeOrNull(tokens[2])
                if (from == null || to == null) return bad(cmd, "cp: invalid path")
                return Part(cmd, Tier.SUPPORTED, Effect.Copy(from, to), "Copied $from")
            } else return bad(cmd, "cp: expected 2 operands")
            "ls" -> {
                val p = tokens.drop(1).filter { !it.startsWith("-") }
                    .firstOrNull()?.let(PathSanitizer::sanitizeOrNull) ?: ""
                return Part(cmd, Tier.SUPPORTED, Effect.ListDir(p), "Listed /${if (p.isEmpty()) "" else p}")
            }
            "cat" -> {
                val p = tokens.drop(1).firstOrNull()?.let(PathSanitizer::sanitizeOrNull) ?: return bad(cmd, "cat: missing operand")
                return Part(cmd, Tier.SUPPORTED, Effect.ReadFile(p), "Read $p")
            }
            "cd" -> return Part(cmd, Tier.SUPPORTED, Effect.NoOp, "Directory context tracked virtually")
            "echo" -> {
                val m = ECHO_REDIRECT_RE.find(cmd)
                if (m != null) {
                    val text = m.groupValues[1].trim('"', '\'')
                    val file = PathSanitizer.sanitizeOrNull(m.groupValues[5]) ?: return bad(cmd, "echo: bad target")
                    val append = m.groupValues[4] == ">>"
                    return Part(cmd, Tier.SUPPORTED, Effect.Write(file, text, append), "Wrote $file")
                }
                return Part(cmd, Tier.SUPPORTED, Effect.NoOp, cmd)
            }
            "pwd" -> return Part(cmd, Tier.SUPPORTED, Effect.NoOp, "/home/project")
            "clear", "true", "exit", "which" -> return Part(cmd, Tier.SUPPORTED, Effect.NoOp, "No-op")
        }
        return Part(
            cmd, Tier.UNSUPPORTED, Effect.NoOp,
            "Command not available in the Android runtime: '$head'. Only a whitelist of file " +
                "operations is translated; process execution is disabled for security."
        )
    }

    private fun bad(cmd: String, note: String) = Part(cmd, Tier.UNSUPPORTED, Effect.NoOp, note)

    /** Minimal POSIX-ish tokenizer honoring single/double quotes. */
    private fun tokenize(cmd: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (c in cmd) {
            when {
                quote != null -> {
                    if (c == quote) quote = null else sb.append(c)
                }
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        out.add(sb.toString()); sb.clear()
                    }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private val NPM_INSTALL_RE = Regex("""^npm (install|i|add)( --\S+)*$""")
    private val NPM_RUN_RE = Regex("""^npm run \S+.*$""")
    private val ECHO_REDIRECT_RE = Regex("""^echo\s+("([^"]*)"|'([^']*)'|\S+)\s*(>>?)\s*(\S+)$""")
}
