package com.anvex.core.engine

/** Central, side-effect-free risk policy used before any AI-originated action. */
object ActionSafetyPolicy {
    enum class Risk { SAFE, REQUIRES_APPROVAL }
    data class Assessment(val risk: Risk, val reason: String) {
        val requiresApproval: Boolean get() = risk == Risk.REQUIRES_APPROVAL
    }

    fun assess(command: String): Assessment {
        val normalized = command.trim().lowercase()
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
        // redirection is the ONLY shell control token present.
        val controlTokens = SHELL_CONTROL_TOKENS.filter { normalized.contains(it) }
        if (controlTokens.isNotEmpty() &&
            !(controlTokens.all { it == ">" } && ECHO_REDIRECT_LIKE.matches(normalized))
        ) {
            return Assessment(Risk.REQUIRES_APPROVAL, "Compound shell syntax can hide additional side effects.")
        }
        val dangerous = listOf(
            Regex("^rm(?:\\s+|$).*(?:-r|-R|--recursive|--dir)"),
            Regex("^git\\s+reset\\s+.*--hard(?:\\s|$)"),
            Regex("^git\\s+clean\\s+.*(?:-f|--force)"),
            Regex("^chmod\\s+.*(?:^|\\s)777(?:\\s|$)"),
            Regex("^mv\\s+.*(?:^|\\s)\\.git(?:/|\\s|$)"),
            Regex("^dd\\s+"),
            Regex("^mkfs(?:\\.|\\s|$)"),
            Regex("^format(?:\\s|$)"),
        )
        return if (dangerous.any { it.containsMatchIn(normalized) })
            Assessment(Risk.REQUIRES_APPROVAL, "This action can irreversibly modify or remove project data.")
        else Assessment(Risk.SAFE, "No high-impact pattern detected.")
    }

    private val SHELL_CONTROL_TOKENS = listOf(";", "&&", "||", "|", "`", "\$(", ">", "<")

    /** `echo <plain text> >>? <file>` — no other shell syntax allowed. */
    private val ECHO_REDIRECT_LIKE = Regex("^echo\\s+[^;&|<>`\\$]+\\s*>>?\\s*[^;&|<>`\\$]+$")

    fun assess(action: BoltAction): Assessment = when (action) {
        is BoltAction.FileAction -> if (action.content.toByteArray().size > 2_000_000)
            Assessment(Risk.REQUIRES_APPROVAL, "Large AI file mutation requires explicit approval.")
        else Assessment(Risk.SAFE, "File mutation is within the safe size budget.")
        is BoltAction.ShellAction -> assess(action.content)
        is BoltAction.StartAction -> assess(action.content)
        is BoltAction.ArtifactAction -> if (action.content.toByteArray().size > 20 * 1024 * 1024 || action.paths.size > 20_000)
            Assessment(Risk.REQUIRES_APPROVAL, "Large generated-file request exceeds the normal packaging budget.")
        else Assessment(Risk.SAFE, "Generated-file request is within the packaging budget.")
        is BoltAction.ToolAction -> if (action.name == "mcp")
            Assessment(Risk.REQUIRES_APPROVAL, "External MCP tool call needs the user's approval.")
        else Assessment(Risk.SAFE, "Read-only tool call.")
        is BoltAction.UnknownAction -> Assessment(Risk.REQUIRES_APPROVAL, "Unknown action type cannot be executed without explicit approval.")
    }
}
