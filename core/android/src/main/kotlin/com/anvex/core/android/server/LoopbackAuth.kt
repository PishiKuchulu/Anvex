package com.anvex.core.android.server

import java.security.MessageDigest
import java.util.UUID

/**
 * v4.10.1 (SEC-01): the loopback servers (preview + harness) bind to
 * 127.0.0.1 with a random port and a Host check — but the Host header
 * constrains the DESTINATION, not the caller: any other app on the
 * device could find the port and read workspace files (including .env).
 * Both servers now require a per-session token:
 *
 *  - the app's WebView loads `<base>/t/<token>/...` (the token path);
 *  - the first token-authorized response sets a session cookie, so
 *    later absolute-path requests from the same WebView also pass;
 *  - a request with neither is refused (403).
 *
 * The token lives only in memory and dies with the server instance.
 *
 * v4.10.2 (second review #11): the two servers used to share one
 * cookie name on the same host (127.0.0.1, Path=/), and cookies are
 * NOT port-scoped (RFC 6265) — the app's shared WebView CookieManager
 * meant the preview server's token overwrote the harness server's, so
 * a later absolute-path harness download could 403 with the other
 * server's token. Each server now has its own cookie name.
 * Token comparison is constant-time (MessageDigest.isEqual) as well.
 */
internal object LoopbackAuth {

    fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    const val PREVIEW_COOKIE = "anvex_preview"
    const val HARNESS_COOKIE = "anvex_harness"

    sealed interface Check {
        /** Authorized; [path] is the request path with any token prefix stripped. */
        data class Granted(val path: String, val viaTokenPath: Boolean) : Check
        object Denied : Check
    }

    fun check(rawPath: String, cookieHeader: String?, token: String, cookieName: String): Check {
        if (rawPath.startsWith("/t/")) {
            val rest = rawPath.removePrefix("/t/")
            val presented = rest.substringBefore('/')
            if (constantTimeEquals(presented, token)) {
                val path = rest.substringAfter('/', "").let { if (it.isEmpty()) "/" else "/$it" }
                return Check.Granted(path, viaTokenPath = true)
            }
        }
        val cookieOk = cookieHeader?.split(';')?.any {
            val part = it.trim()
            part.startsWith("$cookieName=") && constantTimeEquals(part.substringAfter('='), token)
        } == true
        return if (cookieOk) Check.Granted(rawPath, viaTokenPath = false) else Check.Denied
    }

    /** Set-Cookie value that authorizes follow-up requests in this session. */
    fun cookieValue(token: String, cookieName: String): String =
        "$cookieName=$token; Path=/; HttpOnly; SameSite=Strict"

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
