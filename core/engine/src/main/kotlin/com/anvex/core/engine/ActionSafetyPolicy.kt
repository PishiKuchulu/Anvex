package com.anvex.core.engine

/** Central, side-effect-free risk policy used before any AI-originated action. */
object ActionSafetyPolicy {
    enum class Risk { SAFE, REQUIRES_APPROVAL }

    /**
     * [detail] carries the concrete instance data behind a verdict — the
     * command text, target path + size, artifact name — so an approval
     * prompt can show the user WHAT is being approved, not just the
     * category of risk (audit round 2, N1: the dialog used to be blind).
     * Empty for SAFE verdicts and for verdicts with no instance data.
     */
    data class Assessment(val risk: Risk, val reason: String, val detail: String = "") {
        val requiresApproval: Boolean get() = risk == Risk.REQUIRES_APPROVAL
    }

    private const val MAX_DETAIL_CHARS = 4000

    /** Human byte count for approval details (e.g. "2.4 MB"). */
    private fun humanBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Preview of free-form content for an approval detail, length-bounded. */
    private fun preview(text: String): String {
        val clean = text.trim()
        return if (clean.length > MAX_DETAIL_CHARS) clean.take(MAX_DETAIL_CHARS) + "…" else clean
    }

    fun assess(command: String): Assessment {
        val verdict = assessCommand(command)
        // Approval prompts must name the command being approved.
        return if (verdict.requiresApproval && verdict.detail.isBlank()) {
            verdict.copy(detail = preview(command))
        } else {
            verdict
        }
    }

    private fun assessCommand(command: String): Assessment {
        // v4.10.1 (external review SEC-03): the old policy matched anchored
        // regexes against the raw string, so `/bin/rm -rf x`, `env rm -rf x`,
        // `rm${IFS}-rf x`, `find . -delete`, `git -C . reset --hard` and
        // `echo hi > ../../x` all read as SAFE. Assessment now normalizes
        // ($IFS, wrappers, path prefixes) and judges EVERY command segment.
        //
        // v4.10.2 (second external review #2/#6): two holes remained.
        // (a) The whole command was lowercased, so `git branch -D` became
        // `git branch -d` before the `-D` comparison — that branch was dead
        // code and force-deleting a branch read as SAFE. Case is now kept:
        // only the command word and the git subcommand are lowercased for
        // matching, arguments keep their case (in shell, `-D` and `-d`
        // genuinely differ). (b) The bypass table was still a denylist
        // over the FIRST word: `sh -c '...'`, `'rm' -rf`, `toybox rm`,
        // `nice -n 10 rm`, `timeout 5 rm`, `node -e '...'`, `git push
        // origin +main`, `rm --recursive=x`, `mv x /dev/null`,
        // `chmod -R 000 .` and `curl -d @.env ...` all read as SAFE.
        // The fixes below: quote-stripped command words, wrapper flag
        // skipping, nested shells / interpreter -e/-c gated outright,
        // --flag=value normalization, git push gated as a publish, and
        // /dev/null + curl-upload + recursive-chmod gates.
        val normalized = command.trim()
            .replace("\${IFS}", " ", ignoreCase = true)
            .replace("\$IFS", " ", ignoreCase = true)
        if (normalized.isBlank()) return Assessment(Risk.SAFE, "Empty command")
        // Newline/CR and shell line-continuation can introduce a second command
        // while still looking like a single allowlisted command. Treat all
        // line separators and backslashes as compound shell syntax.
        if (normalized.contains('\n') || normalized.contains('\r') || normalized.contains('\\')) {
            return Assessment(Risk.REQUIRES_APPROVAL, "Line separators or shell escaping can hide additional commands.")
        }
        // Compound shell syntax is high-impact by default: a harmless-looking
        // first command must never hide a destructive second command.
        // v4.0 fix: `echo <text> > file` / `>> file` is a plain journaled workspace
        // write (revertible through the mutation journal), so it stays SAFE when
        // redirection is the ONLY shell control token present AND the target
        // stays inside the workspace.
        val controlTokens = SHELL_CONTROL_TOKENS.filter { normalized.contains(it) }
        if (controlTokens.isNotEmpty() && !(controlTokens.all { it == ">" } && isSafeEchoRedirect(normalized))) {
            return Assessment(Risk.REQUIRES_APPROVAL, "Compound shell syntax can hide additional side effects.")
        }
        // A redirect that escapes the workspace is a write to an arbitrary
        // location (~/.bashrc, /etc, ../) — approval, whoever performs it.
        redirectTarget(normalized)?.let { target ->
            if (target.startsWith("/") || target.startsWith("~") || target.split('/').any { it == ".." }) {
                return Assessment(Risk.REQUIRES_APPROVAL, "Redirection writes outside the workspace.")
            }
        }
        for (segment in segmentsOf(normalized)) {
            val (cmd, args) = commandWord(segment) ?: continue
            segmentRisk(cmd, args)?.let { return it }
        }
        // M-01: this is a DENY-list verdict — "no known pattern matched",
        // not a proof of safety. Callers must never present it as a
        // guarantee (the Developer Center label says exactly this), and
        // the AI shell path additionally allow-lists commands rather
        // than trusting this verdict.
        return Assessment(Risk.SAFE, "No high-impact pattern detected.")
    }

