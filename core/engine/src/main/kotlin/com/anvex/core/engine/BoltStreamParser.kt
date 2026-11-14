package com.anvex.core.engine

/**
 * Faithful Kotlin port of bolt.diy `StreamingMessageParser`
 * (upstream: app/lib/runtime/message-parser.ts, MIT License,
 * Copyright (c) 2024 StackBlitz, Inc. and bolt.diy contributors).
 *
 * Incremental, chunk-tolerant state machine over the `<boltArtifact>/<boltAction>`
 * streaming protocol. `parse()` is called with the FULL accumulated message text;
 * an internal per-message cursor makes re-parsing O(new content).
 *
 * Deviations from upstream (documented, deliberate):
 *  - the HTML "artifact element" injected into output is dropped (Compose UI renders
 *    artifacts as cards, not HTML), so artifact bodies are stripped from prose;
 *  - `<bolt-quick-actions>` are surfaced via [ParserCallbacks.onQuickActions] and
 *    stripped from prose;
 *  - unknown action types do not throw (upstream throws for invalid supabase ops).
 */
class BoltStreamParser(private val callbacks: ParserCallbacks) {

    private class MessageState {
        var position = 0
        var insideArtifact = false
        var insideAction = false
        var artifactCounter = 0
        var currentArtifact: AnvexArtifactData? = null
        var currentAction: BoltAction = BoltAction.ShellAction("")
        var actionId = 0
        /** Absolute index into the full message text where the open action's content begins (-1 = none). */
        var actionContentStart = -1

        /** F6: the open action's raw content, appended with only the new suffix per parse call. */
        val contentBuilder = StringBuilder()

        /** Absolute input index up to which content has been appended to [contentBuilder]. */
        var contentAppendedUpTo = -1
    }

    private val messages = HashMap<String, MessageState>()

    /**
     * Test instrumentation for the F6 perf budget: total characters the
     * parser copies while PROCESSING an open action's content (substring
     * extractions and whole-content transforms on the accumulated content).
     * The immutable full-content String handed to onActionStream is mandated
     * by the frozen callback contract and is not counted here.
     */
    internal var totalContentCharsCopied: Long = 0

    /**
     * Test instrumentation, item 5: total characters of content payloads
     * handed to [ParserCallbacks.onActionStream]. Reported alongside
     * [totalContentCharsCopied] (processing copies) but NEVER asserted:
     * each payload is the full accumulated content by contract, so this
     * number is inherently quadratic in the action's length.
     */
    internal var totalStreamPayloadChars: Long = 0

