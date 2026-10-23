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
    )

    /** Full check: metadata directories (.git/.anvex) AND secret files. */
    fun isSensitive(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty()) return false
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".git" || it == ".anvex" }) return true
        return isSecretFile(path)
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
        if (parts.any { it == ".git" }) return true
        val name = parts.last().lowercase()
        if (name == ".env" || name.startsWith(".env.") || name.endsWith(".env")) return true
        if (name in KEY_NAMES) return true
        val ext = name.substringAfterLast('.', "")
        return ext in KEY_EXTENSIONS
    }
}
