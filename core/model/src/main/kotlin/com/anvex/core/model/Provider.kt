package com.anvex.core.model

import kotlinx.serialization.Serializable

/**
 * Provider kinds understood by the AI layer. Every kind maps to a wire protocol
 * adapter in :core:ai. CUSTOM_* entries are fully user-configurable endpoints.
 */
enum class ThinkingLevel(val label: String, val budgetTokens: Int) {
    LOW("Low", 2048),
    NORMAL("Normal", 4096),
    HIGH("High", 8192),
    XHIGH("XHigh", 16384),
    MAX("Max", 32768),
}

/**
 * v4.0 (GOAL 5): Anvex is cloud-provider-only. OLLAMA and LMSTUDIO are retained
 * as *deprecated, hidden* entries so persisted Room rows from v3.x still decode —
 * removing the constants would crash `ProviderKind.valueOf` on upgrade (DB stays at
 * schema v12 by the audit contract). They are no longer selectable in any UI and are
 * reported as unsupported when used.
 * v4.4.4: ANTIGRAVITY and CLINE were retired the same way (deprecated, hidden,
 * read-only for legacy rows) per product decision.
 */
enum class ProviderKind(val displayLabel: String, val supportsBaseUrlOverride: Boolean) {
    OPENAI("OpenAI", true),
    ANTHROPIC("Anthropic", true),
    GEMINI("Google Gemini", true),
    OPENROUTER("OpenRouter", false),
    DEEPSEEK("DeepSeek", false),
    @Deprecated("v4.0: local-model servers were removed; kept only to decode legacy rows.")
    OLLAMA("Ollama (legacy local server)", true),
    MISTRAL("Mistral", false),
    GROQ("Groq", false),
    @Deprecated("v4.0: local-model servers were removed; kept only to decode legacy rows.")
    LMSTUDIO("LM Studio (legacy local server)", true),
    CUSTOM_OPENAI("Custom (OpenAI-compatible)", true),
    CUSTOM_ANTHROPIC("Custom (Anthropic-compatible)", true),
    /** v4.4: Z.ai GLM Coding Plan — account login (ZCode CLI polling flow), no pasted API key. */
    ZAI_ACCOUNT("Z.ai (GLM Coding Plan)", false),
    /**
     * v4.4.1: OpenCode free tier — no credential, `-free`-suffixed models discovered live
     * from the public models endpoint. VERIFIED 2026-10-15: the chat gateway answers
     * HTTP 403 FreeTierError ("OpenCode's free tier can only be used from within
     * OpenCode") for any client that is not the official OpenCode app. Anvex does NOT
     * reproduce the official client fingerprint (research: opencode-free-9router does
     * exactly that, which is out of bounds here) — the 403 is surfaced verbatim instead.
     */
    OPENCODE_FREE("OpenCode Free", false),
    /**
     * v4.4.1: OpenCode Console (formerly "Zen") — API-key tier. Documented auth
     * (docs.opencode.ai, verified 2026-10-15): `Authorization: Bearer <console API key>`
     * against https://opencode.ai/inference/openai/v1/chat/completions; the models
     * endpoint validates the same key when present. Enum name kept from v4.4 so
     * persisted Room rows (kind stored as enum name) keep decoding.
     */
    OPENCODE_ZEN("OpenCode Console (API key)", false),
    /**
     * v4.4.4: RETIRED — removed from the product surface per product decision.
     * Kept as a deprecated constant ONLY so persisted Room rows (kind stored as
     * enum name) keep decoding — removing it would crash `ProviderKind.valueOf`
     * on upgrade (same contract as OLLAMA/LMSTUDIO). Research record:
     * docs/CLINE_INTEGRATION_REPORT.md.
     */
    @Deprecated("v4.4.4: Cline was removed from the product surface; kept only to decode legacy rows.")
    CLINE("Cline (API key)", false),
    /**
     * v4.4.4: RETIRED — removed from the product surface per product decision.
     * Kept as a deprecated constant ONLY so persisted Room rows (kind stored as
     * enum name) keep decoding — removing it would crash `ProviderKind.valueOf`
     * on upgrade (same contract as OLLAMA/LMSTUDIO). Research record:
     * docs/ANTIGRAVITY_ASSESSMENT.md.
     */
    @Deprecated("v4.4.4: Antigravity was removed from the product surface; kept only to decode legacy rows.")
    ANTIGRAVITY("Google Antigravity (via adapter)", true);