    fun parse(messageId: String, input: String): String {
        var state = messages[messageId]
        if (state == null) {
            state = MessageState()
            messages[messageId] = state
        }
        val st = state

        val output = StringBuilder()
        var i = st.position
        var earlyBreak = false

        while (i < input.length) {
            // ---- F1 tag hold-back ----------------------------------------------
            // If the unparsed tail is a strict prefix of a known tag opener,
            // stop and wait for more input — never emit a partial tag as prose
            // and never advance the cursor past it. (Inside an action the tail
            // is content, not markup, so the check does not apply there.)
            if (!st.insideAction && input[i] == '<' && tailIsStrictPrefixOfKnownOpener(input, i)) {
                break
            }

            // ---- <bolt-quick-actions> block ------------------------------------
            if (!st.insideAction && input.startsWith(QUICK_ACTIONS_OPEN, i)) {
                val blockEnd = input.indexOf(QUICK_ACTIONS_CLOSE, i)
                if (blockEnd != -1) {
                    val block = input.substring(i + QUICK_ACTIONS_OPEN.length, blockEnd)
                    callbacks.onQuickActions(parseQuickActions(block))
                    i = blockEnd + QUICK_ACTIONS_CLOSE.length
                    continue
                }
                // Open tag complete but the block is still streaming in: wait
                // for the rest instead of leaking the tag into prose.
                break
            }

            if (st.insideArtifact) {
                val artifact = st.currentArtifact ?: error("Artifact not initialized")

                if (st.insideAction) {
                    val closeIndex = input.indexOf(ACTION_TAG_CLOSE, i)
                    if (closeIndex != -1) {
                        // A previous chunk may have appended a partial close
                        // tag as content (it was indistinguishable from
                        // content then); hand those characters back.
                        if (st.contentAppendedUpTo > closeIndex) {
                            st.contentBuilder.setLength(
                                st.contentBuilder.length - (st.contentAppendedUpTo - closeIndex)
                            )
                            st.contentAppendedUpTo = closeIndex
                        }
                        appendContentSuffix(st, input, closeIndex)
                        val action = st.currentAction
                        val raw = st.contentBuilder.toString()
                        totalContentCharsCopied += raw.length
                        val finished = finishClosedAction(action, raw, (st.actionId - 1).toString())
                        st.currentAction = finished
                        callbacks.onActionClose(
                            ActionCallbackData(messageId, artifact.id, (st.actionId - 1).toString(), finished)
                        )
                        st.insideAction = false
                        st.currentAction = BoltAction.ShellAction("")
                        st.actionContentStart = -1
                        st.contentBuilder.setLength(0)
                        st.contentAppendedUpTo = -1
                        i = closeIndex + ACTION_TAG_CLOSE.length
                    } else {
                        val appended = appendContentSuffix(st, input, input.length)
                        val action = st.currentAction
                        if (action is BoltAction.FileAction && appended > 0) {
                            // F4: NO normalization while streaming — the raw
                            // accumulated content, minus a trailing partial
                            // close tag that may complete on the next chunk.
                            val displayLen = st.contentBuilder.length - partialCloseTailLength(st.contentBuilder)
                            val content = if (displayLen == st.contentBuilder.length) {
                                st.contentBuilder.toString()
                            } else {
                                st.contentBuilder.substring(0, displayLen)
                            }
                            totalStreamPayloadChars += content.length
                            callbacks.onActionStream(
                                ActionCallbackData(
                                    messageId, artifact.id, (st.actionId - 1).toString(),
                                    withContent(action, content)
                                )
                            )
                        }
                        break
                    }
                } else {
                    val actionOpenIndex = input.indexOf(ACTION_TAG_OPEN, i)
                    val artifactCloseIndex = input.indexOf(ARTIFACT_TAG_CLOSE, i)
                    if (actionOpenIndex != -1 &&
                        (artifactCloseIndex == -1 || actionOpenIndex < artifactCloseIndex)
                    ) {
                        val actionEndIndex = input.indexOf('>', actionOpenIndex)
                        if (actionEndIndex != -1) {
                            st.insideAction = true
                            st.currentAction = parseActionTag(input, actionOpenIndex, actionEndIndex)
                            st.actionContentStart = actionEndIndex + 1
                            st.contentBuilder.setLength(0)
                            st.contentAppendedUpTo = actionEndIndex + 1
                            callbacks.onActionOpen(
                                ActionCallbackData(messageId, artifact.id, st.actionId.toString(), st.currentAction)
                            )
                            st.actionId++
                            i = actionEndIndex + 1
                        } else {
                            break
                        }
                    } else if (artifactCloseIndex != -1) {
                        callbacks.onArtifactClose(artifact)
                        st.insideArtifact = false
                        st.currentArtifact = null
                        i = artifactCloseIndex + ARTIFACT_TAG_CLOSE.length
                    } else {
                        break
                    }
                }
            } else if (input[i] == '<' && artifactTagMightFollow(input, i)) {
                // ---- potential artifact open tag --------------------------------
                // Upstream JS checks `input[i + 1] !== '/'` where an out-of-range
                // index yields undefined (!== '/'), so a '<' at the very END of
                // the accumulated input still enters this branch and waits for
                // more. The v1.0 Kotlin port guarded with `i + 1 < input.length`,
                // which made a trailing '<' fall through to prose and SKIP the
                // artifact entirely — broken for SSE chunks that split a tag
                // exactly before its first character (proved by the v1.1
                // ChatSession char-by-char stream test). artifactTagMightFollow
                // restores upstream semantics.
                var j = i
                val potential = StringBuilder()
                var matchedBreak = false
                while (j < input.length && potential.length < ARTIFACT_TAG_OPEN.length) {
                    potential.append(input[j])
                    if (potential.toString() == ARTIFACT_TAG_OPEN) {
                        val nextChar = if (j + 1 < input.length) input[j + 1] else null
                        if (nextChar != null && nextChar != '>' && nextChar != ' ') {
                            // e.g. <boltArtifactFoo> — plain text
                            output.append(input.substring(i, j + 1))
                            i = j + 1
                            matchedBreak = true
                            break
                        }
                        val openTagEnd = input.indexOf('>', j)
                        if (openTagEnd != -1) {
                            val artifactTag = input.substring(i, openTagEnd + 1)
                            val artifactAttrs = parseAttributes(artifactTag)
                            val title = artifactAttrs["title"] ?: ""
                            val type = artifactAttrs["type"]
                            // P0-4 fix (v1.1): respect the id attribute the model supplies
                            // (e.g. <boltArtifact id="project"> -> artifact.id == "project").
                            // The system prompt instructs models to reuse the prior identifier
                            // for updates, so a repeated id means artifact continuation/update.
                            // Upstream bolt.diy currently generates ids (its extraction is
                            // commented out upstream), but our engine contract (v1.1 §6/§7)
                            // requires compatibility with supplied ids. Blank/missing ids
                            // fall back to a generated one, exactly like upstream.
                            val suppliedId = artifactAttrs["id"]?.trim()
                            val artifactId = if (!suppliedId.isNullOrBlank()) sanitizeArtifactId(suppliedId)
                            else "$messageId-${st.artifactCounter}"
                            st.artifactCounter++
                            st.insideArtifact = true
                            val currentArtifact = AnvexArtifactData(artifactId, title, type)
                            st.currentArtifact = currentArtifact
                            callbacks.onArtifactOpen(currentArtifact)
                            i = openTagEnd + 1
                        } else {
                            earlyBreak = true
                        }
                        matchedBreak = true
                        break
                    } else if (!ARTIFACT_TAG_OPEN.startsWith(potential.toString())) {
                        output.append(input.substring(i, j + 1))
                        i = j + 1
                        matchedBreak = true
                        break
                    }
                    j++
                }
                if (!matchedBreak) {
                    // Reached end of input while still matching the prefix — wait for more.
                    if (j == input.length && ARTIFACT_TAG_OPEN.startsWith(potential.toString())) {
                        break
                    }
                }
            } else {
                output.append(input[i])
                i++
            }

            if (earlyBreak) break
        }

        st.position = i
        return output.toString()
    }

