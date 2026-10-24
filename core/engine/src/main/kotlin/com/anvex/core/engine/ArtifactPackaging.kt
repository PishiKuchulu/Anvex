package com.anvex.core.engine

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Supported user-facing generated file formats. */
enum class ArtifactFormat { ZIP, PDF, TXT, MD, JSON, MP4 }

/** One central policy for generated source/document artifacts. */
object ArtifactHygienePolicy {
    private val BUILD_OUTPUT_DIRS = setOf(
        "outputs", "intermediates", "generated", "tmp", "kotlin", "classes", "reports",
        "test-results", "distributions", "libs", "bundle", "compose_metrics", "reports",
    )
    private val excludedNames = setOf(".env", ".env.local", ".env.production", "local.properties", "keystore.properties")
    private val secretName = Regex("(?i)(^|[._-])(secret|secrets|credential|credentials|token|password|passwd|private[-_]?key)([._-]|$)")
    private const val KEYS = "api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|secret[_-]?key|password|passwd|private[_-]?key"

    /** `key = "literal"` / `"key": "literal"` — a quoted literal, never a variable reference. */
    private val quotedSecret = Regex("(?i)([A-Za-z0-9_.-]*(?:$KEYS)[\"']?\\s*[:=]\\s*)([\"'])([^\"'\\r\\n]{8,})\\2")

    /** `KEY=value` in config-style files, where values are normally unquoted. */
    private val bareSecret = Regex("(?im)^(\\s*[A-Za-z0-9_.-]*(?:$KEYS)\\s*[:=]\\s*)([A-Za-z0-9_./+\\-]{12,})\\s*$")

    private val configExtensions = setOf("properties", "env", "ini", "conf", "yaml", "yml", "toml")
    private val placeholderMarks = listOf(
        "your", "xxx", "***", "...", "changeme", "example", "placeholder", "todo", "<", "\${", "$", "dummy",
        "redacted", "sample", "password", "secret", "token", "api_key", "apikey",
    )

    /** One suspected secret. Only the key text and line are reported, never the value. */
    data class SecretFinding(val line: Int, val key: String)

    private fun isPlaceholder(value: String): Boolean {
        val v = value.trim().lowercase()
        if (v.length < 8) return true
        if (v.toSet().size <= 2) return true // "........" / "aaaaaaaa"
        return placeholderMarks.any { v.contains(it) }
    }

    private fun scan(path: String, text: String, redact: Boolean): Pair<List<SecretFinding>, String> {
        val ext = path.substringAfterLast('.', "").lowercase()
        val found = ArrayList<SecretFinding>()
        var out = text
        // quoted literals work for every text type; bare values only for config-style files
        val patterns = buildList {
            add(quotedSecret)
            if (ext in configExtensions || path.substringAfterLast('/').startsWith(".env")) add(bareSecret)
        }
        for (rx in patterns) {
            val current = out
            val quoted = rx === quotedSecret
            out = rx.replace(current) { m ->
                val value = if (quoted) m.groupValues[3] else m.groupValues[2]
                if (isPlaceholder(value)) {
                    m.value
                } else {
                    val line = current.substring(0, m.range.first).count { it == '\n' } + 1
                    found += SecretFinding(line, m.groupValues[1].trim().trimEnd(':', '=', ' ', '"', '\''))
                    if (quoted) m.groupValues[1] + m.groupValues[2] + "REDACTED" + m.groupValues[2]
                    else m.groupValues[1] + "REDACTED"
                }
            }
        }
        return found to (if (redact) out else text)
    }

    fun include(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return false
        val parts = normalized.split('/')
        if (parts.any { it == ".git" || it == ".gradle" || it == "node_modules" || it == ".idea" || it == ".anvex" || it == ".anvex-pi" || it == "pFP" || it == "pfp" }) return false
        // Gradle/Node output directories only. A source package that happens to be called
        // "build" (e.g. core/android/src/main/kotlin/.../build/) must stay in the archive.
        val buildIndex = parts.indexOfFirst { it == "build" }
        if (buildIndex >= 0 && parts.take(buildIndex).none { it == "src" }) {
            val next = parts.getOrNull(buildIndex + 1)
            if (buildIndex == 0 || next in BUILD_OUTPUT_DIRS) return false
        }
        val name = parts.last()
        if (name in excludedNames || secretName.containsMatchIn(name)) return false
        // Private keys and keystores must never leave the device via sync or
        // artifact packaging — a .p12 is binary, so the secret-content scan
        // cannot see it; the extension is the only reliable signal.
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("p12", "pfx", "keystore", "jks", "key", "pem")) return false
        return true
    }

    fun looksLikeText(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf(
        "txt", "md", "json", "xml", "yaml", "yml", "properties", "gradle", "kts", "kt", "java",
        "js", "jsx", "ts", "tsx", "css", "scss", "html", "htm", "toml", "ini", "conf"
    )

    fun findSecrets(path: String, text: String): List<SecretFinding> = scan(path, text, redact = false).first

    fun containsLikelySecret(path: String, bytes: ByteArray): Boolean {
        if (!looksLikeText(path) || bytes.size > ArtifactPackager.MAX_ENTRY_BYTES) return false
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return false
        return findSecrets(path, text).isNotEmpty()
    }

    data class Redaction(val bytes: ByteArray, val findings: List<SecretFinding>)

    /**
     * Source ZIPs are delivered, not blocked: values that look like real secrets are replaced by
     * `REDACTED` and the caller reports file + line (never the value). Binary files pass through.
     */
    fun redactSecrets(path: String, bytes: ByteArray): Redaction {
        if (!looksLikeText(path) || bytes.size > ArtifactPackager.MAX_ENTRY_BYTES) return Redaction(bytes, emptyList())
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return Redaction(bytes, emptyList())
        val (found, cleaned) = scan(path, text, redact = true)
        return if (found.isEmpty()) Redaction(bytes, emptyList()) else Redaction(cleaned.toByteArray(StandardCharsets.UTF_8), found)
    }

    fun requireSafeGeneratedText(label: String, content: String) {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= 20L * 1024 * 1024) { "Generated document exceeds the 20 MB safety limit." }
        // Generated documents are arbitrary text, so quoted literals AND bare `KEY=value`
        // assignments are both treated as secrets here (unlike source files, where bare values are trusted).
        require(findSecrets("generated.env", content).isEmpty()) { "Artifact creation stopped: likely secret detected in generated content ($label)." }
    }
}

