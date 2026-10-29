package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ThinkingLevel
import com.anvex.core.model.Usage
import kotlinx.serialization.Serializable

/** Provider config with the decrypted key — exists in memory only, never logged. */
data class ProviderRuntimeConfig(
    val config: ProviderConfig,
    val apiKey: String,
) {
    override fun toString(): String =
        "ProviderRuntimeConfig(provider=${config.name}, kind=${config.kind}, model=${config.model}, apiKey=${maskApiKey(apiKey)}, extraHeaderNames=${config.extraHeaders.keys})"
}

@Serializable
data class ChatTurn(val role: MessageRole, val content: String)

data class ChatRequest(
    val systemPrompt: String,
    val turns: List<ChatTurn>,
    val attachments: List<ChatAttachment> = emptyList(),
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.NORMAL,
    /** Phase 4.2: ask the provider to use its native web search (only Anthropic and Gemini honor it). */
    val webSearch: Boolean = false,
) {
    override fun toString(): String =
        "ChatRequest(systemPromptLength=${systemPrompt.length}, turns=${turns.size}, attachments=${attachments.size}, maxTokens=$maxTokens, temperature=$temperature, thinkingLevel=$thinkingLevel)"
}

/** A web page the provider consulted, reported with native search (Phase 4.2). */
data class WebSource(val title: String, val url: String)

sealed class StreamEvent {
    object Started : StreamEvent()
    /** v4.4 (architect feature 1): model reasoning / extended-thinking text, streamed before answer tokens. */
    data class Reasoning(val text: String) : StreamEvent()
    data class Token(val text: String) : StreamEvent()
    data class Completed(val finishReason: String? = null, val usage: Usage? = null) : StreamEvent()
    data class Failed(val error: AiError) : StreamEvent()
    data class Sources(val sources: List<WebSource>) : StreamEvent()
}

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val base64: String? = null,
    val text: String? = null,
    val isImage: Boolean = false,
) {
    override fun toString(): String =
        "ChatAttachment(name=$name, mimeType=$mimeType, base64Bytes=${base64?.length ?: 0}, textLength=${text?.length ?: 0}, isImage=$isImage)"
}

data class AiError(
    val kind: Kind,
    val message: String,
    val httpStatus: Int? = null,
    /** M-16: seconds from the HTTP `Retry-After` header, when the provider sent one. */
    val retryAfterSeconds: Long? = null,
) {
    enum class Kind {
        AUTH, ACCESS_RESTRICTED, RATE_LIMIT, NOT_FOUND, NETWORK, PROTOCOL, CANCELED, UNKNOWN
    }

    /** Human-readable title for UI (brief §23 — no raw stack traces). */
    val userTitle: String
        get() = when (kind) {
            Kind.AUTH -> "Invalid API key or access denied"
            // v4.4.1: upstream answered 403 — the request reached the service and was
            // refused for policy reasons (e.g. OpenCode FreeTierError), which is a
            // different user action from a bad key (401).
            Kind.ACCESS_RESTRICTED -> "Access restricted by the provider"
            Kind.RATE_LIMIT -> "Rate limit or quota exceeded"
            Kind.NOT_FOUND -> "Model or endpoint not found"
            Kind.NETWORK -> "Network error — check your connection"
            Kind.PROTOCOL -> "The provider returned an unexpected response"
            Kind.CANCELED -> "Request canceled"
            Kind.UNKNOWN -> "Request failed"
        }
}

data class ValidationResult(
    val ok: Boolean,
    val latencyMs: Long,
    val message: String,
    val modelsFound: Int = 0,
)