    fun reset() = messages.clear()

    /**
     * H-06: returns the still-open action at end-of-stream WITHOUT applying
     * it, and clears it. Callers use this when the stream ended truncated
     * or failed: a half-written file must never be applied as complete.
     * Only the file path is needed by callers (for the user-facing note).
     */
    fun takeOpenAction(messageId: String): BoltAction? {
        val st = messages[messageId] ?: return null
        if (!st.insideArtifact || !st.insideAction) return null
        val action = st.currentAction
        st.insideAction = false
        st.currentAction = BoltAction.ShellAction("")
        st.actionContentStart = -1
        st.contentBuilder.setLength(0)
        st.contentAppendedUpTo = -1
        return action
    }

    /**
     * Like [takeOpenAction] but returns the full callback data (artifact id +
     * action id) and, for a file action, the PARTIAL CONTENT recovered from
     * [fullText] (the complete message text the caller accumulated — the
     * parser itself retains only a cursor, never the text). The recovered
     * content is normalized like the close path (trim; escaped-tag cleanup
     * for non-Markdown files) EXCEPT the markdown-fence cleanout, which is
     * only valid on a complete document. Callers salvaging a truncated file
     * write use this to save the finished prefix instead of dropping the
     * whole action.
     */
    fun takeOpenActionData(messageId: String, fullText: String): ActionCallbackData? {
        val st = messages[messageId] ?: return null
        if (!st.insideArtifact || !st.insideAction) return null
        val artifact = st.currentArtifact ?: return null
        val action = st.currentAction
        val recovered = if (action is BoltAction.FileAction &&
            st.actionContentStart in 0..fullText.length
        ) {
            var content = fullText.substring(st.actionContentStart).trim()
            if (!action.filePath.endsWith(".md")) {
                content = cleanEscapedTags(content)
            }
            withContent(action, content)
        } else {
            action
        }
        st.insideAction = false
        st.currentAction = BoltAction.ShellAction("")
        st.actionContentStart = -1
        st.contentBuilder.setLength(0)
        st.contentAppendedUpTo = -1
        return ActionCallbackData(messageId, artifact.id, (st.actionId - 1).toString(), recovered)
    }