    /** Split a command line into its command segments (;, &&, ||, |). */
    private fun segmentsOf(normalized: String): List<String> =
        normalized.split(Regex("\\s*(?:;|&&|\\|\\||\\|)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    private val WRAPPER_WORDS = setOf(
        "env", "busybox", "toybox", "sudo", "command", "time", "nice",
        "nohup", "stdbuf", "timeout", "setsid", "exec",
    )

    /** Flags of these wrappers take a value (skip flag AND value, not just the flag). */
    private fun wrapperValueFlags(wrapper: String): Set<String> = when (wrapper) {
        "env" -> setOf("-u", "--unset", "-C", "--chdir")
        "nice" -> setOf("-n", "--adjustment")
        "timeout" -> setOf("-s", "--signal", "-k", "--kill-after")
        "sudo" -> setOf("-u", "--user", "-g", "--group", "-h", "--host", "-p", "--prompt", "-t", "--timeout")
        "stdbuf" -> setOf("-i", "--input", "-o", "--output", "-e", "--error")
        "setsid" -> setOf("-c", "--ctty")
        "exec" -> setOf("-a")
        else -> emptySet()
    }

    /**
     * The effective command word of a segment: quotes stripped
     * (`'rm'` -> `rm`), path prefixes stripped (`/bin/rm` -> `rm`) and
     * wrapper commands skipped with their flags (`nice -n 10 rm` -> `rm`,
     * `timeout 5 rm` -> `rm`, `env -i rm` -> `rm`, `xargs` — for xargs
     * the FOLLOWING word is the real command).
     *
     * The returned word is lowercased for matching; the returned args
     * keep their original case (flags like git's `-D` are case-sensitive).
     */
    private fun commandWord(segment: String): Pair<String, List<String>>? {
        val tokens = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.trim('\'', '"') }
        var i = 0
        while (i < tokens.size) {
            var word = tokens[i].substringAfterLast('/').lowercase()
            // Skip env assignments (A=1 rm ...) and wrapper flags/words.
            if (word.contains('=') && !word.startsWith("-")) { i++; continue }
            if (word in WRAPPER_WORDS) {
                i = advancePastWrapper(tokens, i, word)
                continue
            }
            if (word == "xargs") {
                i++
                while (i < tokens.size && tokens[i].startsWith("-")) i++
                if (i >= tokens.size) return null
                word = tokens[i].substringAfterLast('/').lowercase()
            }
            return word to tokens.drop(i + 1)
        }
        return null
    }

