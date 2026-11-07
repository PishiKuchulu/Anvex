package com.anvex.core.android.harness

import com.anvex.core.android.reliability.PluginPackageInstaller
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * v4.0 (GOAL 2): skill-store installation from a public GitHub repository.
 *
 * Flow: HTTPS github.com URL → codeload tar.gz → bounded TAR extraction (zip-slip
 * and size guarded) → validated by the existing [PluginPackageInstaller]
 * (manifest, id, no executable entrypoints). Two package shapes are accepted:
 *
 *  1. an Anvex plugin package (a `plugin.json` at the root or one level down) —
 *     the repository is re-packaged as a plugin ZIP as-is;
 *  2. agent-skill repositories in the ecosystem-standard `SKILL.md` shape — a
 *     root `SKILL.md`, or one per skill under `skills/<name>/SKILL.md` — where a
 *     plugin manifest is synthesized from each SKILL.md's frontmatter and the
 *     SKILL.md body becomes the package's declarative skill instructions.
 *
 * Private repositories are out of scope (documented honestly) because Anvex
 * does not accept OAuth flows for arbitrary GitHub accounts.
 *
 * v4.15.10 (device bug, 2026-11-07): the TAR reader now implements the ustar
 * block discipline it previously only half-followed. Every git-archive tarball
 * begins with a pax global header (type 'g', 52-byte payload); the old reader
 * drained non-file payloads without skipping their 512-byte block padding, so
 * every subsequent header was read 460 bytes out of alignment and extraction
 * died with `IOException: Invalid file path` on the first real-world tarball.
 * The reader now skips padding for EVERY entry type and honours GNU longname
 * ('L') and pax per-file `path` records ('x'). The entry budget rises from 256
 * to 1024 (a real skill collection — DietrichGebert/ponytail — has 273).
 */
object SkillGitInstaller {

    /**
     * v4.10.1 (SEC-05): installations record their provenance — the exact
     * commit the tarball was built from (GitHub names the tarball's root
     * directory `<repo>-<commit>`) and the archive's SHA-256 — so what was
     * installed is auditable after the fact, even though the source ref
     * itself is a moving HEAD.
     */
    data class Installed(
        val id: String,
        val version: String,
        val name: String,
        val commit: String? = null,
        val archiveSha256: String? = null,
    )

    /** A discovered SKILL.md skill: its directory (packaged wholesale) and parsed parts. */
    internal data class SkillSource(val dir: File, val markdown: File)

    internal data class SkillMeta(
        val name: String?,
        val description: String?,
        val version: String?,
        val body: String,
    )

