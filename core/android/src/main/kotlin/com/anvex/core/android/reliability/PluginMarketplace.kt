package com.anvex.core.android.reliability

import com.anvex.core.android.db.PluginDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Trusted, metadata-first marketplace facade backed by the safe declarative package installer. */
class PluginMarketplace(
    private val dao: PluginDao,
    private val installer: PluginPackageInstaller,
) {
    @Serializable
    data class CatalogEntry(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val gitUrl: String,
        val dependencies: List<String> = emptyList(),
    )
    @Serializable data class InstallResult(val id: String, val version: String, val dependencies: List<String>, val sha256: String)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun validateGitUrl(url: String): String {
        val u = URI(url.trim())
        require(u.scheme == "https") { "Plugin Git URL must use HTTPS." }
        require(u.userInfo == null && u.rawQuery == null && u.rawFragment == null) { "Git URL must not contain credentials, query or fragment." }
        require(u.host?.lowercase() in setOf("github.com", "gitlab.com")) { "Only GitHub and GitLab are trusted plugin origins." }
        require(u.path.trim('/').split('/').size == 2) { "Plugin URL must point to a repository." }
        return u.toString().take(500)
    }

    /** Downloads the repository archive, validates plugin.json, extracts it safely, and records its hash. */
    suspend fun installFromGitUrl(url: String, expectedSha256: String? = null): InstallResult {
        val safeUrl = validateGitUrl(url)
        val bytes = fetchArchive(safeUrl)
        val tmp = File.createTempFile("plugin-", ".zip", installerTempDir())
        try {
            tmp.writeBytes(bytes)
            val result = installer.installArchive(tmp, expectedSha256, safeUrl)
            val plugin = dao.get(result.id)
            require(plugin?.source == safeUrl) { "Installed plugin source mismatch." }
            return InstallResult(result.id, result.version, json.decodeFromString<List<String>>(plugin.dependenciesJson), result.sha256)
        } finally { tmp.delete() }
    }

    suspend fun enableWithDependencies(id: String): List<String> {
        val plan = dependencyPlan(id)
        plan.forEach { dao.setEnabled(it.id, true) }
        return plan.map { it.id }
    }

    suspend fun disableWithDependents(id: String): List<String> {
        val all = dao.all()
        val dependents = all.filter { plugin -> parseDeps(plugin.dependenciesJson).any { dependencyId(it) == id } }
        require(dependents.none { it.enabled && it.builtIn }) { "A built-in plugin depends on this plugin." }
        dependents.filter { !it.builtIn }.forEach { dao.setEnabled(it.id, false) }
        dao.setEnabled(id, false)
        return (listOf(id) + dependents.map { it.id }).distinct()
    }

    suspend fun dependencyPlan(id: String): List<com.anvex.core.android.db.PluginEntity> {
        val all = dao.all().associateBy { it.id }
        val result = LinkedHashMap<String, com.anvex.core.android.db.PluginEntity>()
        fun visit(current: String, stack: Set<String>) {
            if (current in stack) error("Plugin dependency cycle detected: $current")
            val plugin = all[current] ?: error("Missing plugin dependency: $current")
            if (result.containsKey(current)) return
            parseDeps(plugin.dependenciesJson).forEach { spec ->
                val depId = dependencyId(spec)
                val dep = all[depId] ?: error("Missing plugin dependency: $depId")
                require(versionSatisfies(dep.version, dependencyConstraint(spec))) { "Dependency $depId does not satisfy '$spec'." }
                visit(depId, stack + current)
            }
            result[current] = plugin
        }
        visit(id, emptySet())
        return result.values.toList()
    }

    private fun parseDeps(value: String): List<String> = runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
    private fun dependencyId(spec: String) = spec.trim().split(Regex("\\s*(?:>=|<=|>|<|=)"), limit = 2).first()
    private fun dependencyConstraint(spec: String) = spec.trim().removePrefix(dependencyId(spec)).trim()
    private fun versionSatisfies(version: String, constraint: String): Boolean {
        if (constraint.isBlank()) return true
        val a = version.split('.').mapNotNull { it.toIntOrNull() }.let { it + List(3 - it.size.coerceAtMost(3)) { 0 } }.take(3)
        val b = constraint.removePrefix(">=").removePrefix("<=").removePrefix(">").removePrefix("<").removePrefix("=").trim().split('.').mapNotNull { it.toIntOrNull() }.let { it + List(3 - it.size.coerceAtMost(3)) { 0 } }.take(3)
        val cmp = a.zip(b).firstOrNull { it.first != it.second }?.let { if (it.first > it.second) 1 else -1 } ?: 0
        return when { constraint.startsWith(">=") -> cmp >= 0; constraint.startsWith("<=") -> cmp <= 0; constraint.startsWith(">") -> cmp > 0; constraint.startsWith("<") -> cmp < 0; else -> cmp == 0 }
    }
    private fun fetchArchive(url: String): ByteArray {
        val u = URI(url); val host = u.host.lowercase(); val path = u.path.trimEnd('/')
        val archiveUrl = if (host == "github.com") "https://github.com$path/archive/refs/heads/main.zip" else "https://gitlab.com$path/-/archive/main/${path.substringAfterLast('/')}-main.zip"
        val c = URI(archiveUrl).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
        require(c.responseCode in 200..299) { "Plugin package download failed: HTTP ${c.responseCode}" }
        require(c.contentLengthLong in 1..20L * 1024 * 1024) { "Plugin package is too large." }
        return c.inputStream.use { it.readBytes().also { require(it.size <= 20 * 1024 * 1024) } }
    }
    private fun installerTempDir(): File = File(System.getProperty("java.io.tmpdir") ?: ".").apply { mkdirs() }
}
