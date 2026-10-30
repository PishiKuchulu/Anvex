package com.anvex.core.android.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.anvex.core.engine.VersionNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** A newer signed release published on GitHub, with the APK for this device. */
data class AppUpdateInfo(
    val version: String,
    val tag: String,
    val apkName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** v4.10.1 (SEC-06): the release's published `<apk>.sha256` asset, when present. */
    val sha256Url: String? = null,
)

/**
 * v4.7: in-app update check against this app's own distribution channel —
 * the versioned GitHub releases of PishiKuchulu/Anvex. Deliberately NOT the
 * `/releases/latest` endpoint: the rolling debug release (tag `latest`) is
 * republished on every main build and could shadow a versioned release, so
 * the list is scanned for strict `vX.Y.Z` tags and the highest wins. The
 * asset for the device's primary ABI is preferred, with the universal APK
 * as fallback. No token needed (public repo), no data leaves the device
 * beyond the request itself.
 */
object AppUpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/PishiKuchulu/Anvex/releases?per_page=20"
    private const val USER_AGENT = "Anvex-Updater"

    /**
     * The only hosts a release-download redirect may land on: github.com
     * itself (no redirect) and GitHub's release-asset CDN hosts — today
     * release-assets.githubusercontent.com serves every release asset
     * (verified against the live v4.11.1 asset redirect); objects… is the
     * legacy host. Exact matches only, so lookalike suffix domains fail.
     * (The 4.11.x updater omitted release-assets… and so refused every
     * real download — this set is pinned by AppUpdateCheckerHostTest.)
     */
    internal fun isAllowedUpdateRedirectHost(host: String?): Boolean =
        host == "github.com" ||
            host == "objects.githubusercontent.com" ||
            host == "release-assets.githubusercontent.com"

    /** The newest release strictly newer than [currentVersion], or null. */
    suspend fun fetchLatestNewerThan(currentVersion: String): AppUpdateInfo? =
        withContext(Dispatchers.IO) {
            val releases = JSONArray(httpGet(RELEASES_URL))
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            var best: AppUpdateInfo? = null
            for (i in 0 until releases.length()) {
                val rel = releases.optJSONObject(i) ?: continue
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
                val tag = rel.optString("tag_name")
                val version = VersionNumbers.parse(tag)?.joinToString(".") ?: continue
                if (!VersionNumbers.isNewer(version, currentVersion)) continue
                val current = best
                if (current != null && !VersionNumbers.isNewer(version, current.version)) continue
                val asset = pickAsset(rel.optJSONArray("assets"), version, abi) ?: continue
                val shaAsset = pickShaAsset(rel.optJSONArray("assets"), asset.optString("name"))
                best = AppUpdateInfo(
                    version = version,
                    tag = tag,
                    apkName = asset.optString("name"),
                    downloadUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                    sha256Url = shaAsset?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                )
            }
            best
        }

    private fun pickShaAsset(assets: JSONArray?, apkName: String): JSONObject? {
        if (assets == null || apkName.isBlank()) return null
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            if (asset.optString("name") == "$apkName.sha256") return asset
        }
        return null
    }

    private fun pickAsset(assets: JSONArray?, version: String, abi: String): JSONObject? {
        if (assets == null) return null
        var universal: JSONObject? = null
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            when (asset.optString("name")) {
                "Anvex-$version-$abi.apk" -> return asset
                "Anvex-$version.apk" -> universal = asset
            }
        }
        return universal
    }

    /**
     * Downloads [url] to [dest] (via a `.part` sibling, renamed on success),
     * reporting (downloaded, total) progress. Cancellation lands between
     * chunks; a cancelled or failed attempt only leaves the `.part` file,
     * which the caller removes.
     *
     * M-03: the checksum is now MANDATORY (a release without a published
     * `.sha256` asset is refused — every published Anvex release ships
     * one), both URLs must be Anvex release-download URLs, and the hash
     * is computed in the same pass as the download (the old code read
     * the 60–160 MB file three times: write, length recount, hash).
     */
    suspend fun download(
        url: String,
        dest: File,
        sha256Url: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        requireAnvexReleaseUrl(url)
        require(!sha256Url.isNullOrBlank()) { "Update has no published checksum — refusing to install." }
        requireAnvexReleaseUrl(sha256Url)
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val code = conn.responseCode
                check(code in 200..299) { "Download failed (HTTP $code)" }
                // Redirects from github.com release downloads land on
                // GitHub's asset CDN (release-assets.githubusercontent.com
                // today); anything outside the allowlist is suspect.
                val finalHost = conn.url.host
                check(isAllowedUpdateRedirectHost(finalHost)) {
                    "Update download redirected to an unexpected host: $finalHost"
                }
                val total = conn.contentLengthLong
                dest.parentFile?.mkdirs()
                val part = File(dest.parentFile, dest.name + ".part")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, if (total > 0) total else downloaded)
                        }
                        // v4.10.1 (SEC-06): a truncated download used to be
                        // renamed into place and handed to the installer.
                        // Completeness comes from the same pass's counter.
                        if (total > 0) {
                            check(downloaded == total) { "Download incomplete ($downloaded of $total bytes)." }
                        }
                    }
                }
                val expected = httpGet(sha256Url).trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                check(!expected.isNullOrBlank() && expected.matches(Regex("[0-9a-f]{64}"))) {
                    "Could not read a valid update checksum."
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected) { "Update checksum mismatch — refusing to install a corrupted or tampered file." }
                if (dest.exists()) dest.delete()
                check(part.renameTo(dest)) { "Could not finalize the downloaded update" }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * M-03: the download URL must be one of this app's own release
     * assets. The release list comes from api.github.com, but its
     * `browser_download_url` fields are data, not trust — a tampered or
     * mirrored listing must not redirect the updater to an arbitrary host.
     */
    private fun requireAnvexReleaseUrl(url: String) {
        val u = runCatching { URL(url) }.getOrNull()
        require(u != null && u.protocol == "https" && u.host == "github.com" &&
            u.path.startsWith("/PishiKuchulu/Anvex/releases/download/")) {
            "Unexpected update URL host or path: $url"
        }
    }

    /**
     * M-03: Android itself verifies the installer signature, but only
     * against the INSTALLED app's key at install time — and for a debug
     * build that check is meaningless (public debug key). Compare the
     * downloaded APK's signers with the running app's signers BEFORE
     * firing the installer, so a mismatched APK never reaches it.
     * Returns false (refuse) when either side's signers cannot be read.
     */
    fun verifySameSigner(context: Context, apkFile: File): Boolean {
        return runCatching {
            val pm = context.packageManager
            // GET_SIGNING_CERTIFICATES exists only from API 28; older
            // devices use the (deprecated) GET_SIGNATURES path.
            @Suppress("DEPRECATION")
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, flag)
                ?: return false
            val archiveCerts = signerCertBytes(pm, archive, apkFile.absolutePath)
            val installed = pm.getPackageInfo(context.packageName, flag)
            val installedCerts = signerCertBytes(pm, installed, context.packageName)
            archiveCerts.isNotEmpty() && archiveCerts == installedCerts
        }.getOrDefault(false)
    }

    private fun signerCertBytes(
        pm: PackageManager,
        info: android.content.pm.PackageInfo,
        label: String,
    ): Set<List<Byte>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signers = info.signingInfo?.apkContentsSigners
            if (!signers.isNullOrEmpty()) return signers.map { it.toByteArray().toList() }.toSet()
        }
        @Suppress("DEPRECATION")
        val legacy = info.signatures
        if (!legacy.isNullOrEmpty()) return legacy.map { it.toByteArray().toList() }.toSet()
        return emptySet()
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "Update check failed (HTTP $code)" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
