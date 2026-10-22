package com.anvex.core.android.runtime.pi

import org.json.JSONObject

/**
 * Reduces Pi's JSONL event stream into chat-shaped state:
 *  - [streamText]: the in-flight assistant message's text (for the
 *    streaming bubble), reset at each new assistant message;
 *  - [statusLine]: the tool currently running, if any;
 *  - [finalText]: the text of the last COMPLETED assistant message —
 *    what gets persisted as the chat reply when the turn settles;
 *  - [errorText]: provider/agent error text when a message ends in an
 *    error stop reason.
 * Pure and deterministic — unit-tested on the JVM with canned records.
 */
class PiTranscriptReducer {

    var streamText: String = ""
        private set
    var statusLine: String? = null
        private set
    var finalText: String = ""
        private set
    var errorText: String? = null
        private set

    private val buffer = StringBuilder()
    private var inAssistantMessage = false

    fun onEvent(record: JSONObject) {
        when (record.optString("type")) {
            "message_start" -> {
                val role = record.optJSONObject("message")?.optString("role")
                inAssistantMessage = role == "assistant"
                if (inAssistantMessage) {
                    buffer.setLength(0)
                    streamText = ""
                }
            }
            "message_update" -> {
                if (!inAssistantMessage) return
                val ev = record.optJSONObject("assistantMessageEvent") ?: return
                if (ev.optString("type") == "text_delta") {
                    buffer.append(ev.optString("delta"))
                    streamText = buffer.toString()
                }
            }
            "message_end" -> {
                val message = record.optJSONObject("message") ?: return
                if (message.optString("role") != "assistant") return
                inAssistantMessage = false
                val text = extractText(message)
                if (text.isNotBlank()) finalText = text
                if (message.optString("stopReason") == "error") {
                    errorText = message.optString("errorMessage").ifBlank { "The provider reported an error." }
                }
                streamText = ""
                buffer.setLength(0)
            }
            "tool_execution_start" -> {
                statusLine = "Running ${record.optString("toolName")}…"
            }
            "tool_execution_end" -> {
                statusLine = null
            }
            "agent_settled" -> {
                statusLine = null
            }
        }
    }

    private fun extractText(message: JSONObject): String {
        val content = message.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(block.optString("text"))
            }
        }
        return sb.toString()
    }
}
