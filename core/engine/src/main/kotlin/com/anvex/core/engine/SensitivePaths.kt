package com.anvex.core.engine

/**
 * v4.10.1 (SEC-01/SEC-02): one shared definition of "file whose content
 * must not leave the workspace through agent tools or the loopback
 * servers" — secret-bearing files and app/VCS metadata. The shell
 * runtime, the agent `read` tool and both HTTP servers all consult this
 * list (they previously each had partial, divergent rules).
 *
 * Matching is by path SEGMENT for metadata directories and by file NAME
 * for secret files — deliberately narrower than the sync hygiene sweep:
 * a source file merely CALLED `token.ts` is ordinary code, but `.env`,
 * key material and VCS internals are never legitimate tool output.
 */
object SensitivePaths {

    private val KEY_EXTENSIONS = setOf("pem", "p12", "pfx", "keystore", "jks", "key", "der", "cer", "crt")
    private val KEY_NAMES = setOf(
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        ".git-credentials", ".npmrc", ".pypirc", "local.properties",
        "credentials", "credentials.json", "service-account.json",
        "secrets.json", "secret.json",
    )

    /**
     * v4.10.5 follow-up: reads of extracted zip attachments. 4.10.5 extracts
     * attached zips into .anvex/attachments/<digest>-extracted/ so the agent
     * can review them — but [isSensitive] blocks every path under .anvex/,
     * so the agent's read tool (and Pi's mapped tools) refused the very
     * files extraction exists to expose. This carve-out covers ONLY that
     * subtree (text files from a user-supplied zip, bounded and
     * zip-slip-guarded at extraction). Secret files stay refused: callers
     * must still apply [isSecretFile] on top (see
     * [isReadableExtractedAttachment]).
     */
    fun isExtractedAttachment(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val idx = parts.indexOf("attachments")
        if (idx <= 0 || parts[idx - 1] != ".anvex") return false
        if (parts.size <= idx + 1) return false
        return parts[idx + 1].endsWith("-extracted")
    }

    /** True when a path is an extracted attachment AND not a secret file. */
    fun isReadableExtractedAttachment(path: String): Boolean =
        isExtractedAttachment(path) && !isSecretFile(path)

    /**
     * Like [commandReferencesSensitive], but extracted-attachment paths
     * that are not secret files do not count. Used by Pi's bash gate so
     * `cat` of an extracted review file is not hard-denied, while
     * exfiltration of a secret file inside an extracted tree still is.
     */
    fun commandReferencesSensitiveOutsideExtracted(command: String): Boolean {
        for (raw in command.split(Regex("\\s+"))) {
            val token = raw.trim('\'', '"').trim(',', ';', ':')
            if (token.isEmpty()) continue
            for (piece in token.split('=', '@')) {
                val p = piece.trim('\'', '"')
                if (p.isNotEmpty() && isSensitive(p) && !isReadableExtractedAttachment(p)) return true
            }
        }
        return false
    }

    /** Full check: metadata directories (.git/.anvex/.anvex-pi) AND secret files. */
    fun isSensitive(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty()) return false
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".git" || it == ".anvex" || it == ".anvex-pi" }) return true
        return isSecretFile(path)
    }

    /**
     * v4.10.2 (second review #7): the shell runtime and the terminal
     * session each had their own secret regex, and neither caught
     * `id_rsa`, `*.pem`/`*.jks`/`*.p12`, `.npmrc` or `.pypirc` — all of
     * which ARE in this list. Both now call this shared check instead
     * of their own regex, so the KDoc claim above is finally true.
     * Tokenizes a command line (whitespace, then `=`/`@` splits for
     * `--flag=value` and `curl -d @file` shapes) and reports whether
     * any token names a sensitive path. Deliberately name-based, like
     * [isSensitive]: a file merely called `token.ts` is ordinary code.
     */
    fun commandReferencesSensitive(command: String): Boolean {
        for (raw in command.split(Regex("\\s+"))) {
            val token = raw.trim('\'', '"').trim(',', ';', ':')
            if (token.isEmpty()) continue
            for (piece in token.split('=', '@')) {
                val p = piece.trim('\'', '"')
                if (p.isNotEmpty() && isSensitive(p)) return true
            }
        }
        return false
    }

    /**
     * Secret files only (no directory rule). The harness download route
     * uses this variant: downloading files under `.anvex/attachments` is
     * its documented purpose, but `.env`, keys and `.git` stay refused.
     */
    fun isSecretFile(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty()) return false
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".git" || it == ".anvex-pi" }) return true
        val name = parts.last().lowercase()
        if (name == ".env" || name.startsWith(".env.") || name.endsWith(".env")) return true
        if (name in KEY_NAMES) return true
        val ext = name.substringAfterLast('.', "")
        return ext in KEY_EXTENSIONS
    }
}