    private const val MAX_TAR_BYTES: Long = 20L * 1024 * 1024
    private const val MAX_ENTRIES = 1024
    private const val MAX_SKILLS = 32
    private const val MAX_SKILL_INSTRUCTIONS = 32_000

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    suspend fun installFromGit(installer: PluginPackageInstaller, gitUrl: String): List<Installed> {
        val ownerRepo = parseOwnerRepo(gitUrl) ?: error("Unsupported repository URL: $gitUrl")
        val tarGz = downloadTarball(ownerRepo)
        val archiveSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(tarGz).joinToString("") { "%02x".format(it) }
        val staging = File(installerRoot(installer), ".git-skill-${System.nanoTime()}")
        staging.mkdirs()
        try {
            extractTarGz(tarGz, staging)
            // GitHub tarballs wrap everything in a single top-level <repo>-<sha>/ dir.
            val rootDir = staging.listFiles()?.singleOrNull()?.takeIf { it.isDirectory }
            val packageRoot = rootDir ?: staging
            val commit = rootDir?.name?.substringAfterLast('-')?.takeIf { it.matches(Regex("[0-9a-f]{7,40}")) }
            val manifestFile = findPluginManifest(packageRoot)
            val manifestParses = manifestFile?.let { file ->
                runCatching {
                    com.anvex.core.android.reliability.PluginRuntime().parseManifest(file.readText(Charsets.UTF_8))
                }.isSuccess
            } == true
            if (manifestFile != null && manifestParses) {
                val zip = repackageAsZip(packageRoot, File(staging, "package.zip"))
                val result = installer.installArchive(zip, expectedGitUrl = gitUrl)
                // Review B5: the display name comes from the package manifest
                // (Result.name) — the id/slug was shown here before.
                return listOf(Installed(result.id, result.version, result.name.ifBlank { result.id }, commit = commit, archiveSha256 = archiveSha256))
            }
            val skills = discoverSkills(packageRoot)
            require(skills.isNotEmpty()) {
                "Repository contains neither an Anvex plugin.json nor any SKILL.md skill."
            }
            val installed = mutableListOf<Installed>()
            val failures = mutableListOf<String>()
            for (skill in skills) {
                runCatching {
                    val zip = synthesizeSkillZip(skill, gitUrl, File(staging, "skill-${installed.size + failures.size}.zip"))
                    val result = installer.installArchive(zip, expectedGitUrl = gitUrl)
                    installed += Installed(result.id, result.version, result.name.ifBlank { result.id }, commit = commit, archiveSha256 = archiveSha256)
                }.onFailure { failures += "${skill.dir.name}: ${it.message}" }
            }
            if (installed.isEmpty()) {
                error("No skill in the repository could be installed. ${failures.firstOrNull() ?: ""}".trim())
            }
            return installed
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Locates a candidate Anvex manifest the way [PluginPackageInstaller] does
     * (root, or exactly one directory down). A candidate only counts when it
     * also PARSES as an Anvex manifest: skill-ecosystem repositories routinely
     * carry other tools' `plugin.json` files (e.g. `.claude-plugin/`), which
     * must fall through to SKILL.md discovery instead of failing the install.
     */
    internal fun findPluginManifest(packageRoot: File): File? {
        val root = File(packageRoot, "plugin.json")
        if (root.isFile) return root
        val candidates = packageRoot.listFiles()?.filter { it.isDirectory && File(it, "plugin.json").isFile } ?: emptyList()
        return if (candidates.size == 1) File(candidates.single(), "plugin.json") else null
    }

    /**
     * Finds SKILL.md skills in an extracted repository. A top-level `skills/`
     * directory is the ecosystem convention and wins outright (it also keeps
     * harness-specific duplicates such as `.openclaw/skills/` out); otherwise a
     * root SKILL.md; otherwise any SKILL.md outside dot-directories.
     */
    internal fun discoverSkills(packageRoot: File): List<SkillSource> {
        val skillsDir = File(packageRoot, "skills")
        val fromSkillsDir = if (skillsDir.isDirectory) {
            skillsDir.walkTopDown().filter { it.isFile && it.name == "SKILL.md" }.toList()
        } else {
            emptyList()
        }
        val candidates = when {
            fromSkillsDir.isNotEmpty() -> fromSkillsDir
            File(packageRoot, "SKILL.md").isFile -> listOf(File(packageRoot, "SKILL.md"))
            else -> packageRoot.walkTopDown().filter { f ->
                f.isFile && f.name == "SKILL.md" &&
                    f.relativeTo(packageRoot).path.split('/').none { it.startsWith(".") }
            }.toList()
        }
        // Sort by the skill DIRECTORY path, not the SKILL.md path: comparing
        // file paths puts "ponytail-review/SKILL.md" before "ponytail/SKILL.md"
        // because '-' (0x2D) < '/' (0x2F).
        return candidates.sortedBy { it.parentFile?.path ?: it.path }.take(MAX_SKILLS)
            .map { SkillSource(it.parentFile ?: packageRoot, it) }
    }

    /**
     * Minimal YAML-frontmatter reader for SKILL.md: flat `key: value` scalars
     * between the `---` fences (quotes stripped). The body is everything after
     * the closing fence; without a fence the whole file is the body.
     */
    internal fun parseSkillMeta(markdown: String): SkillMeta {
        val lines = markdown.lines()
        if (lines.firstOrNull()?.trim() != "---") return SkillMeta(null, null, null, markdown.trim())
        val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return SkillMeta(null, null, null, markdown.trim())
        var name: String? = null
        var description: String? = null
        var version: String? = null
        for (i in 1 until close) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))) {
                value = value.substring(1, value.length - 1)
            }
            when (key) {
                "name" -> name = value.ifBlank { null }
                "description" -> description = value.ifBlank { null }
                "version" -> version = value.ifBlank { null }
            }
        }
        val body = lines.drop(close + 1).joinToString("\n").trim()
        return SkillMeta(name, description, version, body)
    }

    internal fun sanitizeSkillId(raw: String): String {
        val cleaned = raw.lowercase().map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '-' }
            .joinToString("").trim('-', '.')
        val withHead = if (cleaned.isNotEmpty() && cleaned.first().isLetterOrDigit()) cleaned else "skill-$cleaned"
        return withHead.take(80)
    }

    /** Builds an installable plugin ZIP from one SKILL.md skill directory. */
    internal fun synthesizeSkillZip(skill: SkillSource, gitUrl: String, zipOut: File): File {
        val text = skill.markdown.readText(Charsets.UTF_8)
        val meta = parseSkillMeta(text)
        val id = sanitizeSkillId(meta.name ?: skill.dir.name)
        require(meta.body.length <= MAX_SKILL_INSTRUCTIONS) {
            "SKILL.md instructions for '$id' exceed the 32,000-character package limit."
        }
        val manifest = PluginPackageInstaller.PackageManifest(
            id = id,
            name = (meta.name ?: skill.dir.name).take(120),
            description = (meta.description ?: "").take(2_000),
            version = (meta.version ?: "1.0.0").take(40),
            gitUrl = gitUrl,
            skillInstructions = meta.body,
        )
        val manifestJson = json.encodeToString(manifest)
        java.util.zip.ZipOutputStream(zipOut.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("plugin.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            skill.dir.walkTopDown().filter { it.isFile }.forEach { file ->
                // The installer's per-entry cap is 2 MB; oversized assets are
                // left out rather than failing the whole skill.
                if (file.length() > 2L * 1024 * 1024) return@forEach
                val entry = java.util.zip.ZipEntry(file.relativeTo(skill.dir).path.replace(File.separatorChar, '/'))
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return zipOut
    }

    internal fun parseOwnerRepo(url: String): Pair<String, String>? {
        val cleaned = url.removeSuffix("/").removeSuffix(".git")
        // L-05: the old code searched for the substring "github.com/"
        // anywhere, so a URL like evil.com/?x=github.com/owner/repo
        // parsed as a GitHub repo. The host itself must be GitHub.
        val uri = runCatching { java.net.URI(cleaned) }.getOrNull() ?: return null
        if (uri.scheme != "https" || (uri.host != "github.com" && uri.host != "www.github.com")) return null
        // A query or fragment could reshape the constructed download URL;
        // the repo URL must be a plain path and nothing else.
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        // rawPath (not path): the decoded form would turn %2F into a real
        // separator and smuggle extra segments past the checks below.
        val parts = uri.rawPath.trim('/').split('/')
        if (parts.size < 2) return null
        // Owner/repo are interpolated into a URL path: accept only GitHub's own
        // character set so '?', '#', '%', '..' etc. cannot reshape the request.
        val name = Regex("[A-Za-z0-9_.-]{1,100}")
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        if (!name.matches(owner) || !name.matches(repo)) return null
        if (owner == "." || owner == ".." || repo == "." || repo == "..") return null
        return owner to repo
    }

    private fun downloadTarball(ownerRepo: Pair<String, String>): ByteArray {
        val (owner, repo) = ownerRepo
        // HEAD resolves the repository's real default branch (not only main/master);
        // the explicit branch names stay as fallbacks.
        val candidates = listOf(
            "https://codeload.github.com/$owner/$repo/tar.gz/HEAD",
            "https://codeload.github.com/$owner/$repo/tar.gz/refs/heads/main",
            "https://codeload.github.com/$owner/$repo/tar.gz/refs/heads/master",
        )
        for (url in candidates) {
            val response = http.newCall(Request.Builder().url(url).build()).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body ?: error("Empty tarball body")
                    val declared = body.contentLength()
                    if (declared > MAX_TAR_BYTES) error("Repository tarball exceeds the 20 MB budget.")
                    // Stream with a hard cap: never buffer an unbounded body in memory.
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TAR_BYTES) error("Repository tarball exceeds the 20 MB budget.")
                            out.write(buf, 0, n)
                        }
                    }
                    return out.toByteArray()
                }
            }
        }
        error("Could not download the repository tarball (network error, missing branch, or private repository).")
    }

    internal fun extractTarGz(tarGz: ByteArray, target: File) {
        GZIPInputStream(tarGz.inputStream()).use { gz ->
            extractTar(gz, target)
        }
    }

    /**
     * Minimal ustar TAR reader with traversal + budget guards.
     *
     * Block discipline (the 4.15.10 fix): every entry's payload occupies a
     * whole number of 512-byte blocks, so after consuming `size` payload bytes
     * the reader MUST skip `(512 - size % 512) % 512` padding bytes — for every
     * entry type, not just regular files. GNU longname ('L') and pax ('x')
     * records describe the NEXT entry and are consumed the same way.
     */
    private fun extractTar(input: InputStream, target: File) {
        val header = ByteArray(512)
        var entries = 0
        var totalBytes = 0L
        var zeroBlocks = 0
        var longName: String? = null
        var paxPath: String? = null
        val canonicalRoot = target.canonicalFile
        val scratch = ByteArray(32 * 1024)
        while (true) {
            if (readFully(input, header) < 512) break
            val name = header.copyOfRange(0, 100).toString(Charsets.UTF_8).trimEnd('\u0000')
            if (name.isEmpty()) {
                // Two consecutive zero blocks mark the end of a tar archive. Stopping here
                // also prevents a gzip "zero bomb" from keeping this loop busy.
                if (++zeroBlocks >= 2) break
                continue
            }
            zeroBlocks = 0
            val size = tarSize(header)
            if (++entries > MAX_ENTRIES) error("Tarball has too many entries.")
            totalBytes += size
            if (totalBytes > MAX_TAR_BYTES) error("Tarball expands beyond the size budget.")
            val type = header[156]
            when (type) {
                'L'.code.toByte() -> {
                    longName = readPayload(input, size, scratch).toString(Charsets.UTF_8).trimEnd('\u0000')
                }
                'x'.code.toByte() -> {
                    val records = readPayload(input, size, scratch).toString(Charsets.UTF_8)
                    paxPath = parsePaxPath(records) ?: paxPath
                }
                'g'.code.toByte(), 'K'.code.toByte() -> {
                    // Global pax header / GNU long link name: metadata only.
                    skipFully(input, size, scratch)
                    skipPadding(input, size, scratch)
                }
                '5'.code.toByte() -> {
                    val fullName = effectiveName(header, name, longName, paxPath)
                    if (fullName.isNotBlank()) {
                        val dir = File(canonicalRoot, fullName).canonicalFile
                        if (dir.path.startsWith(canonicalRoot.path + File.separator)) dir.mkdirs()
                    }
                    skipFully(input, size, scratch)
                    skipPadding(input, size, scratch)
                    longName = null
                    paxPath = null
                }
                '0'.code.toByte(), 0.toByte(), '7'.code.toByte() -> {
                    // regular file
                    val fullName = effectiveName(header, name, longName, paxPath)
                    val safe = fullName.split('/').none { it == ".." }
                    if (!safe || fullName.isBlank()) error("Blocked tarball path: $fullName")
                    val out = File(canonicalRoot, fullName).canonicalFile
                    if (!out.path.startsWith(canonicalRoot.path + File.separator)) error("Blocked tarball path: $fullName")
                    out.parentFile?.mkdirs()
                    val data = readPayload(input, size, scratch)
                    out.writeBytes(data)
                    longName = null
                    paxPath = null
                }
                else -> {
                    // symlinks/devices/fifos: skipped; payload drained WITH padding.
                    skipFully(input, size, scratch)
                    skipPadding(input, size, scratch)
                    longName = null
                    paxPath = null
                }
            }
        }
    }

    /** ustar name resolution: pax `path` wins, then GNU longname, then prefix + name. */
    private fun effectiveName(header: ByteArray, name: String, longName: String?, paxPath: String?): String {
        if (paxPath != null) return paxPath
        if (longName != null) return longName
        val prefixField = header.copyOfRange(345, 500).toString(Charsets.UTF_8).trimEnd('\u0000')
        return if (prefixField.isNotBlank()) "$prefixField/$name" else name
    }

    /** Extracts the `path` record from a pax extended header payload ("<len> path=<value>\n" records). */
    private fun parsePaxPath(records: String): String? {
        var i = 0
        while (i < records.length) {
            val space = records.indexOf(' ', i)
            if (space < 0) return null
            val len = records.substring(i, space).toIntOrNull() ?: return null
            if (len <= 0 || i + len > records.length) return null
            val record = records.substring(space + 1, i + len).trimEnd('\n')
            if (record.startsWith("path=")) return record.substring(5)
            i += len
        }
        return null
    }

    /** Octal size, or base-256 binary when the high bit of the first size byte is set. */
    private fun tarSize(header: ByteArray): Long {
        val first = header[124].toInt() and 0xFF
        if (first and 0x80 == 0) return octal(header.copyOfRange(124, 136))
        var value = (first and 0x7F).toLong()
        for (i in 125 until 136) value = (value shl 8) or (header[i].toInt() and 0xFF).toLong()
        return value
    }

    /** Reads exactly `size` payload bytes (bounded by the archive budget), then skips block padding. */
    private fun readPayload(input: InputStream, size: Long, scratch: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var remaining = size
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) error("Truncated tarball.")
            out.write(scratch, 0, n)
            remaining -= n
        }
        skipPadding(input, size, scratch)
        return out.toByteArray()
    }

    private fun skipFully(input: InputStream, size: Long, scratch: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) error("Truncated tarball.")
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long, scratch: ByteArray) {
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) skipFully(input, padding, scratch)
    }

    private fun repackageAsZip(dir: File, zipOut: File): File {
        java.util.zip.ZipOutputStream(zipOut.outputStream().buffered()).use { zip ->
            dir.walkTopDown().filter { it.isFile && it != zipOut }.forEach { file ->
                val entry = java.util.zip.ZipEntry(file.relativeTo(dir).path.replace(File.separatorChar, '/'))
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return zipOut
    }

    private fun octal(bytes: ByteArray): Long =
        bytes.toString(Charsets.US_ASCII).trimEnd('\u0000', ' ').trim().toLongOrNull(radix = 8) ?: 0L

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun installerRoot(installer: PluginPackageInstaller): File =
        // The installer's package root is filesDir/plugins — derive it from context.
        File(com.anvex.core.android.AppGraph.get().context.filesDir, "plugins")
}
