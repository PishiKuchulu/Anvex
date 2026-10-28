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
                    "read" -> read(action.argument, fs)
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

    private fun read(rawPath: String, fs: ProjectFileSystem): ToolResult {
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
        val clipped = if (text.length > MAX_READ_CHARS) text.take(MAX_READ_CHARS) + "\n…[truncated at $MAX_READ_CHARS chars]" else text
        return ToolResult(true, clipped)
    }

    private fun list(rawPath: String, fs: ProjectFileSystem): ToolResult {
        val prefix = rawPath.trim().trim('/').let { if (it == "." ) "" else it }
        val all = fs.walk().sorted().filter { prefix.isEmpty() || it == prefix || it.startsWith("$prefix/") }
        if (all.isEmpty()) return ToolResult(true, "(no files)")
        val shown = all.take(MAX_LIST_ENTRIES).joinToString("\n") { "$it (${fs.fileSize(it) ?: 0} bytes)" }
        val more = if (all.size > MAX_LIST_ENTRIES) "\n…and ${all.size - MAX_LIST_ENTRIES} more" else ""
        return ToolResult(true, shown + more)
    }

    // ------------------------------------------------------------------ web tools

    private fun fetch(rawUrl: String): ToolResult {
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

    /** Resolves through the system resolver but refuses any non-public result at connect time. */
    internal object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            for (a in all) {
                if (!isPublicAddress(a)) throw UnknownHostException("Refusing to contact a private or local address.")
            }
            return all
        }
    }

    internal fun checkPublicHttps(url: HttpUrl) {
        require(url.isHttps) { "Only https:// URLs are allowed." }
        val host = url.host
        val addresses = resolver(host)
        require(addresses.isNotEmpty()) { "Host did not resolve." }
        for (a in addresses) {
            require(isPublicAddress(a)) { "Refusing to contact a private or local address." }
        }
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
        const val MAX_FETCH_CHARS = 40_000
        const val MAX_READ_CHARS = 60_000
        const val MAX_LIST_ENTRIES = 300
        const val MAX_SEARCH_RESULTS = 8
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Anvex"
    }
}

/** True only for globally routable unicast addresses (no loopback/private/link-local/ULA/CGNAT/multicast). */
internal fun isPublicAddress(a: InetAddress): Boolean {
    val bytes = a.address
    val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    val cgnat = bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 0x40
    // v4.10.1 (BUG-16): ranges the JDK predicates miss — 0.0.0.0/8 (only the
    // exact 0.0.0.0 was covered), 192.0.0.0/24, 198.18.0.0/15 (benchmarking),
    // 240.0.0.0/4 (reserved, incl. the limited broadcast), NAT64 64:ff9b::/96
    // and 6to4 2002::/16 (both embed an IPv4 target that bypasses v4 checks).
    val v4Special = bytes.size == 4 && (
        (bytes[0].toInt() and 0xFF) == 0 ||
            ((bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 0 && (bytes[2].toInt() and 0xFF) == 0) ||
            ((bytes[0].toInt() and 0xFF) == 198 && (bytes[1].toInt() and 0xFE) == 18) ||
            ((bytes[0].toInt() and 0xF0) == 240)
        )
    val nat64 = bytes.size == 16 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0x64 &&
        bytes[2].toInt() == 0xFF && bytes[3].toInt() == 0x9B
    val sixToFour = bytes.size == 16 && bytes[0].toInt() == 0x20 && bytes[1].toInt() == 0x02
    // IPv4-mapped IPv6 (::ffff:a.b.c.d) is already unwrapped by InetAddress into Inet4Address.
    return !(a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
        a.isSiteLocalAddress || a.isMulticastAddress || uniqueLocalV6 || cgnat || v4Special || nat64 || sixToFour)
}
