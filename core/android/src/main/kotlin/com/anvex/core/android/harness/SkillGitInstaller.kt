package com.anvex.core.android.harness

import com.anvex.core.android.reliability.PluginPackageInstaller
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
 * and size guarded) → re-packaged as a plugin ZIP in cache → validated by the
 * existing [PluginPackageInstaller] (manifest, id, no executable entrypoints).
 * Private repositories are out of scope (documented honestly) because Anvex
 * does not accept OAuth flows for arbitrary GitHub accounts.
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

    private const val MAX_TAR_BYTES: Long = 20L * 1024 * 1024
    private const val MAX_ENTRIES = 256

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    suspend fun installFromGit(installer: PluginPackageInstaller, gitUrl: String): Installed {
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
            val zip = repackageAsZip(packageRoot, File(staging, "package.zip"))
            val result = installer.installArchive(zip, expectedGitUrl = gitUrl)
            return Installed(result.id, result.version, result.id, commit = commit, archiveSha256 = archiveSha256)
        } finally {
            staging.deleteRecursively()
        }
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

    private fun extractTarGz(tarGz: ByteArray, target: File) {
        GZIPInputStream(tarGz.inputStream()).use { gz ->
            extractTar(gz, target)
        }
    }

    /** Minimal ustar TAR reader with traversal + budget guards. */
    private fun extractTar(input: InputStream, target: File) {
        val header = ByteArray(512)
        var entries = 0
        var totalBytes = 0L
        var zeroBlocks = 0
        val canonicalRoot = target.canonicalFile
        while (true) {
            if (readFully(input, header) < 512) break
            val name = header.copyOfRange(0, 100).toString(Charsets.US_ASCII).trimEnd('\u0000')
            if (name.isEmpty()) {
                // Two consecutive zero blocks mark the end of a tar archive. Stopping here
                // also prevents a gzip "zero bomb" from keeping this loop busy.
                if (++zeroBlocks >= 2) break
                continue
            }
            zeroBlocks = 0
            val size = octal(header.copyOfRange(124, 136))
            if (++entries > MAX_ENTRIES) error("Tarball has too many entries.")
            totalBytes += size
            if (totalBytes > MAX_TAR_BYTES) error("Tarball expands beyond the size budget.")
            val type = header[156]
            val prefixField = header.copyOfRange(345, 500).toString(Charsets.US_ASCII).trimEnd('\u0000')
            val fullName = if (prefixField.isNotBlank()) "$prefixField/$name" else name
            val safe = fullName.split('/').none { it == ".." }
            if (!safe || fullName.isBlank()) error("Blocked tarball path: $fullName")
            when (type) {
                '0'.code.toByte(), 0.toByte() -> {
                    // regular file
                    val out = File(canonicalRoot, fullName).canonicalFile
                    if (!out.path.startsWith(canonicalRoot.path + File.separator)) error("Blocked tarball path: $fullName")
                    out.parentFile?.mkdirs()
                    val data = ByteArray(size.toInt())
                    if (readFully(input, data) < size) error("Truncated tarball.")
                    out.writeBytes(data)
                    val padding = ((512 - (size % 512)) % 512).toInt()
                    if (padding > 0) {
                        val pad = ByteArray(padding)
                        readFully(input, pad)
                    }
                }
                '5'.code.toByte() -> File(canonicalRoot, fullName).mkdirs()
                else -> { // symlinks/devices skipped; their payload bytes (if any) are drained
                    if (size > 0) {
                        var remaining = size
                        val drain = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = input.read(drain, 0, minOf(drain.size.toLong(), remaining).toInt())
                            if (n < 0) error("Truncated tarball.")
                            remaining -= n
                        }
                    }
                }
            }
        }
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