    /**
     * Flush any pending action at end-of-stream (upstream relies on </boltAction>).
     *
     * H-06 + F5: a stream that ends INSIDE an action (network cut,
     * `max_tokens` truncation) has produced an incomplete file or command.
     * The default is DROP — a half-written action is never applied unless
     * the caller explicitly opts in with [applyIncomplete] = true.
     *
     * SAFETY INVARIANT (Pack 01-AMEND item 3): an unterminated SHELL or
     * START action is NEVER applied by flush under ANY argument shape,
     * including the explicit opt-in — a truncated command must not run.
     * Only FILE actions (and other non-executing types) may be applied
     * under the opt-in. The invariant is pinned by
     * BoltStreamParserFlushInvariantTest.
     *
     * Return contract (Pack 01-AMEND C2): true ⟺ an action was APPLIED
     * (fired through onActionClose). A dropped pending action returns
     * false, as does having nothing pending. (Pre-amend, flush returned
     * true whenever an unterminated action was pending, applied or not;
     * no production caller reads the value — see the amend report.)
     */
    fun flush(messageId: String, applyIncomplete: Boolean = false): Boolean =
        flush(messageId, "", applyIncomplete)

    /**
     * [flush] with the complete message text, so a still-open FILE action's
     * content can be recovered from the text when the caller explicitly
     * opts into applying it. With no explicit flag the pending action is
     * dropped, never applied.
     */
    fun flush(messageId: String, fullText: String, applyIncomplete: Boolean = false): Boolean {
        val st = messages[messageId] ?: return false
        if (!st.insideArtifact || !st.insideAction) return false
        val action = st.currentAction
        if (!applyIncomplete || action is BoltAction.ShellAction || action is BoltAction.StartAction) {
            // DROP — the default, and the ONLY outcome for an unterminated
            // shell/start action even under the explicit opt-in (item 3).
            dropOpenAction(st)
            return false
        }
        val artifact = st.currentArtifact ?: return false
        val raw = recoveredRawContent(st, action, fullText)
        val finished = finishClosedAction(action, raw, (st.actionId - 1).toString())
        callbacks.onActionClose(
            ActionCallbackData(messageId, artifact.id, (st.actionId - 1).toString(), finished)
        )
        dropOpenAction(st)
        return true
    }

    /** Clears the still-open action without applying it. */
    private fun dropOpenAction(st: MessageState) {
        st.insideAction = false
        st.currentAction = BoltAction.ShellAction("")
        st.actionContentStart = -1
        st.contentBuilder.setLength(0)
        st.contentAppendedUpTo = -1
    }