    /**
     * Index of the real command word after a wrapper at [start]:
     * its flags (and their values) are skipped, then — for `timeout` —
     * the duration operand. Returns tokens.size when no command word remains.
     */
    private fun advancePastWrapper(tokens: List<String>, start: Int, wrapper: String): Int {
        var i = start + 1
        val valueFlags = wrapperValueFlags(wrapper)
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.startsWith("-") && t != "-") {
                val flag = t.substringBefore('=')
                i += if (flag in valueFlags && '=' !in t) 2 else 1
                continue
            }
            break
        }
        if (wrapper == "timeout" && i < tokens.size &&
            tokens[i].matches(Regex("\\d+(\\.\\d+)?[smhd]?"))
        ) {
            i++
        }
        return i
    }

    private fun destructive(reason: String) = Assessment(Risk.REQUIRES_APPROVAL, reason)

    /** A flag, normalized so `--recursive=x` matches `--recursive`. */
    private fun normFlag(f: String): String = f.substringBefore('=')

    private fun segmentRisk(cmd: String, args: List<String>): Assessment? {
        val flags = args.filter { it.startsWith("-") }.map { normFlag(it) }
        val operands = args.filter { !it.startsWith("-") }
        fun hasFlag(vararg names: String) = flags.any { f -> names.any { f == it || (f.length > 2 && !f.startsWith("--") && it.removePrefix("-").all { c -> c in f }) } }
        // Nested shells and code-evaluating builtins execute an arbitrary
        // string the word-level checks never see (`sh -c 'rm -rf .'`,
        // `eval "$(...)"`): they always need a human.
        if (cmd in SHELL_COMMANDS) {
            return destructive("A nested shell can hide arbitrary commands behind one word.")
        }
        if (cmd in setOf("eval", "source", ".")) {
            return destructive("Shell evaluation executes an arbitrary command string.")
        }
        if (cmd in INTERPRETER_COMMANDS && args.any { it == "-e" || it == "-c" || it == "--eval" || it == "-r" }) {
            return destructive("Interpreter code execution (-e/-c) runs arbitrary code.")
        }
        return when (cmd) {
            "rm" -> when {
                hasFlag("-r", "-R", "--recursive", "--dir") ->
                    destructive("Recursive delete can irreversibly remove project data.")
                operands.any { it.contains('*') || it.contains('?') } ->
                    destructive("Wildcard delete can remove many files at once.")
                hasFlag("-f", "--force") && operands.size > 1 ->
                    destructive("Forced delete of multiple paths.")
                else -> null
            }
            "find" -> if (args.any { it == "-delete" || it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir" }) {
                destructive("find with -delete/-exec can modify or remove many files.")
            } else null
            "git" -> {
                // Skip global options (git -C <path> reset --hard …).
                var j = 0
                while (j < args.size && args[j].startsWith("-")) { j += if (args[j] in setOf("-C", "-c", "--git-dir", "--work-tree")) 2 else 1 }
                val sub = args.getOrNull(j)?.lowercase()
                val rest = args.drop(j + 1)
                when {
                    sub == "reset" && rest.any { it == "--hard" } -> destructive("git reset --hard discards local changes.")
                    sub == "clean" && rest.any { it.startsWith("-") && it.contains('f') } -> destructive("git clean force-removes untracked files.")
                    sub == "checkout" && (rest.any { it == "." } || rest.any { it == "--" } || rest.any { it == "-f" || it == "--force" }) -> destructive("git checkout can discard local changes.")
                    sub == "switch" && rest.any { it == "-f" || it == "--force" } -> destructive("git switch --force can discard local changes.")
                    sub == "restore" && (rest.any { it == "." } || rest.any { it == "--" } || rest.any { it == "-W" || it == "--worktree" }) -> destructive("git restore can discard local changes.")
                    sub == "push" -> destructive("git push publishes to a remote.")
                    sub == "branch" && rest.any { it == "-D" || it == "--force" || it == "-f" } -> destructive("Force-deleting or force-moving a branch loses commits.")
                    sub == "stash" && rest.any { it == "drop" || it == "clear" } -> destructive("Dropping stashes loses saved work.")
                    else -> null
                }
            }
            "dd", "shred", "format" -> destructive("Low-level destructive command.")
            "mkfs", "mkfs.ext4", "mkfs.vfat", "mke2fs" -> destructive("Filesystem format command.")
            "truncate" -> if (args.any { it == "-s" || it == "--size" } && args.any { it == "0" }) {
                destructive("Truncating a file to zero destroys its content.")
            } else null
            "chmod" -> when {
                args.any { it == "777" } -> destructive("chmod 777 makes files world-writable.")
                hasFlag("-R", "--recursive") -> destructive("Recursive chmod changes permissions on many files at once.")
                else -> null
            }
            "mv", "cp" -> when {
                args.any { it == ".git" || it.startsWith(".git/") } ->
                    destructive("Moving/copying repository metadata (.git).")
                operands.any { it == "/dev/null" || it == "/dev/zero" } ->
                    destructive("Moving/copying through /dev/null or /dev/zero destroys or fabricates file content.")
                else -> null
            }
            "curl", "wget" -> if (isUpload(args)) {
                destructive("Uploading a local file to a remote host can exfiltrate project data.")
            } else Assessment(
                Risk.REQUIRES_APPROVAL,
                "Network request (curl/wget) needs approval: the URL, query string and headers can carry project data off the device.",
            )
            // Other direct network tools: the same egress channel as curl.
            "nc", "ncat", "ssh", "scp", "sftp", "ftp" -> Assessment(
                Risk.REQUIRES_APPROVAL,
                "Network command needs approval: it can move project data off the device.",
            )
            // Deliberately NOT gated here: package installs (npm install)
            // and package-manager downloads, which resolve through
            // configured registries. Direct network commands ARE gated
            // since the 2026-11-05 external audit (H-01): a plain GET can
            // carry data out in its query string or headers, so curl/wget
            // "download" forms are no longer treated as harmless. (The
            // native agent shell is unaffected either way — its allowlist
            // never included these tools; this gate bites on the Pi
            // engine's general bash and on AI-originated terminal use.)
            else -> null
        }
    }

    /** curl/wget forms that send a local file out: -T/--upload-file, -d @file, -F field=@file, --post-file. */
    private fun isUpload(args: List<String>): Boolean {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-T" || a == "--upload-file" -> return true
                a.startsWith("--upload-file=") -> return true
                a == "--post-file" || a.startsWith("--post-file=") -> return true
                a in setOf("-d", "--data", "--data-binary", "--data-raw", "--data-ascii", "--data-urlencode") -> {
                    val v = args.getOrNull(i + 1)
                    if (v != null && v.startsWith("@")) return true
                }
                a.startsWith("--data") && a.substringAfter('=', "").startsWith("@") -> return true
                a in setOf("-F", "--form") -> {
                    val v = args.getOrNull(i + 1)
                    if (v != null && ("=@" in v || v.startsWith("@"))) return true
                }
                a.startsWith("--form=") && "=@" in a -> return true
            }
            i++
        }
        return false
    }

    /** The target of the LAST `>`/`>>` redirect in the line, quotes stripped. */
    private fun redirectTarget(normalized: String): String? {
        val idx = normalized.lastIndexOf('>')
        if (idx < 0) return null
        val tail = normalized.substring(idx + 1).trimStart('>').trim()
        if (tail.isEmpty()) return null
        return tail.split(Regex("\\s+")).firstOrNull()?.trim('"', '\'')
    }

    private fun isSafeEchoRedirect(normalized: String): Boolean {
        if (!ECHO_REDIRECT_LIKE.matches(normalized)) return false
        val target = redirectTarget(normalized) ?: return false
        return !(target.startsWith("/") || target.startsWith("~") || target.split('/').any { it == ".." })
    }

    private val SHELL_CONTROL_TOKENS = listOf(";", "&&", "||", "|", "`", "\$(", ">", "<")

    private val SHELL_COMMANDS = setOf(
        "sh", "bash", "dash", "zsh", "fish", "ksh", "csh", "tcsh", "ash", "chroot",
    )

    private val INTERPRETER_COMMANDS = setOf(
        "node", "nodejs", "deno", "bun", "python", "python2", "python3",
        "perl", "ruby", "php", "lua",
    )

    /** `echo <plain text> >>? <file>` — no other shell syntax allowed. */
    private val ECHO_REDIRECT_LIKE = Regex("^echo\\s+[^;&|<>`\\$]+\\s*>>?\\s*[^;&|<>`\\$]+$")

    fun assess(action: BoltAction): Assessment = when (action) {
        is BoltAction.FileAction -> {
            val size = action.content.toByteArray().size
            if (size > 2_000_000)
                Assessment(
                    Risk.REQUIRES_APPROVAL,
                    "Large AI file mutation requires explicit approval.",
                    // H-03 (external audit, 2026-11-05): path + size alone
                    // is not an informed approval — include the head of
                    // the actual content (preview-capped) so the user can
                    // see what is about to be written.
                    detail = "${action.filePath} · ${humanBytes(size)}\n" + preview(action.content),
                )
            else Assessment(Risk.SAFE, "File mutation is within the safe size budget.")
        }
        is BoltAction.ShellAction -> assess(action.content)
        is BoltAction.StartAction -> assess(action.content)
        is BoltAction.ArtifactAction -> {
            val size = action.content.toByteArray().size
            if (size > 20 * 1024 * 1024 || action.paths.size > 20_000)
                Assessment(
                    Risk.REQUIRES_APPROVAL,
                    "Large generated-file request exceeds the normal packaging budget.",
                    detail = "${action.outputName} (${action.format}) · ${action.paths.size} path(s) · content ${humanBytes(size)}",
                )
            else Assessment(Risk.SAFE, "Generated-file request is within the packaging budget.")
        }
        is BoltAction.ToolAction -> if (action.name == "mcp")
            Assessment(
                Risk.REQUIRES_APPROVAL,
                "External MCP tool call needs the user's approval.",
                detail = preview(action.argument.ifBlank { action.name }),
            )
        else Assessment(Risk.SAFE, "Read-only tool call.")
        is BoltAction.UnknownAction -> Assessment(
            Risk.REQUIRES_APPROVAL,
            "Unknown action type cannot be executed without explicit approval.",
            detail = "type: ${action.type} · " + preview(action.content),
        )
    }
}