data class ArtifactFileSpec(val name: String, val bytes: ByteArray)

data class PackagedArtifact(val fileName: String, val mimeType: String, val bytes: ByteArray)

/** Pure-JVM packaging primitives. Storage/sharing belongs to :core:android. */
object ArtifactPackager {
    const val MAX_FILES = 20_000
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 20L * 1024 * 1024

    /** Streaming ZIP writer. It never accumulates the complete archive in RAM. */
    fun zipTo(fileName: String, files: Sequence<ArtifactFileSpec>, out: OutputStream): PackagedArtifactMeta {
        val seen = HashSet<String>()
        var count = 0
        var total = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            // Callers must provide deterministic ordering. Do not sort ArtifactFileSpec here:
            // sorting would materialize the entire sequence and defeat streaming memory bounds.
            files.forEach { item ->
                val safeItem = item.copy(name = safeArchivePath(item.name))
                require(seen.add(safeItem.name)) { "Duplicate archive paths are not allowed." }
                require(++count <= MAX_FILES) { "Too many files for one archive." }
                require(safeItem.bytes.size.toLong() <= MAX_ENTRY_BYTES) { "Archive entry exceeds the 20 MB safety limit." }
                total += safeItem.bytes.size
                require(total <= MAX_TOTAL_BYTES) { "Archive exceeds the 200 MB safety limit." }
                zip.putNextEntry(ZipEntry(safeItem.name).apply { time = 0L })
                zip.write(safeItem.bytes)
                zip.closeEntry()
            }
        }

        require(count > 0) { "Nothing to package." }
        return PackagedArtifactMeta(ensureExtension(fileName, "zip"), "application/zip", count, total)
    }

    /** Compatibility helper for small JVM callers/tests. */
    fun zip(fileName: String, files: List<ArtifactFileSpec>): PackagedArtifact {
        val out = java.io.ByteArrayOutputStream()
        val meta = zipTo(fileName, files.asSequence(), out)
        return PackagedArtifact(meta.fileName, meta.mimeType, out.toByteArray())
    }

    fun text(fileName: String, content: String, format: ArtifactFormat): PackagedArtifact {
        require(format == ArtifactFormat.TXT || format == ArtifactFormat.MD || format == ArtifactFormat.JSON)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_ENTRY_BYTES) { "Document exceeds the 20 MB safety limit." }
        val ext = when (format) { ArtifactFormat.TXT -> "txt"; ArtifactFormat.MD -> "md"; ArtifactFormat.JSON -> "json"; else -> error("Unsupported") }
        val mime = when (format) { ArtifactFormat.JSON -> "application/json"; ArtifactFormat.MD -> "text/markdown"; ArtifactFormat.TXT -> "text/plain"; else -> "application/octet-stream" }
        return PackagedArtifact(ensureExtension(fileName, ext), mime, bytes)
    }

    fun safeArchivePath(raw: String): String {
        val value = raw.trim().replace('\\', '/')
        require(value.isNotBlank()) { "Archive path cannot be blank." }
        require(!value.startsWith('/')) { "Absolute archive paths are not allowed." }
        val parts = value.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." }) { "Archive path traversal is not allowed." }
        val normalized = parts.joinToString("/")
        require(normalized.length <= 512) { "Archive path is too long." }
        return normalized
    }

    private fun ensureExtension(name: String, extension: String): String {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "download" }
        return if (clean.lowercase().endsWith(".$extension")) clean else "$clean.$extension"
    }
}

data class PackagedArtifactMeta(val fileName: String, val mimeType: String, val fileCount: Int, val sourceBytes: Long)
