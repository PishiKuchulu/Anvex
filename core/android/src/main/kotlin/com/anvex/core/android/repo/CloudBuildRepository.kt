package com.anvex.core.android.repo

import android.util.Base64
import com.anvex.core.android.build.SafeDirUnzip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Cloud APK builder via GitHub Actions (architect review feature #4, path 1).
 *
 * Flow: publish the project with the existing [GitHubRepository] Contents
 * sync (called from the view model), make sure the Anvex workflow file
 * exists, dispatch it, observe the new run and finally download the built
 * APK artifact.
 *
 * Honesty rules: every reported state (queued / in_progress / completed /
 * success / failure) comes verbatim from the GitHub Actions API — nothing is
 * fabricated or assumed. Errors surface as clear, actionable messages.
 *
 * Security rules:
 *  - The GitHub token is injected through [tokenProvider] (AppGraph wires it
 *    to [GitHubRepository.savedToken]); the Keystore decryption stays in one
 *    place and is never duplicated here. The token is never logged and never
 *    appears in a user-visible string or URL.
 *  - Every request sends `Authorization: Bearer …` plus `User-Agent: Anvex`.
 *    The logs/artifact endpoints answer with a 302 to a signed URL on a
 *    different host; a network interceptor removes the Authorization header
 *    from any request that leaves api.github.com (belt-and-braces on top of
 *    OkHttp's own cross-host redirect stripping), so the signed credential
 *    in the redirect URL is the only one used — and it is never logged.
 *  - Downloads are hard-capped: [MAX_LOG_BYTES] of run-log text
 *    (decompression-safe, zip-bomb-safe), [MAX_ARTIFACT_BYTES] artifacts,
 *    zip-slip-safe extraction through [SafeDirUnzip].
 *  - An existing `.github/workflows/anvex-cloud-build.yml` is never
 *    overwritten — [ensureWorkflowFile] only creates it when missing.
 */
class CloudBuildRepository(private val tokenProvider: () -> String?) {

    /** One workflow run as reported verbatim by the Actions API. */
    data class RunInfo(
        val id: Long,
        val status: String,
        val conclusion: String?,
        val htmlUrl: String,
        val createdAt: String,
    )

    /** HTTP failure from the GitHub API; [status] is kept for callers. */
    class ApiException(val status: Int, override val message: String) : Exception(message)

    private val jsonType = "application/json".toMediaType()

    /**
     * Never forward the GitHub token to a host other than api.github.com.
     * Runs as a NETWORK interceptor so it also covers redirect follow-ups
     * (the signed log/artifact URLs live on githubusercontent.com and work
     * without authentication).
     */
    private val stripAuthOutsideApi = Interceptor { chain ->
        val request = chain.request()
        val safe = if (request.url.host == API_HOST) request
        else request.newBuilder().removeHeader("Authorization").build()
        chain.proceed(safe)
    }

    /** JSON API client: 30s timeout per call, redirects followed. */
    private val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor(stripAuthOutsideApi)
        .build()

    /**
     * Download client: identical 30s per-hop timeouts (a stalled connection
     * still aborts after 30s) but a longer total budget so large APK
     * artifacts are not cut off mid-download. Shares the connection pool.
     */
    private val downloadClient: OkHttpClient = api.newBuilder()
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    // ---- token -----------------------------------------------------------

    private fun requireToken(): String =
        tokenProvider()?.takeIf { it.isNotBlank() }
            ?: error("Add a GitHub token in GitHub Sync before using cloud builds.")

    // ---- public API ---------------------------------------------------------

    /**
     * Creates the Anvex cloud-build workflow file on [branch] when the
     * repository does not have it yet. `workflow_dispatch` only accepts
     * workflows that are also registered on the repository's DEFAULT branch,
     * so when [branch] is not the default the file is created there too.
     * Existing files are never overwritten.
     * @return true when the file was created on at least one branch, false
     * when it already existed everywhere.
     */
    suspend fun ensureWorkflowFile(owner: String, repo: String, branch: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val auth = requireToken()
            validateTarget(owner, repo, branch)
            var created = ensureWorkflowOnBranch(owner, repo, branch, auth)
            val defaultBranch = fetchDefaultBranch(owner, repo, auth)
            if (defaultBranch != null && defaultBranch != branch) {
                if (ensureWorkflowOnBranch(owner, repo, defaultBranch, auth)) created = true
            }
            created
        }
    }

    /** Puts [WORKFLOW_TEMPLATE] on [branch] when absent; existing files are left untouched. */
    private fun ensureWorkflowOnBranch(owner: String, repo: String, branch: String, auth: String): Boolean {
        val contentsPath = "/repos/$owner/$repo/contents/${encodePath(WORKFLOW_PATH)}"
        api.newCall(jsonRequest("$contentsPath?ref=${encode(branch)}", auth)).execute().use { response ->
            when {
                response.isSuccessful -> return false // already present on this branch
                response.code != 404 -> requireSuccess(response)
            }
        }
        val body = JSONObject()
            .put("message", "Add Anvex cloud build workflow")
            .put("content", Base64.encodeToString(WORKFLOW_TEMPLATE.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", branch)
        api.newCall(jsonRequest(contentsPath, auth, "PUT", body)).execute().use { response ->
            requireSuccess(response)
        }
        return true
    }

    /** Repository default branch (GET /repos/{o}/{r}); null when it cannot be determined. */
    private fun fetchDefaultBranch(owner: String, repo: String, auth: String): String? = runCatching {
        api.newCall(jsonRequest("/repos/$owner/$repo", auth)).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            JSONObject(response.body?.string().orEmpty()).optString("default_branch").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /**
     * Dispatches the workflow. GitHub answers 204 No Content and returns no
     * run id for a dispatch, so success is reported as [Unit]; observe the
     * run that this dispatch created through [latestRun] instead.
     */
    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowFile: String,
        ref: String,
        inputs: Map<String, String> = emptyMap(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val auth = requireToken()
            validateTarget(owner, repo, ref)
            require(workflowFile.matches(Regex("[A-Za-z0-9._-]{1,200}"))) { "Invalid workflow file name." }
            val body = JSONObject().put("ref", ref)
            if (inputs.isNotEmpty()) {
                val jsonInputs = JSONObject()
                inputs.forEach { (k, v) -> jsonInputs.put(k.take(100), v.take(200)) }
                body.put("inputs", jsonInputs)
            }
            api.newCall(jsonRequest("/repos/$owner/$repo/actions/workflows/${encode(workflowFile)}/dispatches", auth, "POST", body))
                .execute().use { response ->
                    requireSuccess(response) // 204 expected; any non-2xx is an error
                    Unit
                }
        }
    }

    /**
     * The most recent workflow run on [branch]. When [workflowPath] is given,
     * the newest run of that specific workflow file is returned instead of the
     * newest run of any workflow (repositories often run unrelated CI too).
     * @return null when the branch has no matching runs yet.
     */
    suspend fun latestRun(owner: String, repo: String, branch: String, workflowPath: String? = null): Result<RunInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = requireToken()
                validateTarget(owner, repo, branch)
                if (workflowPath == null) {
                    fetchRuns("/repos/$owner/$repo/actions/runs?branch=${encode(branch)}&per_page=1", auth)
                        .optJSONObject(0)?.let(::toRunInfo)
                } else {
                    require(workflowPath.matches(Regex("[A-Za-z0-9_./-]{1,200}")) && !workflowPath.contains("..")) { "Invalid workflow path." }
                    // Exact listing scoped to our workflow file. Falls back to the
                    // branch-wide listing (client-side path filter) while the
                    // workflow is not registered on the default branch yet — the
                    // scoped endpoint answers 404 in that case.
                    val scoped = runCatching {
                        fetchRuns(
                            "/repos/$owner/$repo/actions/workflows/${encode(workflowPath.substringAfterLast('/'))}/runs" +
                                "?branch=${encode(branch)}&per_page=5",
                            auth,
                        )
                    }.getOrNull()
                    val run = if (scoped != null) scoped.optJSONObject(0)
                    else fetchRuns("/repos/$owner/$repo/actions/runs?branch=${encode(branch)}&per_page=20", auth)
                        .firstRunMatching(workflowPath)
                    run?.let(::toRunInfo)
                }
            }
        }

    /**
     * Downloads the run's log TEXT (tail). The endpoint answers 302 with a
     * signed redirect to a zip archive of per-job logs; OkHttp follows the
     * redirect and the archive is decoded in memory. The result is capped at
     * [maxBytes] of decompressed text (zip-bomb safe).
     */
    suspend fun fetchLogTail(owner: String, repo: String, runId: Long, maxBytes: Int = MAX_LOG_BYTES): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = requireToken()
                validateTarget(owner, repo)
                require(runId > 0) { "Invalid workflow run id." }
                require(maxBytes in 1..MAX_LOG_BYTES) { "Invalid log size cap." }
                val (bytes, overflow) = downloadClient
                    .newCall(authGet("$API_ROOT/repos/$owner/$repo/actions/runs/$runId/logs", auth))
                    .execute().use { response ->
                        if (response.code == 404) {
                            throw ApiException(404, "Run logs are not available yet (HTTP 404). Try again once the run has started.")
                        }
                        requireSuccess(response)
                        val stream = response.body?.byteStream() ?: error("Empty log response.")
                        stream.use { readCapped(it, maxBytes) }
                    }
                decodeRunLogArchive(bytes, overflow, maxBytes)
            }
        }

    /**
     * Downloads the newest non-expired APK artifact of [runId], extracts the
     * .apk from the artifact zip (zip-slip safe via [SafeDirUnzip]) into
     * [targetDir] and returns the file. When the archive contains several
     * APKs the largest one is returned (multi-module projects). Artifacts
     * larger than [MAX_ARTIFACT_BYTES] are rejected before downloading.
     */
    suspend fun downloadArtifactApk(owner: String, repo: String, runId: Long, targetDir: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = requireToken()
                validateTarget(owner, repo)
                require(runId > 0) { "Invalid workflow run id." }
                require(targetDir.isDirectory) { "Artifact target directory does not exist." }

                val root = api.newCall(jsonRequest("/repos/$owner/$repo/actions/runs/$runId/artifacts?per_page=50", auth))
                    .execute().use { response ->
                        requireSuccess(response)
                        JSONObject(response.body?.string().orEmpty())
                    }
                val artifacts: JSONArray = root.optJSONArray("artifacts") ?: JSONArray()
                var best: JSONObject? = null
                var bestId = 0L
                for (i in 0 until artifacts.length()) {
                    val item = artifacts.optJSONObject(i) ?: continue
                    if (item.optBoolean("expired", false)) continue
                    if (!item.optString("name").contains("apk", ignoreCase = true)) continue
                    val id = item.optLong("id", 0L)
                    if (best == null || id > bestId) {
                        best = item
                        bestId = id
                    }
                }
                val artifact = best ?: error("No downloadable APK artifact was found for run $runId.")
                val size = artifact.optLong("size_in_bytes", -1L)
                if (size < 0 || size > MAX_ARTIFACT_BYTES) {
                    error(
                        "The APK artifact is too large to download (reported " +
                            (if (size > 0) "${size / (1024 * 1024)} MB" else "unknown size") +
                            "; the limit is ${MAX_ARTIFACT_BYTES / (1024 * 1024)} MB)."
                    )
                }
                val downloadUrl = artifact.optString("archive_download_url")
                require(downloadUrl.startsWith("https://$API_HOST/repos/")) { "Unexpected artifact download URL." }

                val zip = File(targetDir, "artifact-$runId.zip")
                downloadClient.newCall(authGet(downloadUrl, auth)).execute().use { response ->
                    requireSuccess(response)
                    val declared = response.header("Content-Length")?.toLongOrNull()
                    if (declared != null && declared > MAX_ARTIFACT_BYTES) {
                        error("The artifact download exceeds the ${MAX_ARTIFACT_BYTES / (1024 * 1024)} MB limit.")
                    }
                    val input = response.body?.byteStream() ?: error("Empty artifact download.")
                    zip.outputStream().use { out -> copyCapped(input, out, MAX_ARTIFACT_BYTES) }
                }
                try {
                    SafeDirUnzip.extract(zip.inputStream(), targetDir)
                    targetDir.walkTopDown()
                        .filter { it.isFile && it != zip && it.extension.equals("apk", true) }
                        .maxByOrNull { it.length() }
                        ?: error("The artifact archive contained no .apk file.")
                } finally {
                    runCatching { zip.delete() }
                }
            }
        }

    // ---- helpers ---------------------------------------------------------

    private fun fetchRuns(path: String, auth: String): JSONArray {
        val root = api.newCall(jsonRequest(path, auth)).execute().use { response ->
            requireSuccess(response)
            JSONObject(response.body?.string().orEmpty())
        }
        return root.optJSONArray("workflow_runs") ?: JSONArray()
    }

    private fun JSONArray.firstRunMatching(workflowPath: String): JSONObject? {
        for (i in 0 until length()) {
            val run = optJSONObject(i) ?: continue
            if (run.optString("path") == workflowPath) return run
        }
        return null
    }

    private fun toRunInfo(run: JSONObject): RunInfo {
        val id = run.optLong("id", 0L)
        require(id > 0) { "Invalid run id from GitHub." }
        return RunInfo(
            id = id,
            status = run.optString("status").ifBlank { "unknown" },
            conclusion = run.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
            htmlUrl = run.optString("html_url"),
            createdAt = run.optString("created_at"),
        )
    }

    /**
     * Turns the downloaded bytes (a zip archive of per-job logs, or plain
     * text) into capped log text. Decompression is budget-limited so a
     * malicious archive cannot expand past [cap]; entries are prefixed with
     * their job-file name so jobs stay distinguishable.
     */
    internal fun decodeRunLogArchive(bytes: ByteArray, overflow: Boolean, cap: Int = MAX_LOG_BYTES): String {
        val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) {
            val text = bytes.toString(Charsets.UTF_8)
            return if (overflow) "$text\n…(log truncated at the display cap)" else text
        }
        val out = StringBuilder()
        var remaining = cap
        var truncated = false
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entries = 0
                while (true) {
                    if (entries >= MAX_LOG_ENTRIES) {
                        truncated = zip.nextEntry != null // more entries were dropped
                        break
                    }
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entry.isDirectory) continue
                    val header = "── ${entry.name} ──\n"
                    if (header.length > remaining) {
                        truncated = true
                        break
                    }
                    out.append(header)
                    remaining -= header.length
                    val (chunk, over) = readCapped(zip, remaining)
                    out.append(chunk.toString(Charsets.UTF_8))
                    remaining -= chunk.size
                    if (over) {
                        truncated = true
                        break
                    }
                }
            }
        } catch (_: ZipException) {
            truncated = true // short/truncated archive — keep what decoded cleanly
        }
        if (overflow || truncated) out.append("\n…(log truncated at the display cap)")
        return out.toString()
    }

    /** Reads at most [cap] bytes; the flag reports whether the source had more. */
    private fun readCapped(input: InputStream, cap: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(cap.coerceAtMost(256 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        var overflow = false
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            val room = cap - total
            if (n > room) {
                out.write(buffer, 0, room)
                total = cap
                overflow = true
                break
            }
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray() to overflow
    }

    /** Streams to [out], refusing to write more than [cap] bytes. */
    private fun copyCapped(input: InputStream, out: OutputStream, cap: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > cap) error("The download exceeded the ${cap / (1024 * 1024)} MB safety limit.")
            out.write(buffer, 0, n)
        }
    }

    /** JSON request builder for the REST API. Never logs URLs or tokens. */
    private fun jsonRequest(path: String, auth: String, method: String = "GET", body: JSONObject? = null): Request {
        val builder = Request.Builder()
            .url("$API_ROOT$path")
            .header("Authorization", "Bearer $auth")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> error("Unsupported method")
        }
        return builder.build()
    }

    /** Authorized GET for download endpoints (absolute URL, redirect-followed). */
    private fun authGet(url: String, auth: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $auth")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

    /**
     * Converts a non-2xx response into a clear, actionable error. Only the
     * status hint plus GitHub's own bounded error message are thrown — never
     * a URL, header or token.
     */
    private fun requireSuccess(response: okhttp3.Response) {
        if (response.isSuccessful) return
        val code = response.code
        val detail = runCatching {
            JSONObject(response.body?.string().orEmpty()).optString("message")
                .takeIf { it.isNotBlank() }?.take(200)
        }.getOrNull()
        val base: String? = when (code) {
            401 -> "GitHub rejected the token (HTTP 401). Check the token saved in GitHub Sync and its Actions permissions."
            403 -> if (response.header("x-ratelimit-remaining") == "0") {
                "GitHub API rate limit reached (HTTP 403). Wait a few minutes and try again."
            } else {
                "GitHub denied the request (HTTP 403). The token may lack Actions access to this repository."
            }
            404 -> "Not found (HTTP 404). Check the owner, repository and branch values."
            409 -> "GitHub reported a conflict (HTTP 409). The repository may be empty — create it with an initial commit first."
            422 -> "GitHub rejected the request (HTTP 422). Check the owner/repository/branch values."
            429 -> "GitHub API rate limit reached (HTTP 429). Wait a few minutes and try again."
            else -> null
        }
        val text = listOfNotNull(base, detail).joinToString(" — ")
            .ifBlank { "GitHub request failed (HTTP $code)." }
        throw ApiException(code, text)
    }

    private fun validateTarget(owner: String, repo: String, branch: String? = null) {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid GitHub owner." }
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid repository name." }
        if (branch != null) {
            require(branch.matches(Regex("[A-Za-z0-9._/-]{1,200}")) && !branch.contains("..") && !branch.startsWith("/") && !branch.endsWith("/")) {
                "Invalid branch."
            }
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encode(it) }

    companion object {
        private const val API_ROOT = "https://api.github.com"
        private const val API_HOST = "api.github.com"
        private const val USER_AGENT = "Anvex"

        /** Path inside the repository; managed by Anvex, never overwritten. */
        const val WORKFLOW_PATH = ".github/workflows/anvex-cloud-build.yml"

        /** Workflow id accepted by the dispatches endpoint (file name form). */
        const val WORKFLOW_FILE = "anvex-cloud-build.yml"

        /** Hard cap for downloaded run-log text (decompressed). */
        const val MAX_LOG_BYTES = 512 * 1024

        /** Hard cap on log archive entries decoded (zip-bomb name vectors). */
        const val MAX_LOG_ENTRIES = 500

        /** Hard cap for artifact downloads, aligned with SafeDirUnzip per-entry limit. */
        const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024

        /**
         * Minimal cloud-build workflow (25 lines): workflow_dispatch with a
         * `variant` input (default "debug"), checkout, a pinned JDK 17 so the
         * build does not depend on the runner's default Java, the Gradle
         * build (`--no-daemon`), and upload of the built APKs (everything
         * under build/outputs/apk) via actions/upload-artifact@v4. The
         * variant arrives through the VARIANT env var (never spliced into
         * the command) and its first letter is capitalized in the shell so
         * `debug` resolves to the `assembleDebug` task. `sh gradlew` avoids
         * the executable bit that the Contents API cannot set on push.
         * `${'$'}` escapes are literal `$` for the GitHub expression / shell.
         */
        const val WORKFLOW_TEMPLATE = """name: Anvex Cloud Build
on:
  workflow_dispatch:
    inputs:
      variant:
        description: "Build variant (debug or release)"
        default: "debug"
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - name: Build APK
        env:
          VARIANT: ${'$'}{{ inputs.variant }}
        run: sh gradlew "assemble${'$'}(printf '%s' "${'$'}VARIANT" | sed 's/^./\U&/')" --no-daemon
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: anvex-apk
          path: '**/build/outputs/apk/**/*.apk'
"""
    }
}
