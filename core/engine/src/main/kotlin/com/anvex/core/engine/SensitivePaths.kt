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
        ".netrc", ".dev.vars", ".htpasswd", "kubeconfig", "keystore.properties",
        "terraform.tfstate", "terraform.tfstate.backup", "terraform.tfvars",
        "secrets.yaml", "secrets.yml", "google-credentials.json",
    )

    /** Directories whose whole content is credential material. */
    private val SECRET_DIRS = setOf(".ssh", ".aws", ".kube", ".gnupg")

    /** Sample names used to decide whether a shell glob could hit a secret. */
    private val SECRET_SAMPLES = listOf(
        ".env", ".env.local", ".env.production", "id_rsa", "id_ed25519",
        ".npmrc", ".netrc", ".git-credentials", "credentials.json",
        "service-account.json", "secrets.json", "server.pem", "release.keystore",
        "release.jks", "cert.p12", "private.key", "local.properties",
        ".dev.vars", "terraform.tfstate", ".git",
    )

    /**
     * Quote/escape characters never change what a path IS to the shell
     * (`.e''nv` and `.e"n"v` both mean `.env`), so a lexical filter must
     * drop them before comparing — otherwise the filter is bypassable.
     */
    private fun canonicalToken(s: String): String =
        s.filterNot { it == '\'' || it == '"' || it == '\\' || it == '`' }

    /**
     * True when a token contains shell glob metacharacters AND the glob
     * could expand to a secret file name (`.e?v`, `.env*`, `*.pem`).
     * Mirrors shell rules: a leading wildcard never matches a dotfile.
     */
    private fun globCouldMatchSecret(token: String): Boolean {
        if (token.none { it == '*' || it == '?' || it == '[' }) return false
        val base = token.substringAfterLast('/')
        if (base.isEmpty()) return false
        val rx = StringBuilder()
        var i = 0
        while (i < base.length) {
            val c = base[i]
            when (c) {
                '*' -> rx.append(".*")
                '?' -> rx.append('.')
                '[' -> {
                    val end = base.indexOf(']', i + 1)
                    if (end < 0) rx.append("\\[") else { rx.append(base, i, end + 1); i = end }
                }
                else -> rx.append(Regex.escape(c.toString()))
            }
            i++
        }
        val regex = runCatching { Regex(rx.toString()) }.getOrNull() ?: return true
        val startsWithDot = base.startsWith(".")
        // A bare `*` / `?` names nothing in particular (and never matches dotfiles), so it is
        // not treated as a secret reference; dotfile globs (`.*`, `.e?v`) always are.
        if (!startsWithDot && base.count { it != '*' && it != '?' && it != '[' && it != ']' } < 2) return false
        return SECRET_SAMPLES.any { sample ->
            (startsWithDot || !sample.startsWith(".")) && regex.matches(sample)
        }
    }

    /** the .github/workflows subtree runs with repository secrets: writes need explicit approval. */
    fun isWorkflowPath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val i = parts.indexOf(".github")
        return i >= 0 && parts.getOrNull(i + 1) == "workflows"
    }

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
    /**
     * H-02: the shell reassembles quoted/escaped fragments before it
     * resolves a path (`.e''nv`, `.e"n"v`, `.e\nv` all become `.env`),
     * so matching must see the string the shell will execute — not the
     * raw token. Trimming quotes only at the edges missed quotes in
     * the middle, which made `cat .e''nv` read as harmless everywhere
     * this check is used (terminal, shell runtime, Pi bash gate).
     */
    private fun shellNormalized(piece: String): String =
        piece.replace("'", "").replace("\"", "").replace("\\", "")

    fun commandReferencesSensitiveOutsideExtracted(command: String): Boolean {
        for (raw in command.split(Regex("\\s+"))) {
            val token = canonicalToken(raw).trim(',', ';', ':')
            if (token.isEmpty()) continue
            for (piece in token.split('=', '@')) {
                val p = canonicalToken(piece)
                if (p.isNotEmpty() && isSensitive(p) && !isReadableExtractedAttachment(p)) return true
                if (p.isNotEmpty() && globCouldMatchSecret(p)) return true
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
            val token = canonicalToken(raw).trim(',', ';', ':')
            if (token.isEmpty()) continue
            for (piece in token.split('=', '@')) {
                val p = canonicalToken(piece)
                if (p.isNotEmpty() && isSensitive(p)) return true
                if (p.isNotEmpty() && globCouldMatchSecret(p)) return true
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
        if (parts.any { it == ".git" || it == ".anvex-pi" || it in SECRET_DIRS }) return true
        val name = parts.last().lowercase()
        if (name == ".env" || name.startsWith(".env.") || name.endsWith(".env")) return true
        if (name in KEY_NAMES) return true
        if (name.startsWith(".dev.vars.")) return true
        if (name.startsWith("service-account") && name.endsWith(".json")) return true
        val ext = name.substringAfterLast('.', "")
        return ext in KEY_EXTENSIONS
    }
}
