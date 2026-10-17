package com.anvex.core.android.reliability

import com.anvex.core.android.db.PluginDao
import com.anvex.core.android.db.PluginEntity
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Secure declarative plugin package installer. A package may contain manifests,
 * assets and declarative skill plans, but no executable entrypoint is loaded or
 * invoked by Anvex. Extraction is bounded and confined to app-private storage.
 */
class PluginPackageInstaller(
    private val root: File,
    private val dao: PluginDao,
    private val runtime: PluginRuntime = PluginRuntime(),
) {
    @Serializable
    data class PackageManifest(
        val id: String,
        val name: String,
        val description: String = "",
        val version: String,
        val gitUrl: String? = null,
        val dependencies: List<String> = emptyList(),
        val entrypoint: String? = null,
        val capabilities: List<String> = emptyList(),
        val skillInstructions: String? = null,
    )
    data class Result(val id: String, val version: String, val sha256: String, val packageDir: File)

    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
    private val maxArchiveBytes = 20L * 1024 * 1024
    private val maxEntries = 256
    private val maxEntryBytes = 2L * 1024 * 1024

    suspend fun installArchive(archive: File, expectedSha256: String? = null, expectedGitUrl: String? = null): Result {
        require(archive.isFile) { "Plugin package does not exist." }
        require(archive.length() in 1..maxArchiveBytes) { "Plugin package is outside the allowed size." }
        val sha = sha256(archive)
        if (expectedSha256 != null) {
            require(expectedSha256.matches(Regex("[A-Fa-f0-9]{64}"))) { "Invalid SHA-256." }
            require(sha.equals(expectedSha256, true)) { "Plugin package checksum mismatch." }
        }
        val staging = File(root, ".staging-${System.nanoTime()}").apply { mkdirs() }
        try {
            var total = 0L
            var count = 0
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    count++; require(count <= maxEntries) { "Plugin package has too many entries." }
                    require(!e.isDirectory) { "Directory entries are not required; package must contain files only." }
                    val name = e.name.replace('\\', '/')
                    require(name.isNotBlank() && !name.startsWith('/') && !name.contains("../") && !name.contains("/..")) { "Unsafe plugin package path." }
                    require(!name.contains(':')) { "Absolute drive paths are not allowed." }
                    val out = File(staging, name).canonicalFile
                    require(out.path.startsWith(staging.canonicalPath + File.separator)) { "Plugin path escapes package root." }
                    out.parentFile?.mkdirs()
                    val data = zip.readBounded(maxEntryBytes)
                    total += data.size
                    require(total <= maxArchiveBytes) { "Plugin expanded size is too large." }
                    out.writeBytes(data)
                }
            }
            val manifestFile = File(staging, "plugin.json")
            val rootDir = if (manifestFile.isFile) staging else {
                val candidates = staging.walkTopDown().filter { it.isFile && it.name == "plugin.json" && it.parentFile?.parentFile == staging }.take(2).toList()
                require(candidates.size == 1) { "plugin.json is missing or ambiguous." }
                candidates.single().parentFile
            }
            val actualManifestFile = File(rootDir, "plugin.json")
            val manifest = json.decodeFromString<PackageManifest>(actualManifestFile.readText(Charsets.UTF_8))
            require(manifest.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}"))) { "Invalid plugin id." }
            require(manifest.name.length in 1..120 && manifest.version.length in 1..40) { "Invalid plugin metadata." }
            require(manifest.description.length <= 2_000)
            require(manifest.dependencies.size <= 32 && manifest.dependencies.all { dependencyValid(it) }) { "Invalid plugin dependencies." }
            require(manifest.entrypoint == null) { "Executable plugin entrypoints are not supported." }
            val pluginManifest = runtime.parseManifest(actualManifestFile.readText(Charsets.UTF_8))
            require(pluginManifest.id == manifest.id && pluginManifest.version == manifest.version)
            manifest.gitUrl?.let { validateGitUrl(it) }
            if (expectedGitUrl != null) require(manifest.gitUrl == expectedGitUrl) { "Plugin manifest source mismatch." }
            manifest.skillInstructions?.let { require(it.length <= 32_000) }
            val destination = File(root, "${manifest.id}/${manifest.version}").canonicalFile
            require(destination.path.startsWith(root.canonicalFile.path + File.separator))
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.deleteRecursively()
            require(rootDir.renameTo(destination)) { "Could not commit plugin package." }
            dao.upsert(PluginEntity(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                version = manifest.version,
                source = manifest.gitUrl ?: "PACKAGE",
                enabled = false,
                builtIn = false,
                dependenciesJson = json.encodeToString(manifest.dependencies),
                packagePath = destination.absolutePath,
                integritySha256 = sha,
                skillInstructions = manifest.skillInstructions,
                skillCapabilitiesJson = json.encodeToString(manifest.capabilities),
            ))
            return Result(manifest.id, manifest.version, sha, destination)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun dependencyValid(value: String): Boolean = value.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}(?:\\s*(?:>=|<=|>|<|=)\\s*\\d+(?:\\.\\d+){0,2})?"))
    private fun validateGitUrl(url: String) {
        val u = java.net.URI(url.trim())
        require(u.scheme == "https" && u.userInfo == null && u.rawQuery == null && u.rawFragment == null && u.host != null)
    }
    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(8192)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun ZipInputStream.readBounded(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0L
        while (true) { val n = read(buf); if (n < 0) break; total += n; require(total <= max) { "Plugin entry is too large." }; out.write(buf, 0, n) }
        return out.toByteArray()
    }
}
