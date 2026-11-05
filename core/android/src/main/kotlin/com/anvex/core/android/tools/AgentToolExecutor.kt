package com.anvex.core.android.tools

import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Read-only agent tools (v4.2): `read`, `list`, `fetch`, `search`.
 *
 * Security posture
 *  - `fetch`/`search` are HTTPS-only, never reach loopback / private / link-local addresses
 *    (SSRF), follow at most [MAX_REDIRECTS] redirects and re-validate every hop.
 *  - Bodies are size-capped and only text-like content types are returned.
 *  - Results are handed back to the model wrapped as untrusted data (see ChatSession).
 */
class AgentToolExecutor(
    private val http: OkHttpClient = OkHttpClient.Builder()
        // Validate the addresses OkHttp will actually connect to. Checking only
        // before the request would let a DNS-rebinding host answer with a public
        // IP for the check and a private one for the connection.
        .dns(AgentToolExecutor.PublicOnlyDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    /** Injectable for tests; production resolves through the system resolver. */
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    data class ToolResult(val ok: Boolean, val text: String)

    suspend fun run(action: BoltAction.ToolAction, fs: ProjectFileSystem, webAllowed: Boolean): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                when (action.name) {
                    "read" -> read(action.argument, fs, action.offset, action.limit)
                    "list" -> list(action.argument, fs)
                    "fetch" -> if (webAllowed) fetch(action.argument) else ToolResult(false, "Web tools are disabled in Settings.")
                    "search" -> if (webAllowed) search(action.argument) else ToolResult(false, "Web tools are disabled in Settings.")
                    else -> ToolResult(false, "Unknown tool: ${action.name}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult(false, "${action.name} failed: ${e.message?.take(200) ?: e::class.simpleName}")
            }
        }

    // ------------------------------------------------------------------ project tools

    private fun read(rawPath: String, fs: ProjectFileSystem, offset: Int = 0, limit: Int = 0): ToolResult {
        val path = PathSanitizer.sanitizeOrNull(rawPath.trim()) ?: return ToolResult(false, "Invalid path.")
        // v4.10.1 (SEC-02): the shell runtime already refused to surface
        // secret files, but this tool did not — a prompt-injected page could
        // have the model read .env and exfiltrate it via fetch. One shared
        // list (SensitivePaths) now guards every read surface.
        // v4.10.5 follow-up: extracted zip attachments are the exception —
        // they are user-supplied review sources, not app metadata. Secret
        // files inside an extracted tree stay refused.
        if (com.anvex.core.engine.SensitivePaths.isSensitive(path) &&
            !com.anvex.core.engine.SensitivePaths.isReadableExtractedAttachment(path)
        ) {
            return ToolResult(false, "Refused: '$path' is a protected file (secrets/app metadata are never exposed to tools).")
        }
        val text = fs.readFile(path) ?: return ToolResult(false, "File not found: $path")
        // Paged reads (2026-11-06): before this, a file longer than one
        // window could never be read past its first MAX_READ_CHARS — every
        // re-read returned the same head, so reviews of large files silently
        // covered only their beginning. offset/limit walk the file; the
        // window footer always states exactly which chars were returned and
        // where to continue, so a partial read can never pass as a full one.
        val start = offset.coerceAtLeast(0)
        if (start > 0 && start >= text.length) {
            return ToolResult(false, "Offset $start is at or past the end of '$path' (${text.length} chars) — there is nothing to read there.")
        }
        val cap = if (limit in 1 until MAX_READ_CHARS) limit else MAX_READ_CHARS
        val end = minOf(text.length, start + cap)
        val window = text.substring(start, end)
        return if (start == 0 && end == text.length) {
            ToolResult(true, window)
        } else {
            val remaining = if (end < text.length) "; ${text.length - end} chars remain — read again with offset=$end to continue]" else "]"
            ToolResult(true, window + "\n…[read window: chars $start–$end of ${text.length} in '$path'$remaining")
        }
    }

    private fun list(rawPath: String, fs: ProjectFileSystem): ToolResult {
        val prefix = rawPath.trim().trim('/').let { if (it == "." ) "" else it }
        // Sizes ride along with the walk — a per-path fileSize() re-resolves
        // every file through the storage backend (SAF: minutes per listing).
        val allSized = fs.walkWithSizes()
            .filter { (path, _) -> prefix.isEmpty() || path == prefix || path.startsWith("$prefix/") }
            .sortedBy { it.first }
        // L-03: listing used to name sensitive files (.env, .git/…) to the
        // model even though their contents are refused — a name alone
        // confirms a secret exists. Hide sensitive names, except extracted
        // attachments, which the read tool deliberately serves.
        val visibleSized = allSized.filter { (path, _) ->
            !com.anvex.core.engine.SensitivePaths.isSensitive(path) ||
                com.anvex.core.engine.SensitivePaths.isReadableExtractedAttachment(path)
        }
        val visible = visibleSized.map { it.first }
        val sizeOf = visibleSized.toMap()
        if (visible.isEmpty()) {
            // Distinguish "not here" from "here but empty/hidden": a model
            // told only "(no files)" cannot tell a typo'd path from an empty
            // folder, and may report the folder as missing from the project.
            return if (prefix.isNotEmpty() && !fs.exists(prefix)) {
                ToolResult(true, "Path not found in this workspace: '$prefix'. Nothing was listed — check the path, or list a parent folder.")
            } else {
                ToolResult(true, "(no files)")
            }
        }
        val scope = prefix.ifEmpty { "." }
        if (visible.size <= MAX_LIST_ENTRIES) {
            val body = visible.joinToString("\n") { "$it (${sizeOf[it] ?: 0} bytes)" }
            return ToolResult(true, "Listing ${visible.size} file(s) under '$scope':\n$body")
        }
        // Over the cap, a flat first-slice is actively misleading: it is
        // alphabetical, so late-sorting subtrees (res/, resources/…) vanish
        // entirely and a reviewing model concludes they do not exist
        // (2026-11-02 incident: an 899-file tree read as "res is empty").
        // Answer with a directory summary instead — every folder under the
        // scope with its recursive file count fits in a few dozen lines no
        // matter how large the tree is, so nothing can silently disappear.
        val parents = visible.map { it.substringBeforeLast('/', "") }.toSet()
        if (parents.size <= 1) {
            // One huge flat folder: no sub-structure to summarize.
            val shown = visible.take(MAX_LIST_ENTRIES).joinToString("\n") { "$it (${sizeOf[it] ?: 0} bytes)" }
            return ToolResult(
                true,
                "Listing ${visible.size} file(s) under '$scope' (single folder, showing first ${MAX_LIST_ENTRIES}):\n" +
                    "$shown\n…and ${visible.size - MAX_LIST_ENTRIES} more file(s) in this same folder. " +
                    "This listing is PARTIAL — files not shown still exist.",
            )
        }
        val counts = sortedMapOf<String, Int>()
        visible.forEach { path ->
            // Count the file into every ancestor folder down to (and
            // including) the queried scope; files directly in the scope
            // count under the scope itself.
            var dir = path.substringBeforeLast('/', "")
            while (true) {
                val inScope = prefix.isEmpty() || dir == prefix || dir.startsWith("$prefix/")
                if (!inScope) break
                val key = dir.ifEmpty { "." }
                counts[key] = (counts[key] ?: 0) + 1
                if (dir.isEmpty() || dir == prefix) break
                dir = dir.substringBeforeLast('/', "")
            }
        }
        val out = StringBuilder()
        out.append("Listing ").append(visible.size).append(" file(s) under '").append(scope)
            .append("' — too many to list individually, so here is every folder with its file count:\n")
        val dirs = counts.keys.toList()
        dirs.take(MAX_LIST_SUMMARY_DIRS).forEach { d -> out.append(d).append("/ — ").append(counts.getValue(d)).append(" file(s)\n") }
        if (dirs.size > MAX_LIST_SUMMARY_DIRS) {
            out.append("…folder summary truncated at ").append(MAX_LIST_SUMMARY_DIRS)
                .append(" of ").append(dirs.size).append(" folders; list a subfolder for its summary.\n")
        }
        out.append("This is a PARTIAL view: list any folder above directly to see its files. ")
            .append("Never conclude a file or folder is missing from a partial listing.")
        return ToolResult(true, out.toString())
    }

    // ------------------------------------------------------------------ web tools

    private fun fetch(rawUrl: String): ToolResult {
        if (rawUrl.length > MAX_URL_CHARS) return ToolResult(false, "URL too long (${rawUrl.length} chars, limit $MAX_URL_CHARS).")
        var url = rawUrl.trim().toHttpUrlOrNull() ?: return ToolResult(false, "Invalid URL.")
        var hops = 0
        while (true) {
            checkPublicHttps(url)
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5")
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val location = response.header("Location") ?: return ToolResult(false, "Redirect without Location.")
                    url = url.resolve(location) ?: return ToolResult(false, "Bad redirect target.")
                    if (++hops > MAX_REDIRECTS) return ToolResult(false, "Too many redirects.")
                    return@use
                }
                if (!response.isSuccessful) return ToolResult(false, "HTTP ${response.code} from ${url.host}")
                val type = response.header("Content-Type").orEmpty().lowercase()
                val textual = type.isEmpty() || listOf("text/", "json", "xml", "javascript", "yaml").any { type.contains(it) }
                if (!textual) return ToolResult(false, "Unsupported content type: ${type.substringBefore(';')}")
                val bytes = readCapped(response.body?.byteStream() ?: return ToolResult(false, "Empty body."), MAX_FETCH_BYTES)
                val raw = String(bytes, Charsets.UTF_8)
                val text = if (type.contains("html") || raw.trimStart().startsWith("<")) htmlToText(raw) else raw
                val clipped = if (text.length > MAX_FETCH_CHARS) text.take(MAX_FETCH_CHARS) + "\n…[truncated]" else text
                return ToolResult(true, "URL: $url\n\n$clipped")
            }
        }
    }

    private fun search(query: String): ToolResult {
        val q = query.trim()
        if (q.isEmpty()) return ToolResult(false, "Empty query.")
        if (q.length > MAX_QUERY_CHARS) return ToolResult(false, "Query too long (${q.length} chars, limit $MAX_QUERY_CHARS).")
        val url = HttpUrl.Builder().scheme("https").host("html.duckduckgo.com").addPathSegments("html/")
            .addQueryParameter("q", q).build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val html = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return ToolResult(false, "Search failed: HTTP ${r.code}")
            String(readCapped(r.body?.byteStream() ?: return ToolResult(false, "Empty body."), MAX_FETCH_BYTES), Charsets.UTF_8)
        }
        val links = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        if (links.isEmpty()) return ToolResult(false, "No results (the search endpoint may be rate-limiting).")
        val out = links.take(MAX_SEARCH_RESULTS).mapIndexed { i, m ->
            val href = decodeDuckLink(m.groupValues[1].replace("&amp;", "&"))
            val title = htmlToText(m.groupValues[2]).replace('\n', ' ').trim()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.let { htmlToText(it).replace('\n', ' ').trim() }.orEmpty()
            "${i + 1}. $title\n   $href" + if (snippet.isNotBlank()) "\n   $snippet" else ""
        }
        return ToolResult(true, out.joinToString("\n\n"))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Connect-time DNS filter: private/local answers are dropped, never connected to, and the
     * lookup fails only when NO public answer remains. (Filtering instead of fail-on-any: a
     * split-DNS or VPN resolver may legitimately return a stray non-public record alongside the
     * public one; the rebinding protection is preserved because a private address can still
     * never reach the socket.)
     */
    internal object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val public = Dns.SYSTEM.lookup(hostname).filter { isPublicAddress(it) }
            if (public.isEmpty()) throw UnknownHostException("Refusing to contact a private or local address.")
            return public
        }
    }

    internal fun checkPublicHttps(url: HttpUrl) {
        require(url.isHttps) { "Only https:// URLs are allowed." }
        val host = url.host
        val addresses = resolver(host)
        require(addresses.isNotEmpty()) { "Host did not resolve." }
        require(addresses.any { isPublicAddress(it) }) { "Refusing to contact a private or local address." }
    }

    private fun decodeDuckLink(href: String): String {
        val encoded = href.substringAfter("uddg=", "").substringBefore('&')
        return if (encoded.isNotEmpty()) runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href) else href
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        input.use { s ->
            while (out.size() < max) {
                val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    internal fun htmlToText(html: String): String {
        var t = html
        t = Regex("(?is)<(script|style|noscript|svg|head)\\b.*?</\\1>").replace(t, " ")
        t = Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|section|article)>").replace(t, "\n")
        t = Regex("<[^>]+>").replace(t, " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        t = Regex("[ \\t\\u00A0]+").replace(t, " ")
        t = Regex("\\s*\\n\\s*").replace(t, "\n")
        return t.trim()
    }

    companion object {
        const val MAX_REDIRECTS = 4
        const val MAX_FETCH_BYTES = 1_500_000
        // H-02 (external audit, 2026-11-05): fetch/search stay ungated by
        // approval (web access is an explicit, default-off user setting
        // with SSRF + redirect guards), but the request itself is now
        // size-bounded: an over-long URL or query is overwhelmingly a
        // data-exfiltration shape, not a research request, and servers
        // reject such URLs anyway.
        const val MAX_URL_CHARS = 2048
        const val MAX_QUERY_CHARS = 512
        const val MAX_FETCH_CHARS = 40_000
        const val MAX_READ_CHARS = 60_000
        const val MAX_LIST_ENTRIES = 300
        const val MAX_LIST_SUMMARY_DIRS = 150
        const val MAX_SEARCH_RESULTS = 8
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Anvex"
    }
}

/** True only for globally routable unicast addresses (no loopback/private/link-local/ULA/CGNAT/multicast). */
internal fun isPublicAddress(a: InetAddress): Boolean {
    val bytes = a.address
    // NAT64 (64:ff9b::/96) and 6to4 (2002::/16) embed the real IPv4 target; judge the embedded
    // address instead of blanket-blocking the translation ranges (v4.14.5 — a blanket ban broke
    // every fetch for users on DNS64/NAT64 carriers). The recursion terminates: the embedded
    // address is IPv4, so it cannot re-enter this branch.
    if (bytes.size == 16) {
        // Byte comparisons must mask with 0xFF: Byte is signed, so 0xFF.toInt() is -1, not 255
        // (the pre-4.14.5 NAT64 test compared unmasked and could never match).
        val nat64 = (bytes[0].toInt() and 0xFF) == 0 && (bytes[1].toInt() and 0xFF) == 0x64 &&
            (bytes[2].toInt() and 0xFF) == 0xFF && (bytes[3].toInt() and 0xFF) == 0x9B &&
            (4..11).all { bytes[it].toInt() == 0 }
        if (nat64) return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        val sixToFour = (bytes[0].toInt() and 0xFF) == 0x20 && (bytes[1].toInt() and 0xFF) == 0x02
        if (sixToFour) return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(2, 6)))
    }
    val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    val cgnat = bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 0x40
    // v4.10.1 (BUG-16): ranges the JDK predicates miss — 0.0.0.0/8 (only the
    // exact 0.0.0.0 was covered), 192.0.0.0/24 and 240.0.0.0/4 (reserved, incl.
    // the limited broadcast).
    // v4.14.5: 198.18.0.0/15 is NO LONGER blocked. It is the standard Fake-IP pool of
    // sing-box/Clash-style VPNs (the default fakeip range): behind such a VPN every public
    // hostname resolves into 198.18/15, and the VPN core maps the placeholder back to the real
    // host — blocking the range refused 100% of agent fetches while protecting nothing, since
    // no real LAN device is reachable at a benchmarking address off-tunnel.
    val v4Special = bytes.size == 4 && (
        (bytes[0].toInt() and 0xFF) == 0 ||
            ((bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 0 && (bytes[2].toInt() and 0xFF) == 0) ||
            ((bytes[0].toInt() and 0xF0) == 240)
        )
    // IPv4-mapped IPv6 (::ffff:a.b.c.d) is already unwrapped by InetAddress into Inet4Address.
    return !(a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
        a.isSiteLocalAddress || a.isMulticastAddress || uniqueLocalV6 || cgnat || v4Special)
}
