package com.anvex.core.android.server

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
 */
internal object LoopbackAuth {

    fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    private const val COOKIE_NAME = "anvex_loopback"

    sealed interface Check {
        /** Authorized; [path] is the request path with any token prefix stripped. */
        data class Granted(val path: String, val viaTokenPath: Boolean) : Check
        object Denied : Check
    }

    fun check(rawPath: String, cookieHeader: String?, token: String): Check {
        val prefix = "/t/$token"
        if (rawPath == prefix || rawPath.startsWith("$prefix/")) {
            return Check.Granted(rawPath.removePrefix(prefix).ifEmpty { "/" }, viaTokenPath = true)
        }
        val cookieOk = cookieHeader?.split(';')?.any { it.trim() == "$COOKIE_NAME=$token" } == true
        return if (cookieOk) Check.Granted(rawPath, viaTokenPath = false) else Check.Denied
    }

    /** Set-Cookie value that authorizes follow-up requests in this session. */
    fun cookieValue(token: String): String = "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict"
}
