package com.anvex.core.android.terminal

/**
 * Heuristic detection of interactive prompts at the tail of a terminal
 * stream (F3, P2) — the "[y/n]", "Continue?" and password questions that in
 * a pipe-based terminal just hang forever. Pure Kotlin, unit-tested.
 *
 * Two signal classes:
 *  - Prompt-shaped last line: ends with "? ", ": " after a question, or a
 *    bracketed choice like `[y/n]`, `(yes/no)`.
 *  - ECHO-off: the strongest signal — the program disabled terminal echo
 *    (checked natively via termios); combined with a non-blank current line
 *    it means a secret is being read. [detect] takes that as an input so
 *    this class stays Android-free.
 *
 * Deliberately conservative: a normal shell prompt ($ / # ending) and plain
 * output lines never match, so the answer sheet cannot pop up spuriously
 * during ordinary output.
 */
object InteractivePromptDetector {

    enum class Kind { CHOICE, QUESTION, SECRET }

    data class Prompt(val line: String, val kind: Kind, val secret: Boolean)

    private val CHOICE = Regex("""\[(y/n|yes/no|Y/n|y/N)\]|\((y/n|yes/no)\)""")
    private val SHELL_PROMPT_END = Regex("""[$#]\s*$""")

    fun detect(cursorLine: String, echoOff: Boolean): Prompt? {
        val line = cursorLine.trimEnd()
        if (line.isBlank()) return null
        if (echoOff) return Prompt(line.trim(), Kind.SECRET, secret = true)
        if (SHELL_PROMPT_END.containsMatchIn(line)) return null
        if (CHOICE.containsMatchIn(line)) return Prompt(line.trim(), Kind.CHOICE, secret = false)
        val trimmed = line.trim()
        if (trimmed.endsWith("?") || (trimmed.endsWith(":") && trimmed.contains('?'))) {
            return Prompt(trimmed, Kind.QUESTION, secret = false)
        }
        if (trimmed.contains("password", ignoreCase = true) && trimmed.endsWith(":")) {
            return Prompt(trimmed, Kind.SECRET, secret = true)
        }
        return null
    }

    /**
     * The wire answer for a prompt: choice prompts accept the typed letter
     * verbatim; everything is terminated with CR (what a real Enter sends
     * to a PTY in canonical mode).
     */
    fun answerBytes(answer: String): ByteArray = (answer + "\r").toByteArray(Charsets.UTF_8)
}
