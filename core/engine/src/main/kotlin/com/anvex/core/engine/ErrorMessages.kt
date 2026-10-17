package com.anvex.core.engine

/**
 * Friendly, user-facing mapping for runtime/engine exceptions (v4.0 §17:
 * "never a raw stack trace"). Pre-existing ChatSession dependency that was
 * missing from the round-2 source; restored here so the session compiles.
 */
fun Exception.toUserMessage(): Pair<String, String> {
    val title = when {
        this is SecurityException -> "Blocked by the safety policy"
        this is IllegalArgumentException -> "Invalid request"
        this is java.io.IOException -> "Filesystem or network error"
        message?.contains("timeout", ignoreCase = true) == true -> "Timed out"
        else -> "Runtime error"
    }
    val detail = (message ?: this::class.simpleName ?: "no details")
        .replace(Regex("(?i)(api[_-]?key|bearer|token)[=: ]+[^\\s]+"), "$1=[redacted]")
        .take(300)
    return title to detail
}