    /**
     * Raw content of the open action: for a FILE action, recovered from
     * [fullText] when the caller supplied it (the pre-builder contract);
     * otherwise the accumulated builder content.
     */
    private fun recoveredRawContent(st: MessageState, action: BoltAction, fullText: String): String {
        return if (action is BoltAction.FileAction && fullText.isNotEmpty() &&
            st.actionContentStart in 0..fullText.length
        ) {
            fullText.substring(st.actionContentStart)
        } else {
            st.contentBuilder.toString()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun withContent(action: BoltAction, content: String): BoltAction = when (action) {
        is BoltAction.FileAction -> action.copy(content = content)
        is BoltAction.ShellAction -> action.copy(content = content)
        is BoltAction.StartAction -> action.copy(content = content)
        is BoltAction.ArtifactAction -> action.copy(content = content)
        is BoltAction.ToolAction -> action.copy(content = content)
        is BoltAction.UnknownAction -> action.copy(content = content)
    }

    private fun parseActionTag(input: String, open: Int, end: Int): BoltAction {
        val tag = input.substring(open, end + 1)
        val attrs = parseAttributes(tag)
        val type = attrs["type"] ?: ""
        return when (type) {
            // F3: filePath wins; path is an accepted alias (bolt.diy emits both).
            "file" -> BoltAction.FileAction(
                "",
                attrs["filepath"] ?: attrs["path"] ?: "",
                append = attrs["append"] == "true",
            )
            "shell" -> BoltAction.ShellAction("")
            "start" -> BoltAction.StartAction("")
            "artifact" -> {
                val format = attrs["format"] ?: "zip"
                val outputName = attrs["outputname"] ?: "download"
                val paths = (attrs["paths"] ?: "").split("|").map { it.trim() }.filter { it.isNotBlank() }
                val title = attrs["title"] ?: ""
                // v4.6 (media): render parameters for format="mp4" — invalid or
                // missing values stay 0 and the renderer applies its defaults.
                val source = attrs["source"] ?: ""
                val durationSec = attrs["duration"]?.toIntOrNull() ?: 0
                val fps = attrs["fps"]?.toIntOrNull() ?: 0
                val width = attrs["width"]?.toIntOrNull() ?: 0
                val height = attrs["height"]?.toIntOrNull() ?: 0
                BoltAction.ArtifactAction("", format, outputName, paths, title, source, durationSec, fps, width, height)
            }
            "mcp" -> BoltAction.ToolAction(
                type,
                McpToolTarget.pack(attrs["server"] ?: "", attrs["tool"] ?: ""),
            )
            "read" -> {
                // Paged reads (2026-11-06): offset/limit let the model walk a
                // file larger than one read window instead of re-getting the
                // same head forever. Invalid values fall back to 0 (= start /
                // default window), never to a failed parse.
                val argument = attrs["path"]
                    ?: attrs["filepath"]
                    ?: ""
                val offset = attrs["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = attrs["limit"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                BoltAction.ToolAction(type, argument, offset = offset, limit = limit)
            }
            "fetch", "search", "list", "search_code", "extract_archive" -> {
                val argument = attrs["url"]
                    ?: attrs["query"]
                    ?: attrs["path"]
                    ?: attrs["filepath"]
                    ?: ""
                BoltAction.ToolAction(type, argument)
            }
            else -> BoltAction.UnknownAction(type, "")
        }
    }

    private fun parseQuickActions(block: String): List<QuickAction> {
        val result = ArrayList<QuickAction>()
        var idx = 0
        while (true) {
            val open = block.indexOf(QUICK_ACTION_ITEM_OPEN, idx)
            if (open == -1) break
            val openEnd = block.indexOf('>', open)
            if (openEnd == -1) break
            val close = block.indexOf(QUICK_ACTION_ITEM_CLOSE, openEnd)
            if (close == -1) break
            val attrs = block.substring(open, openEnd + 1)
            val label = block.substring(openEnd + 1, close).trim()
            result.add(
                QuickAction(
                    label = label,
                    type = extractAttribute(attrs, "type") ?: "",
                    message = extractAttribute(attrs, "message") ?: "",
                    path = extractAttribute(attrs, "path") ?: "",
                    href = extractAttribute(attrs, "href") ?: "",
                )
            )
            idx = close + QUICK_ACTION_ITEM_CLOSE.length
        }
        return result
    }

    private fun extractAttribute(tag: String, name: String): String? =
        parseAttributes(tag)[name.lowercase()]

    /**
     * F2: a real attribute tokenizer (replaces the old `name="..."` regex,
     * which had no word boundaries — `data-path` matched `path` — and only
     * understood double-quoted values). Names are whole tokens, matched
     * case-insensitively (keys are stored lowercased); values may be
     * double-quoted, single-quoted, or bare. `&quot;`, `&apos;` and `&amp;`
     * are decoded inside attribute values only, `&amp;` last so an escaped
     * `&amp;quot;` stays literal. First occurrence of a name wins.
     */
    private fun parseAttributes(tag: String): Map<String, String> {
        val result = HashMap<String, String>()
        var i = 0
        val n = tag.length
        // Skip '<' and the tag name itself.
        if (i < n && tag[i] == '<') i++
        while (i < n && !tag[i].isWhitespace() && tag[i] != '>' && tag[i] != '/') i++
        while (i < n) {
            while (i < n && (tag[i].isWhitespace() || tag[i] == '/')) i++
            if (i >= n || tag[i] == '>') break
            val nameStart = i
            while (i < n && tag[i] != '=' && !tag[i].isWhitespace() && tag[i] != '>' && tag[i] != '/') i++
            val name = tag.substring(nameStart, i).lowercase()
            while (i < n && tag[i].isWhitespace()) i++
            var value: String? = null
            if (i < n && tag[i] == '=') {
                i++
                while (i < n && tag[i].isWhitespace()) i++
                if (i < n && (tag[i] == '"' || tag[i] == '\'')) {
                    val quote = tag[i]
                    i++
                    val valueStart = i
                    while (i < n && tag[i] != quote) i++
                    value = tag.substring(valueStart, i)
                    if (i < n) i++ // closing quote
                } else {
                    val valueStart = i
                    while (i < n && !tag[i].isWhitespace() && tag[i] != '>' && tag[i] != '/') i++
                    value = tag.substring(valueStart, i)
                }
            }
            if (name.isNotEmpty() && value != null && name !in result) {
                result[name] = decodeAttributeValue(value)
            }
        }
        return result
    }

    private fun decodeAttributeValue(value: String): String {
        if (!value.contains('&')) return value
        return value
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    // ------------------------------------------------------------ F1 helpers

    /**
     * F1: true when the tail of [input] starting at [i] is a strict prefix
     * of one of the known tag openers and runs to the end of the input —
     * the rest of the tag may still arrive with the next chunk.
     */
    private fun tailIsStrictPrefixOfKnownOpener(input: String, i: Int): Boolean {
        val remaining = input.length - i
        for (opener in KNOWN_OPENERS) {
            if (remaining < opener.length && input.regionMatches(i, opener, 0, remaining)) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------ F6 helpers

    /**
     * Appends only the not-yet-appended content suffix (from the open
     * action's content start up to [until]) to the content builder.
     * Returns the number of characters appended this call.
     */
    private fun appendContentSuffix(st: MessageState, input: String, until: Int): Int {
        val from = st.contentAppendedUpTo
        if (from < 0 || until <= from) return 0
        val suffix = input.substring(from, until)
        st.contentBuilder.append(suffix)
        totalContentCharsCopied += suffix.length
        st.contentAppendedUpTo = until
        return suffix.length
    }

    /** Length of the builder's trailing strict prefix of the action close tag (0 = none). */
    private fun partialCloseTailLength(builder: StringBuilder): Int {
        val maxCheck = minOf(ACTION_TAG_CLOSE.length - 1, builder.length)
        for (len in maxCheck downTo 1) {
            var matches = true
            for (k in 0 until len) {
                if (builder[builder.length - len + k] != ACTION_TAG_CLOSE[k]) {
                    matches = false
                    break
                }
            }
            if (matches) return len
        }
        return 0
    }

    // ------------------------------------------------------------ F3/F4 close path

    /**
     * Produces the action object for an onActionClose: the F3 typed failure
     * for a file action with no path, otherwise the action with its content
     * normalized per F4 (or, for a salvage flush, the already-final prefix).
     */
    private fun finishClosedAction(
        action: BoltAction,
        rawContent: String,
        actionId: String,
        salvaged: Boolean = false,
    ): BoltAction {
        if (action is BoltAction.FileAction && action.filePath.isBlank()) {
            return BoltAction.UnknownAction(FAILURE_MISSING_PATH, rawContent)
        }
        val content = if (salvaged) rawContent else normalizeClosedContent(action, rawContent, actionId)
        return withContent(action, content)
    }

    /**
     * F4: content normalization happens HERE, at close, never while
     * streaming. Default is verbatim: the raw body minus the single newline
     * that separates it from the opening tag, with the single trailing
     * newline policy applied to file actions. Two narrow exceptions:
     *  - a whole-document markdown fence is removed, unless the extension
     *    is a documentation format (.md .mdx .txt .rst);
     *  - a markup file (.html .htm .xml .svg .jsx .tsx .vue) whose content
     *    contains zero raw '<' and '>' and at least one &lt;/&gt; entity is
     *    decoded exactly once (&lt; &gt; first, &amp; last) and the decode
     *    is reported via onContentNormalized.
     */
    private fun normalizeClosedContent(action: BoltAction, raw: String, actionId: String): String {
        var content = stripOneLeadingNewline(raw)
        if (action is BoltAction.FileAction) {
            val ext = action.filePath.substringAfterLast('.', "").lowercase()
            if (ext !in DOC_EXTENSIONS && startsWithFence(content)) {
                val cleaned = cleanoutMarkdownSyntax(content)
                if (cleaned != content) {
                    totalContentCharsCopied += cleaned.length
                    content = cleaned
                }
            }
            if (ext in MARKUP_EXTENSIONS && !content.contains('<') && !content.contains('>') &&
                (content.contains("&lt;") || content.contains("&gt;"))
            ) {
                content = content
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                totalContentCharsCopied += content.length
                callbacks.onContentNormalized(actionId, NORMALIZED_REASON_FULLY_ESCAPED)
            }
            val stripped = stripTrailingNewlines(content)
            totalContentCharsCopied += stripped.length
            return stripped + "\n"
        }
        return stripTrailingNewlines(content)
    }

    /** True when the first non-whitespace characters are a code fence. */
    private fun startsWithFence(content: String): Boolean {
        var i = 0
        while (i < content.length && content[i].isWhitespace()) i++
        return content.startsWith("```", i)
    }

    /** Removes the single newline separating an action body from its opening tag, if present. */
    private fun stripOneLeadingNewline(raw: String): String = when {
        raw.startsWith("\r\n") -> raw.substring(2)
        raw.startsWith("\n") || raw.startsWith("\r") -> raw.substring(1)
        else -> raw
    }

    /** Removes trailing '\n'/'\r' characters only — spaces are content and stay. */
    private fun stripTrailingNewlines(content: String): String {
        var end = content.length
        while (end > 0 && (content[end - 1] == '\n' || content[end - 1] == '\r')) end--
        return if (end == content.length) content else content.substring(0, end)
    }

    /**
     * Upstream-faithful gate for entering artifact-tag matching: true when the
     * next char is anything other than '/', INCLUDING the end-of-input case
     * (JS: undefined !== '/'). This is what makes an SSE chunk boundary that
     * lands exactly before '<boltArtifact' still work.
     */
    private fun artifactTagMightFollow(input: String, i: Int): Boolean {
        if (i + 1 >= input.length) return true // '<' is the last char — wait for more
        return input[i + 1] != '/'
    }

    /**
     * Keep supplied artifact ids safe for internal use as map keys and for the
     * ActionCallbackData contract: bounded length, kebab-ish charset. Keeps
     * upstream ids like "project" or "expense-tracker-dashboard" verbatim.
     */
    private fun sanitizeArtifactId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("""[^A-Za-z0-9._-]"""), "-").take(64)
        return cleaned.ifBlank { "artifact" }
    }

    /** Exposed for tests: id the parser would assign for a given opening tag. */
    internal fun artifactIdForTag(artifactTag: String, messageId: String, counter: Int): String {
        val supplied = extractAttribute(artifactTag, "id")?.trim()
        return if (!supplied.isNullOrBlank()) sanitizeArtifactId(supplied) else "$messageId-$counter"
    }

    companion object {
        private const val ARTIFACT_TAG_OPEN = "<boltArtifact"
        private const val ARTIFACT_TAG_CLOSE = "</boltArtifact>"
        private const val ACTION_TAG_OPEN = "<boltAction"
        private const val ACTION_TAG_CLOSE = "</boltAction>"
        private const val QUICK_ACTIONS_OPEN = "<bolt-quick-actions>"
        private const val QUICK_ACTIONS_CLOSE = "</bolt-quick-actions>"
        private const val QUICK_ACTION_ITEM_OPEN = "<bolt-quick-action"
        private const val QUICK_ACTION_ITEM_CLOSE = "</bolt-quick-action>"

        /** F1: every tag opener a partial tail may be a strict prefix of. */
        private val KNOWN_OPENERS = listOf(
            ARTIFACT_TAG_OPEN, ACTION_TAG_OPEN, QUICK_ACTIONS_OPEN, QUICK_ACTION_ITEM_OPEN,
        )

        /** F3: typed failure emitted at close when a file action has no path at all. */
        const val FAILURE_MISSING_PATH = "file-missing-path"

        /** T8/F4: reason reported when a fully-escaped markup file is decoded at close. */
        const val NORMALIZED_REASON_FULLY_ESCAPED = "fully-escaped-markup"

        /** F4: documentation formats never get the markdown-fence cleanout. */
        private val DOC_EXTENSIONS = setOf("md", "mdx", "txt", "rst")

        /** F4: markup formats eligible for the fully-escaped decode exception. */
        private val MARKUP_EXTENSIONS = setOf("html", "htm", "xml", "svg", "jsx", "tsx", "vue")

        /** Port of upstream `cleanoutMarkdownSyntax` (remove wrapping code fence). */
        internal fun cleanoutMarkdownSyntax(content: String): String {
            val m = Regex("""^\s*```(\w*)\n([\s\S]*?)\n\s*```\s*$""").find(content)
            return m?.groupValues?.get(2) ?: content
        }

        /** Port of upstream `cleanEscapedTags` (unconditional, like bolt.diy). */
        internal fun cleanEscapedTags(content: String): String {
            return content.replace("&lt;", "<").replace("&gt;", ">")
        }
    }
}
