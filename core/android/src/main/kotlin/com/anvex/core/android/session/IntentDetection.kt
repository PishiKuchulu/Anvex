package com.anvex.core.android.session

/**
 * Intent-without-action detection for the agent loop.
 *
 * Some models end a turn by only NARRATING the work ("I'll create the
 * files and then run the build") — a normal finish reason, zero emitted
 * actions — and the user has to type "continue" to unstick them. This
 * matcher spots that shape so [ChatSession] can push one continuation
 * round. It is deliberately narrow:
 *
 *  - short-ish prose only (<= 4000 chars): long texts are answers or
 *    reports, not plan announcements;
 *  - first-person intent phrasing, English or Persian;
 *  - callers combine it with "zero actions executed this turn", so a
 *    closing summary after real work can never match the branch.
 */
internal fun looksLikeIntentOnly(prose: String): Boolean {
    val text = prose.trim()
    if (text.isEmpty() || text.length > 4_000) return false
    // ZWNJ removal makes the Persian patterns insensitive to the two
    // spellings models produce (می‌کنم with or without the joiner).
    val flat = text.replace("\u200C", "").lowercase()
    return INTENT_PATTERNS.any { flat.contains(it) }
}

/**
 * True when a round's prose carries no actual answer: blank, or nothing
 * but harness/tool wrapper markers (`<tool_results>` and friends). Weak
 * gateway models sometimes echo the wrapper the harness uses for tool
 * results and then stop — the user sees a raw internal tag instead of a
 * reply. Markup MIXED with real prose is a legitimate answer and does
 * not match: the markers are stripped and only an empty remainder
 * counts.
 */
internal fun isMarkupOnlyOrEmpty(prose: String): Boolean {
    val flat = prose.lowercase()
    val stripped = HARNESS_MARKERS.fold(flat) { acc, marker -> acc.replace(marker, "") }
    return stripped.isBlank()
}

private val HARNESS_MARKERS = listOf(
    "<tool_results>", "</tool_results>",
    "<tool_result>", "</tool_result>",
    "<tool_call>", "</tool_call>",
    "<tool_calls>", "</tool_calls>",
)

private val INTENT_PATTERNS = listOf(
    // English first-person intent / plan announcements.
    "i will ",
    "i'll ",
    "i am going to",
    "i'm going to",
    "let me ",
    "i need to ",
    "i plan to",
    "my plan is",
    "here's my plan",
    "here is my plan",
    "first, i will",
    "now i will",
    "next, i will",
    "i should ",
    // Persian first-person intent (matched ZWNJ-free, see above).
    "انجام میدم",
    "انجام میدهم",
    "انجام خواهم داد",
    "میسازم",
    "مینویسم",
    "ایجاد میکنم",
    "شروع میکنم",
    "اضافه میکنم",
    "تغییر میدم",
    "میخواهم",
    "قرار است انجام",
    "باید اول",
)
