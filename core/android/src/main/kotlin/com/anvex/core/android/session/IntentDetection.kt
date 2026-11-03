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
 *    closing summary after real work can never match the branch;
 *  - callers ALSO gate on the user having actually requested execution
 *    ([com.anvex.core.engine.UserIntentClassifier.requiresExecution]):
 *    a plan-shaped sentence inside a plain Q&A answer must never turn
 *    into file actions the user did not ask for (audit 2026-11-03).
 *
 * English patterns come in two tiers. STRONG patterns are explicit plan
 * announcements and match anywhere. WEAK patterns are everyday answer
 * phrasing ("let me…", "I should…") and only match at the START of a
 * sentence, where they actually announce upcoming work instead of
 * explaining something.
 */
internal fun looksLikeIntentOnly(prose: String): Boolean {
    val text = prose.trim()
    if (text.isEmpty() || text.length > 4_000) return false
    // ZWNJ removal makes the Persian patterns insensitive to the two
    // spellings models produce (می‌کنم with or without the joiner).
    val flat = text.replace("\u200C", "").lowercase()
    if (INTENT_PATTERNS.any { flat.contains(it) }) return true
    // Weak first-person openers: only as the start of a sentence.
    return flat.split(SENTENCE_SPLIT).any { sentence ->
        val start = sentence.trim().trimStart('"', '\'', '(', '«', ' ')
        WEAK_SENTENCE_STARTS.any { start.startsWith(it) }
    }
}

private val SENTENCE_SPLIT = Regex("[.!?\\n…]+")

/** Everyday first-person phrasing — intent ONLY at a sentence start. */
private val WEAK_SENTENCE_STARTS = listOf(
    "i will",
    "i'll",
    "let me",
    "i need to",
    "i should",
)

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
    if (stripped.isBlank()) return true
    // JSON tool-call envelopes: some gateways answer with a bare
    // {"tool_calls": [...]} object as the entire round. That is a tool
    // call in a dialect the parser does not execute, not an answer —
    // but only when the WHOLE round is the envelope (a JSON example
    // inside prose stays an answer).
    val trimmed = flat.trim()
    return trimmed.startsWith("{") &&
        (trimmed.contains("\"tool_call\"") || trimmed.contains("\"tool_calls\"")) &&
        !trimmed.contains("boltartifact")
}

private val HARNESS_MARKERS = listOf(
    "<tool_results>", "</tool_results>",
    "<tool_result>", "</tool_result>",
    "<tool-result>", "</tool-result>",
    "<tool_call>", "</tool_call>",
    "<tool_calls>", "</tool_calls>",
    "<function_calls>", "</function_calls>",
    "<invoke>", "</invoke>",
)

/**
 * Strong markers of a tool call emitted in a dialect the bolt parser does
 * NOT understand — typically a gateway model's own scaffolding
 * (`<|open|>tools<|sep|> … call tool="boltArtifact" … argument key="id"`).
 * Such a round parses to zero actions, so without detection the turn ends
 * as if the model had simply answered, with the raw scaffolding left
 * visible in the message (user report, 2026-11-02). The markers are
 * deliberately exotic: ordinary prose or code discussion does not contain
 * them, and callers still gate on "zero actions executed".
 */
internal fun containsUnparsedToolMarkup(prose: String): Boolean {
    val flat = prose.lowercase()
    return UNPARSED_MARKUP_MARKERS.any { flat.contains(it) }
}

/** The round's prose with the alien tool scaffolding cut away (it is a tail in practice). */
internal fun stripUnparsedToolMarkup(prose: String): String {
    val flat = prose.lowercase()
    val cut = UNPARSED_MARKUP_MARKERS
        .map { flat.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: return prose
    return prose.substring(0, cut).trimEnd()
}

/**
 * [stripUnparsedToolMarkup] for USER-VISIBLE final text: when retained
 * prose was cut, a visible note says so — the plain strip silently
 * discarded everything after the earliest marker, and a reply that
 * legitimately quoted such a token early lost its tail with no sign
 * (audit 2026-11-03). Not for in-flight round cleaning: the note is
 * display text, not model input.
 */
internal fun stripUnparsedToolMarkupNoted(prose: String): String {
    val cleaned = stripUnparsedToolMarkup(prose)
    if (cleaned == prose || cleaned.isBlank()) return cleaned
    return cleaned + "\n\n[Part of the reply was removed: it contained internal tool-call markup.]"
}

private val UNPARSED_MARKUP_MARKERS = listOf(
    "<|sep|>", "<|open|>", "<|close|>", "<|tool",
    // The reported dialect OPENS with a bare "open|>" (no leading "<"):
    // "open|>tools<|sep|><|open|>call tool=…" — without this marker the
    // strip left "open|>tools" behind in the message (CI test failure).
    "open|>",
    "call tool=\"", "argument key=\"",
)

private val INTENT_PATTERNS = listOf(
    // English STRONG plan announcements (match anywhere). The weak
    // everyday openers ("i will", "i'll", "let me", "i need to",
    // "i should") are NOT here anymore — see WEAK_SENTENCE_STARTS.
    "i am going to",
    "i'm going to",
    "i plan to",
    "my plan is",
    "here's my plan",
    "here is my plan",
    "first, i will",
    "now i will",
    "next, i will",
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
    // From a real user-reported narration (2026-11-02) that matched nothing:
    // «مرحله‌به‌مرحله پیش می‌رم… بررسی می‌کنم… تحویل می‌دم».
    "پیش میرم",
    "بررسی میکنم",
    "تحویل میدم",
)
