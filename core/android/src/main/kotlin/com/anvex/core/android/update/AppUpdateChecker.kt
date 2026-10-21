package com.anvex.core.android.update

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
                best = AppUpdateInfo(
                    version = version,
                    tag = tag,
                    apkName = asset.optString("name"),
                    downloadUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                )
            }
            best
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
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
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
                val total = conn.contentLengthLong
                dest.parentFile?.mkdirs()
                val part = File(dest.parentFile, dest.name + ".part")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, if (total > 0) total else downloaded)
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                check(part.renameTo(dest)) { "Could not finalize the downloaded update" }
            } finally {
                conn.disconnect()
            }
        }
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