    /** v4.0: kinds offered in the provider UI — cloud-only.
     * The filter needs a stable name-independent source (Java annotation values are
     * not accessible at runtime), so the deprecated locals are listed explicitly. */
    val isSelectable: Boolean
        get() = this != OLLAMA && this != LMSTUDIO && this != ANTIGRAVITY && this != CLINE

    /** True when this row belongs to a removed local-server kind (legacy import). */
    val isLegacyLocalKind: Boolean
        get() = this == OLLAMA || this == LMSTUDIO

    /** v4.4.4: true when this row belongs to a kind retired from the product surface. */
    val isRetiredKind: Boolean
        get() = this == ANTIGRAVITY || this == CLINE

    /** v4.4: true when the credential comes from an account login flow, not a pasted key. */
    val requiresAccountLogin: Boolean get() = this == ZAI_ACCOUNT

    /** v4.4: true when the provider works without any credential (honest free tier). */
    val isCredentialFree: Boolean get() = this == OPENCODE_FREE

    val defaultBaseUrl: String
        get() = when (this) {
            OPENAI -> "https://api.openai.com/v1"
            ANTHROPIC -> "https://api.anthropic.com"
            GEMINI -> "https://generativelanguage.googleapis.com"
            OPENROUTER -> "https://openrouter.ai/api/v1"
            DEEPSEEK -> "https://api.deepseek.com/v1"
            OLLAMA -> "http://127.0.0.1:11434/v1"
            MISTRAL -> "https://api.mistral.ai/v1"
            GROQ -> "https://api.groq.com/openai/v1"
            LMSTUDIO -> "http://127.0.0.1:1234/v1"
            CUSTOM_OPENAI -> ""
            CUSTOM_ANTHROPIC -> ""
            // Anthropic-protocol transport of the GLM Coding Plan (9Router-verified).
            ZAI_ACCOUNT -> "https://api.z.ai/api/anthropic"
            // Verified live 2026-10-15: chat/completions answers (403 FreeTierError for
            // the no-credential free tier, 401 AuthError when a paid model needs a key).
            // Legacy https://opencode.ai/zen/v1 still serves identical responses.
            // NOTE: models live at the family root .../inference/v1/models, not under
            // .../openai/v1/ — OpenCodeClient derives that path (see its kdoc).
            OPENCODE_FREE -> "https://opencode.ai/inference/openai/v1"
            OPENCODE_ZEN -> "https://opencode.ai/inference/openai/v1"
            // Retired in v4.4.4 (kept for legacy rows): was the official Cline API endpoint.
            CLINE -> "https://api.cline.bot/api/v1"
            // Deliberately empty: the adapter endpoint is ALWAYS user-configured.
            // No default proxy, relay, or maintainer service may be implied here.
            ANTIGRAVITY -> ""
        }

    /** Whether the adapter should use the Anthropic wire protocol. */
    val isAnthropicProtocol: Boolean get() = this == ANTHROPIC || this == CUSTOM_ANTHROPIC || this == ZAI_ACCOUNT
    val isGeminiProtocol: Boolean get() = this == GEMINI
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    /** Encrypted API key (AES-GCM via Android Keystore). Never plaintext at rest. */
    val apiKeyEnc: String? = null,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = "",
    /** Extra HTTP headers (e.g. OpenRouter routing headers). */
    val extraHeaders: Map<String, String> = emptyMap(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val contextWindow: Long? = null,
)
