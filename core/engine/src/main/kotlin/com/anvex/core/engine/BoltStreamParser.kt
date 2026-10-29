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
    }

    private val messages = HashMap<String, MessageState>()

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
            // ---- <bolt-quick-actions> block ------------------------------------
            if (input.startsWith(QUICK_ACTIONS_OPEN, i)) {
                val blockEnd = input.indexOf(QUICK_ACTIONS_CLOSE, i)
                if (blockEnd != -1) {
                    val block = input.substring(i + QUICK_ACTIONS_OPEN.length, blockEnd)
                    callbacks.onQuickActions(parseQuickActions(block))
                    i = blockEnd + QUICK_ACTIONS_CLOSE.length
                    continue
                }
            }

            if (st.insideArtifact) {
                val artifact = st.currentArtifact ?: error("Artifact not initialized")

                if (st.insideAction) {
                    val closeIndex = input.indexOf(ACTION_TAG_CLOSE, i)
                    if (closeIndex != -1) {
                        val action = st.currentAction
                        val raw = input.substring(i, closeIndex)
                        var content = (action.content + raw).trim()
                        if (action is BoltAction.FileAction && !action.filePath.endsWith(".md")) {
                            content = cleanoutMarkdownSyntax(content)
                            content = cleanEscapedTags(content)
                        }
                        if (action is BoltAction.FileAction) content += "\n"
                        val finished = withContent(action, content)
                        st.currentAction = finished
                        callbacks.onActionClose(
                            ActionCallbackData(messageId, artifact.id, (st.actionId - 1).toString(), finished)
                        )
                        st.insideAction = false
                        st.currentAction = BoltAction.ShellAction("")
                        i = closeIndex + ACTION_TAG_CLOSE.length
                    } else {
                        val action = st.currentAction
                        if (action is BoltAction.FileAction) {
                            var content = input.substring(i)
                            if (!action.filePath.endsWith(".md")) {
                                content = cleanoutMarkdownSyntax(content)
                                content = cleanEscapedTags(content)
                            }
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
                            val title = extractAttribute(artifactTag, "title") ?: ""
                            val type = extractAttribute(artifactTag, "type")
                            // P0-4 fix (v1.1): respect the id attribute the model supplies
                            // (e.g. <boltArtifact id="project"> -> artifact.id == "project").
                            // The system prompt instructs models to reuse the prior identifier
                            // for updates, so a repeated id means artifact continuation/update.
                            // Upstream bolt.diy currently generates ids (its extraction is
                            // commented out upstream), but our engine contract (v1.1 §6/§7)
                            // requires compatibility with supplied ids. Blank/missing ids
                            // fall back to a generated one, exactly like upstream.
                            val suppliedId = extractAttribute(artifactTag, "id")?.trim()
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
        return action
    }

    /** Flush any pending action at end-of-stream (upstream relies on </boltAction>). */
    fun flush(messageId: String) {
        val st = messages[messageId] ?: return
        if (st.insideArtifact && st.insideAction) {
            val artifact = st.currentArtifact ?: return
            val action = st.currentAction
            var content = action.content.trim()
            if (action is BoltAction.FileAction && !action.filePath.endsWith(".md")) {
                content = cleanoutMarkdownSyntax(content)
                content = cleanEscapedTags(content)
            }
            if (action is BoltAction.FileAction) content += "\n"
            val finished = withContent(action, content)
            callbacks.onActionClose(
                ActionCallbackData(messageId, artifact.id, (st.actionId - 1).toString(), finished)
            )
            st.insideAction = false
            st.currentAction = BoltAction.ShellAction("")
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
        val type = extractAttribute(tag, "type") ?: ""
        return when (type) {
            "file" -> BoltAction.FileAction("", extractAttribute(tag, "filePath") ?: "")
            "shell" -> BoltAction.ShellAction("")
            "start" -> BoltAction.StartAction("")
            "artifact" -> {
                val format = extractAttribute(tag, "format") ?: "zip"
                val outputName = extractAttribute(tag, "outputName") ?: "download"
                val paths = (extractAttribute(tag, "paths") ?: "").split("|").map { it.trim() }.filter { it.isNotBlank() }
                val title = extractAttribute(tag, "title") ?: ""
                // v4.6 (media): render parameters for format="mp4" — invalid or
                // missing values stay 0 and the renderer applies its defaults.
                val source = extractAttribute(tag, "source") ?: ""
                val durationSec = extractAttribute(tag, "duration")?.toIntOrNull() ?: 0
                val fps = extractAttribute(tag, "fps")?.toIntOrNull() ?: 0
                val width = extractAttribute(tag, "width")?.toIntOrNull() ?: 0
                val height = extractAttribute(tag, "height")?.toIntOrNull() ?: 0
                BoltAction.ArtifactAction("", format, outputName, paths, title, source, durationSec, fps, width, height)
            }
            "mcp" -> BoltAction.ToolAction(
                type,
                McpToolTarget.pack(extractAttribute(tag, "server") ?: "", extractAttribute(tag, "tool") ?: ""),
            )
            "fetch", "search", "read", "list" -> {
                val argument = extractAttribute(tag, "url")
                    ?: extractAttribute(tag, "query")
                    ?: extractAttribute(tag, "path")
                    ?: extractAttribute(tag, "filePath")
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

    private fun extractAttribute(tag: String, name: String): String? {
        val pattern = Regex("""$name="([^"]*)"""", RegexOption.IGNORE_CASE)
        return pattern.find(tag)?.groupValues?.get(1)
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
