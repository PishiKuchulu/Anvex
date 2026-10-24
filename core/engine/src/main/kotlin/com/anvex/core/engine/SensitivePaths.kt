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
