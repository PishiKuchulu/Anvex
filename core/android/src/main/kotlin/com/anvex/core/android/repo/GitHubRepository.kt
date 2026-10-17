package com.anvex.core.android.repo

import android.util.Base64
import com.anvex.core.android.KeystoreCrypto
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ArtifactHygienePolicy
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Bounded GitHub Contents API integration; no embedded git binary is assumed. */
class GitHubRepository(private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences("github_sync", android.content.Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val jsonType = "application/json".toMediaType()
    private val tokenKey = "token_enc"

    fun hasToken(): Boolean = prefs.getString(tokenKey, null)?.isNotBlank() == true
    fun saveToken(token: String) {
        val clean = token.trim()
        require(clean.length in 20..512) { "Invalid GitHub token length." }
        prefs.edit().putString(tokenKey, KeystoreCrypto.encrypt(clean)).apply()
    }
    fun clearToken() { prefs.edit().remove(tokenKey).apply() }
    private fun token(): String = prefs.getString(tokenKey, null)?.let { runCatching { KeystoreCrypto.decrypt(it) }.getOrDefault("") } ?: ""

    /**
     * v4.x cloud build: read-only access to the saved token so features that
     * share this store (e.g. CloudBuildRepository) never duplicate the
     * Keystore decryption. The token is never logged or shown in the UI.
     */
    fun savedToken(): String? = token().takeIf { it.isNotBlank() }

    suspend fun push(fs: ProjectFileSystem, owner: String, repo: String, branch: String = "main", message: String = "Sync from Anvex"): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            validateRepo(owner, repo, branch, message)
            val auth = token().ifBlank { error("Add a GitHub token before syncing.") }
            val files = fs.walk()
                .filter { ArtifactHygienePolicy.include(it) }
                .filter { !it.startsWith(".anvex/") }
                .take(MAX_FILES + 1)
            if (files.size > MAX_FILES) error("Project exceeds the GitHub sync file limit.")
            var pushed = 0
            for (path in files) {
                val bytes = fs.readFileBytes(path) ?: continue
                require(bytes.size <= MAX_FILE_BYTES) { "File '$path' exceeds the GitHub sync limit." }
                require(!ArtifactHygienePolicy.containsLikelySecret(path, bytes)) {
                    error("GitHub sync stopped: a likely secret was detected in $path. Remove the secret and retry.")
                }
                val url = contentsUrl(owner, repo, path)
                // A brand-new file has no blob yet: GitHub answers 404, which must mean "create", not "fail".
                val sha = existingSha(url, auth)
                val body = JSONObject().put("message", message.take(200)).put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("branch", branch)
                if (sha != null) body.put("sha", sha)
                requestJson(url, auth, "PUT", body)
                pushed++
            }
            pushed
        }
    }

    /** Pulls a repository tree into the selected workspace with strict path/size limits. */
    suspend fun pull(fs: ProjectFileSystem, owner: String, repo: String, branch: String = "main"): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            validateRepo(owner, repo, branch, "pull")
            val auth = token().ifBlank { error("Add a GitHub token before syncing.") }
            val root = "https://api.github.com/repos/$owner/$repo/git/trees/${java.net.URLEncoder.encode(branch, Charsets.UTF_8.name())}?recursive=1"
            val tree = requestJson(root, auth, "GET")
            require(!tree.optBoolean("truncated", false)) { "GitHub tree is too large; refusing partial pull." }
            val items = tree.optJSONArray("tree") ?: JSONArray()
            require(items.length() <= MAX_FILES) { "Repository exceeds the file limit." }
            var written = 0
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") != "blob") continue
                val path = PathSanitizer.sanitizeOrNull(item.optString("path")) ?: error("Unsafe repository path rejected.")
                val size = item.optLong("size", 0L)
                require(size in 0..MAX_FILE_BYTES) { "Remote file '$path' exceeds the sync limit." }
                val blobUrl = item.optString("url")
                require(blobUrl.startsWith("https://api.github.com/repos/")) { "Unexpected GitHub blob URL." }
                val blob = requestJson(blobUrl, auth, "GET")
                val content = blob.optString("content").replace("\n", "")
                val bytes = Base64.decode(content, Base64.DEFAULT)
                require(bytes.size <= MAX_FILE_BYTES) { "Remote file '$path' exceeds the sync limit." }
                require(ArtifactHygienePolicy.include(path) && !path.startsWith(".anvex/")) { "Remote path '$path' is excluded from workspace sync for security." }
                require(!ArtifactHygienePolicy.containsLikelySecret(path, bytes)) {
                    error("GitHub pull stopped: a likely secret was detected in $path.")
                }
                PathSanitizer.parent(path)?.let { if (it.isNotBlank()) fs.mkdirs(it) }
                fs.writeFileBytes(path, bytes)
                written++
            }
            written
        }
    }

    private fun requestJson(url: String, token: String, method: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> error("Unsupported GitHub method")
        }
        return http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GitHub request failed (HTTP ${response.code}).")
            JSONObject(text)
        }
    }

    private fun existingSha(url: String, token: String): String? {
        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 404 -> null
                !response.isSuccessful -> error("GitHub request failed (HTTP ${response.code}).")
                else -> runCatching { JSONObject(text).optString("sha") }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun validateRepo(owner: String, repo: String, branch: String, message: String) {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid GitHub owner." }
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid repository name." }
        require(branch.matches(Regex("[A-Za-z0-9._/-]{1,200}")) && !branch.contains("..") && !branch.startsWith("/") && !branch.endsWith("/")) { "Invalid branch." }
        require(message.isNotBlank() && message.length <= 200) { "Invalid commit message." }
    }
    private fun contentsUrl(owner: String, repo: String, path: String) = "https://api.github.com/repos/$owner/$repo/contents/${path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }}"

    companion object {
        const val MAX_FILES = 2_000
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}
