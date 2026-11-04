package com.anvex.core.android.repo

import android.util.Base64
import com.anvex.core.android.KeystoreCrypto
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ArtifactHygienePolicy
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        // Audit 04 (Low): bind the ciphertext to its purpose with AAD
        // ("v2:" marker), like provider keys — a bare Keystore ciphertext
        // could otherwise be replayed into any other encrypted field.
        prefs.edit().putString(tokenKey, TOKEN_V2_PREFIX + KeystoreCrypto.encrypt(clean, TOKEN_AAD)).apply()
    }
    fun clearToken() { prefs.edit().remove(tokenKey).apply() }

    private fun decryptToken(stored: String): String = runCatching {
        if (stored.startsWith(TOKEN_V2_PREFIX)) {
            KeystoreCrypto.decrypt(stored.removePrefix(TOKEN_V2_PREFIX), TOKEN_AAD)
        } else {
            // Legacy unversioned value: AAD attempt, then the plain form.
            runCatching { KeystoreCrypto.decrypt(stored, TOKEN_AAD) }
                .getOrElse { KeystoreCrypto.decrypt(stored) }
        }
    }.getOrDefault("")

    private fun token(): String {
        val stored = prefs.getString(tokenKey, null) ?: return ""
        val plain = decryptToken(stored)
        // Opportunistic upgrade: a legacy value that still decrypts is
        // re-stored in the AAD-bound form, so the unbound window closes
        // on first use instead of waiting for a manual re-entry.
        if (plain.isNotBlank() && !stored.startsWith(TOKEN_V2_PREFIX)) {
            runCatching { saveToken(plain) }
        }
        return plain
    }

    // -- GitHub Device Authorization Flow (OAuth for apps without a backend) --
    // No client secret is needed; the user creates a GitHub OAuth App once and
    // pastes its (public) client ID below. The 8-char user code is authorized at
    // github.com/login/device, then we poll for the access token.

    private val oauthClientIdKey = "oauth_client_id"
    fun oauthClientId(): String = prefs.getString(oauthClientIdKey, "").orEmpty()
    fun saveOauthClientId(id: String) { prefs.edit().putString(oauthClientIdKey, id.trim()).apply() }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSec: Int,
        val intervalSec: Int,
    )

    sealed interface DevicePoll {
        data object Pending : DevicePoll
        data object SlowDown : DevicePoll
        data class Granted(val accessToken: String) : DevicePoll
        data class Failed(val message: String) : DevicePoll
    }

    suspend fun requestDeviceCode(clientId: String): Result<DeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = clientId.trim()
            require(clean.matches(Regex("[A-Za-z0-9]{5,64}"))) { "Invalid OAuth client ID." }
            val body = "client_id=${java.net.URLEncoder.encode(clean, Charsets.UTF_8.name())}&scope=repo"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url("https://github.com/login/device/code")
                .header("Accept", "application/json")
                .post(body)
                .build()
            http.newCall(req).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub device flow failed (HTTP ${response.code}).")
                val json = JSONObject(text)
                json.optString("error").takeIf { it.isNotBlank() }?.let {
                    error("GitHub: $it — ${json.optString("error_description")}")
                }
                DeviceCode(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
                    expiresInSec = json.optInt("expires_in", 900),
                    intervalSec = json.optInt("interval", 5).coerceAtLeast(5),
                )
            }
        }
    }

    suspend fun pollDeviceToken(clientId: String, deviceCode: DeviceCode): DevicePoll = withContext(Dispatchers.IO) {
        runCatching {
            val form = "client_id=${java.net.URLEncoder.encode(clientId.trim(), Charsets.UTF_8.name())}" +
                "&device_code=${java.net.URLEncoder.encode(deviceCode.deviceCode, Charsets.UTF_8.name())}" +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            val req = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(req).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@runCatching DevicePoll.Failed("GitHub device flow failed (HTTP ${response.code}).")
                val json = JSONObject(text)
                json.optString("access_token").takeIf { it.isNotBlank() }?.let {
                    return@runCatching DevicePoll.Granted(it)
                }
                when (json.optString("error")) {
                    "authorization_pending" -> DevicePoll.Pending
                    "slow_down" -> DevicePoll.SlowDown
                    "expired_token" -> DevicePoll.Failed("The code expired. Start again to get a new one.")
                    "access_denied" -> DevicePoll.Failed("Authorization was denied on GitHub.")
                    else -> DevicePoll.Failed(json.optString("error_description", "GitHub authorization failed."))
                }
            }
        }.getOrElse { DevicePoll.Failed(it.message ?: "Network error.") }
    }

    /**
     * v4.x cloud build: read-only access to the saved token so features that
     * share this store (e.g. CloudBuildRepository) never duplicate the
     * Keystore decryption. The token is never logged or shown in the UI.
     */
    fun savedToken(): String? = token().takeIf { it.isNotBlank() }

    /** v4.10.1 (BUG-10): sync results report what was SKIPPED, not just what moved. */
    data class SyncOutcome(val count: Int, val skipped: List<String> = emptyList())

    /**
     * v4.10.1 (SEC-14): a stored token that no longer decrypts (device key
     * changed) must not masquerade as "no token" — callers fail with the
     * real reason instead of the misleading "Add a GitHub token".
     */
    private fun authToken(): String {
        val raw = prefs.getString(tokenKey, null)
        val decrypted = token()
        if (decrypted.isBlank() && !raw.isNullOrBlank()) {
            error("The stored GitHub token could not be decrypted on this device. Re-enter your token in GitHub settings.")
        }
        return decrypted.ifBlank { error("Add a GitHub token before syncing.") }
    }

    suspend fun push(fs: ProjectFileSystem, owner: String, repo: String, branch: String = "main", message: String = "Sync from Anvex"): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            validateRepo(owner, repo, branch, message)
            // 2026-11-04 (audit wave): never push a tree a crashed pull
            // left half-written — roll any stale pull journal back first.
            PullJournal.recoverIfPresent(fs)
            val auth = authToken()
            val api = "https://api.github.com/repos/$owner/$repo"
            val allLocal = fs.walk().filter { !it.startsWith(".anvex/") }
            if (allLocal.size > MAX_FILES) error("Project exceeds the GitHub sync file limit.")
            val included = allLocal.filter { ArtifactHygienePolicy.include(it) }
            val skipped = allLocal.filter { !ArtifactHygienePolicy.include(it) }

            // Content-scan everything BEFORE uploading anything (unchanged rule):
            // a likely secret stops the whole sync, nothing is half-pushed.
            // Audit P-19: the scan used to MATERIALIZE every file's bytes and
            // hold them for the whole push (tens of MB resident on big
            // projects). Bytes are released right after scanning now; the
            // upload phase re-reads each file and re-verifies size + secret,
            // so a file changed between scan and upload cannot slip
            // unscanned content in either.
            for (path in included) {
                val bytes = fs.readFileBytes(path) ?: error("Could not read '$path'.")
                require(bytes.size <= MAX_FILE_BYTES) { "File '$path' exceeds the GitHub sync limit." }
                require(!ArtifactHygienePolicy.containsLikelySecret(path, bytes)) {
                    error("GitHub sync stopped: a likely secret was detected in $path. Remove the secret and retry.")
                }
            }

            // v4.10.1 (BUG-10): Git Data API — blobs + ONE tree + ONE commit +
            // one ref move, instead of a commit per file (2 requests each).
            // A failure before the ref move leaves the branch untouched.
            val refUrl = "$api/git/ref/heads/$branch"
            val baseCommit = runCatching { requestJson(refUrl, auth, "GET").optJSONObject("object")?.optString("sha") }.getOrNull()?.takeIf { it.isNotBlank() }
            val baseTreeSha = baseCommit?.let { requestJson("$api/git/commits/$it", auth, "GET").optJSONObject("tree")?.optString("sha") }
            val baseEntries = if (baseTreeSha != null) {
                val tree = requestJson("$api/git/trees/$baseTreeSha?recursive=1", auth, "GET")
                require(!tree.optBoolean("truncated", false)) { "Remote tree is too large to sync safely." }
                val arr = tree.optJSONArray("tree")
                buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        if (e.optString("type") == "blob") add(Triple(e.optString("path"), e.optString("mode").ifBlank { "100644" }, e.optString("sha")))
                    }
                }
            } else emptyList()

            // Blob uploads with bounded parallelism (audit P-19): results
            // land in per-index slots, so the tree below is built in the
            // same deterministic order as before.
            val blobShas = arrayOfNulls<String>(included.size)
            coroutineScope {
                val gate = Semaphore(MAX_PARALLEL_BLOBS)
                included.mapIndexed { index, path ->
                    async {
                        gate.withPermit {
                            val bytes = fs.readFileBytes(path) ?: error("Could not read '$path'.")
                            require(bytes.size <= MAX_FILE_BYTES) { "File '$path' exceeds the GitHub sync limit." }
                            require(!ArtifactHygienePolicy.containsLikelySecret(path, bytes)) {
                                error("GitHub sync stopped: a likely secret was detected in $path. Remove the secret and retry.")
                            }
                            val blob = requestJson(
                                "$api/git/blobs", auth, "POST",
                                JSONObject().put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("encoding", "base64"),
                            )
                            blobShas[index] = blob.optString("sha")
                        }
                    }
                }.awaitAll()
            }
            val treeEntries = org.json.JSONArray()
            var pushed = 0
            for ((index, path) in included.withIndex()) {
                treeEntries.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blobShas[index]))
                pushed++
            }
            // Carry over remote files the sync does not manage (excluded by the
            // hygiene policy): they are never deleted by a push. Managed files
            // missing locally must be deleted EXPLICITLY: with `base_tree`,
            // merely omitting an entry KEEPS it (the old code's comment
            // claimed deletions propagate, but they did not — renames left
            // both copies on the remote).
            val localPaths = allLocal.toSet()
            for ((path, mode, sha) in baseEntries) {
                if (path in localPaths) continue // included above, or excluded locally (kept below via the same branch)
                if (ArtifactHygienePolicy.include(path)) {
                    // M-04: managed but deleted locally — explicit null sha.
                    treeEntries.put(JSONObject().put("path", path).put("mode", mode).put("type", "blob").put("sha", JSONObject.NULL))
                    continue
                }
                treeEntries.put(JSONObject().put("path", path).put("mode", mode).put("type", "blob").put("sha", sha))
            }
            // Excluded local files that also exist remotely stay untouched.
            for ((path, mode, sha) in baseEntries) {
                if (path in skipped && path !in included) {
                    treeEntries.put(JSONObject().put("path", path).put("mode", mode).put("type", "blob").put("sha", sha))
                }
            }

            val treeBody = JSONObject().put("tree", treeEntries)
            if (baseTreeSha != null) treeBody.put("base_tree", baseTreeSha)
            val newTree = requestJson("$api/git/trees", auth, "POST", treeBody).optString("sha")
            val commitBody = JSONObject().put("message", message.take(200)).put("tree", newTree)
            if (baseCommit != null) commitBody.put("parents", org.json.JSONArray().put(baseCommit))
            val newCommit = requestJson("$api/git/commits", auth, "POST", commitBody).optString("sha")
            if (baseCommit != null) {
                requestJson("$api/git/refs/heads/$branch", auth, "PATCH", JSONObject().put("sha", newCommit).put("force", false))
            } else {
                requestJson("$api/git/refs", auth, "POST", JSONObject().put("ref", "refs/heads/$branch").put("sha", newCommit))
            }
            SyncOutcome(pushed, skipped)
        }
    }

    /** Pulls a repository tree into the selected workspace with strict path/size limits. */
    suspend fun pull(fs: ProjectFileSystem, owner: String, repo: String, branch: String = "main"): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            validateRepo(owner, repo, branch, "pull")
            // 2026-11-04 (audit wave): self-heal a previously crashed
            // pull before touching the tree (see PullJournal).
            PullJournal.recoverIfPresent(fs)
            val auth = authToken()
            val root = "https://api.github.com/repos/$owner/$repo/git/trees/${java.net.URLEncoder.encode(branch, Charsets.UTF_8.name())}?recursive=1"
            val tree = requestJson(root, auth, "GET")
            require(!tree.optBoolean("truncated", false)) { "GitHub tree is too large; refusing partial pull." }
            val items = tree.optJSONArray("tree") ?: JSONArray()
            require(items.length() <= MAX_FILES) { "Repository exceeds the file limit." }

            // v4.10.1 (BUG-10): phase 1 validates EVERY path before a single
            // byte is written — the old loop discovered an excluded path
            // mid-pull and aborted with earlier files already overwritten.
            data class Pending(val path: String, val blobUrl: String)
            val pending = ArrayList<Pending>()
            val skipped = ArrayList<String>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") != "blob") continue
                val path = PathSanitizer.sanitizeOrNull(item.optString("path")) ?: error("Unsafe repository path rejected.")
                val size = item.optLong("size", 0L)
                require(size in 0..MAX_FILE_BYTES) { "Remote file '$path' exceeds the sync limit." }
                if (!ArtifactHygienePolicy.include(path) || path.startsWith(".anvex/")) {
                    skipped += path
                    continue
                }
                val blobUrl = item.optString("url")
                require(blobUrl.startsWith("https://api.github.com/repos/")) { "Unexpected GitHub blob URL." }
                pending += Pending(path, blobUrl)
            }
            // Phase 2: download + write. Audit 02: a failure mid-pull used
            // to leave a half-updated workspace indistinguishable from a
            // completed pull. Before-images are captured as files are
            // written (memory-bounded by MAX_PULL_BACKUP_BYTES; files past
            // the cap are written without one) and any failure rolls the
            // backed-up files back in reverse order.
            val backups = ArrayList<Pair<String, ByteArray?>>()
            var backupBytes = 0L
            var written = 0
            // 2026-11-04 (audit wave): the same before-images are ALSO
            // mirrored to the durable PullJournal, so a process death
            // mid-pull no longer strands a half-written tree.
            val journal = PullJournal(fs)
            try {
                for (entry in pending) {
                    val blob = requestJson(entry.blobUrl, auth, "GET")
                    val content = blob.optString("content").replace("\n", "")
                    val bytes = Base64.decode(content, Base64.DEFAULT)
                    require(bytes.size <= MAX_FILE_BYTES) { "Remote file '${entry.path}' exceeds the sync limit." }
                    if (ArtifactHygienePolicy.containsLikelySecret(entry.path, bytes)) {
                        skipped += entry.path
                        continue
                    }
                    val before = runCatching { fs.readFileBytes(entry.path) }.getOrNull()
                    if (backupBytes + (before?.size ?: 0) <= MAX_PULL_BACKUP_BYTES) {
                        backups += entry.path to before
                        backupBytes += before?.size ?: 0
                    }
                    journal.record(entry.path, before)
                    PathSanitizer.parent(entry.path)?.let { if (it.isNotBlank()) fs.mkdirs(it) }
                    fs.writeFileBytes(entry.path, bytes)
                    written++
                }
                journal.discard()
            } catch (e: Exception) {
                var rollbackOk = true
                for ((path, before) in backups.asReversed()) {
                    runCatching {
                        if (before != null) fs.writeFileBytes(path, before) else fs.delete(path, recursive = false)
                    }.onFailure { rollbackOk = false }
                }
                // The journal is dropped only when the in-process
                // rollback fully succeeded; otherwise it stays so the
                // next sync's recovery pass can retry the restore.
                if (rollbackOk) journal.discard()
                throw e
            }
            SyncOutcome(written, skipped)
        }
    }

    private fun requestJson(url: String, token: String, method: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "PATCH" -> builder.patch((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> error("Unsupported GitHub method")
        }
        return http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // M-04: the bare HTTP code told the user nothing (a 403
                // could be rate-limit, SSO, or scope). Surface GitHub's
                // own message plus the rate-limit state.
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                val rate = response.header("X-RateLimit-Remaining")
                    ?.let { " (rate limit remaining: $it)" }.orEmpty()
                error("GitHub request failed (HTTP ${response.code}$msg)$rate.")
            }
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

    /**
     * 2026-11-04 (external-audit wave): durable pull journal.
     *
     * Before-images used to live only in memory (the audit-02 rollback):
     * a process death mid-pull left a half-written tree with no way
     * back. Images are now mirrored into `.anvex/pull-journal/` inside
     * the project — a subtree sync itself never reads or writes — with
     * a TSV manifest (url-encoded path, existed flag, image name). The
     * journal entry for a file is written BEFORE that file is
     * overwritten; a crash therefore always leaves a complete journal
     * for everything already touched. [recoverIfPresent] rolls a stale
     * journal back and is called at the start of pull and push.
     * Restores are idempotent (re-applying one is a no-op).
     */
    private class PullJournal(private val fs: ProjectFileSystem) {
        private var count = 0
        private var bytes = 0L
        private val lines = StringBuilder()

        fun record(path: String, before: ByteArray?) {
            var imageName = "-"
            if (before != null && bytes + before.size <= MAX_PULL_BACKUP_BYTES) {
                val candidate = "img-$count.bin"
                val ok = runCatching {
                    fs.mkdirs(DIR)
                    fs.writeFileBytes("$DIR/$candidate", before)
                }.isSuccess
                if (!ok) return // unjournaled entry: same as over-cap today
                imageName = candidate
                bytes += before.size
            }
            lines.append(enc(path)).append('\t')
                .append(if (before != null) '1' else '0').append('\t')
                .append(imageName).append('\n')
            count++
            runCatching {
                fs.mkdirs(DIR)
                fs.writeFile(MANIFEST, lines.toString())
            }
        }

        /** Rolls the manifest back (newest first) and clears the journal. */
        fun rollbackFromManifest(): Boolean {
            val text = runCatching { fs.readFile(MANIFEST) }.getOrNull() ?: return false
            val entries = text.lineSequence().filter { it.isNotBlank() }.map { line ->
                val parts = line.split('\t')
                Triple(
                    runCatching { dec(parts[0]) }.getOrDefault(parts[0]),
                    parts.getOrNull(1) == "1",
                    parts.getOrNull(2) ?: "-",
                )
            }.toList()
            for ((path, existed, image) in entries.asReversed()) {
                runCatching {
                    if (existed && image != "-") {
                        fs.readFileBytes("$DIR/$image")?.let { fs.writeFileBytes(path, it) }
                    } else if (!existed) {
                        fs.delete(path, recursive = false)
                    }
                }
            }
            discard()
            return true
        }

        fun discard() {
            runCatching {
                if (fs.exists(DIR)) {
                    fs.list(DIR).forEach { entry ->
                        runCatching { fs.delete("$DIR/${entry.name}", recursive = false) }
                    }
                    fs.delete(DIR, recursive = false)
                }
            }
        }

        companion object {
            const val DIR = ".anvex/pull-journal"
            const val MANIFEST = "$DIR/manifest.tsv"

            private fun enc(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
            private fun dec(s: String): String = java.net.URLDecoder.decode(s, Charsets.UTF_8.name())

            fun recoverIfPresent(fs: ProjectFileSystem): Boolean =
                PullJournal(fs).rollbackFromManifest()
        }
    }

    companion object {
        const val MAX_FILES = 2_000
        const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /** Prefix marking AAD-bound token ciphertexts (audit 04). */
        private const val TOKEN_V2_PREFIX = "v2:"
        private val TOKEN_AAD: ByteArray = "anvex-github-token-v1".toByteArray(Charsets.UTF_8)

        /** Bounded parallel blob uploads during push (audit P-19). */
        private const val MAX_PARALLEL_BLOBS = 4

        /** Cap on pull before-images kept for rollback (audit 02). */
        private const val MAX_PULL_BACKUP_BYTES = 64L * 1024 * 1024
    }
}
