# AI_PROVIDERS.md

Anvex talks to AI providers **directly from the device** — there is no proxy
server (the original bolt.diy used a server-side streaming route to protect keys;
on Android the Keystore takes that role).

## Configuration model

Every provider is a row in the provider manager (Settings → AI providers):

| Field | Notes |
|---|---|
| Name | your label, e.g. "My Provider" |
| Type | OpenAI / Anthropic / Gemini / OpenRouter / DeepSeek / Mistral / Groq / Custom (OpenAI-compatible) / Custom (Anthropic-compatible) — v4.0 is cloud-only |
| API base URL | optional override for known providers; **required** for custom types (e.g. `https://example.com/v1`) |
| API key | stored encrypted (Android Keystore AES-256-GCM); masked in the UI; leave blank on edit to keep the saved key |
| Model | free text, or fetched from the endpoint (`GET /models`) |
| Extra headers | one per line (`HTTP-Referer: https://...`) |
| Temperature / Max tokens | optional overrides |
| Default | the provider used for new chats |

**Test connection** performs `GET /models` (falls back to a 1-token chat request
on endpoints without model listing) and reports latency + model count.

## Wire protocols (implemented in `:core:ai`)

| Protocol | Chat | Models | Auth |
|---|---|---|---|
| OpenAI-compatible (OpenAI, OpenRouter, DeepSeek, Mistral, Groq, custom HTTPS endpoints) | `POST {base}/chat/completions` with `stream: true` | `GET {base}/models` | `Authorization: Bearer <key>` |
| Anthropic-compatible (Anthropic, custom) | `POST {base}/v1/messages` with `stream: true`, top-level `system` | `GET {base}/v1/models` | `x-api-key` + `anthropic-version: 2023-06-01` |
| Google Gemini | `POST {base}/v1beta/models/{model}:streamGenerateContent?alt=sse` | `GET {base}/v1beta/models` | `x-goog-api-key` |

All streaming is decoded by a hand-written SSE reader (line-based, keep-alive
tolerant, cancellation-aware, bounded buffers) — no Node AI SDK.

## Error mapping (brief §23)

HTTP/code → human titles: 401/403 → "Invalid API key or access denied", 404 →
"Model or endpoint not found", 429 → "Rate limit or quota exceeded", IOException →
"Network error — check your connection". Raw bodies are surfaced only as their
`error.message` fields, never as stack traces.

## Key security

- Keys exist in plaintext **only** in memory during a request.
- At rest: `iv || ciphertext` (AES-256-GCM, hardware-backed AndroidKeyStore key).
- Never written to logs, crash output, debug panel, or exported ZIPs.

## Local / custom endpoints (v4.0)

Anvex 4.0 removed the Ollama / LM Studio provider kinds. A custom endpoint
must be HTTPS (loopback HTTP is still accepted for `adb reverse`-style local
development). Existing v3 rows of the removed kinds still load — the provider
editor marks them as unsupported and asks you to re-create them as custom
cloud endpoints.

## Google Antigravity — via a user-installed adapter (v4.4.3)

Google publishes **no third-party API** for Antigravity model access, and the
official Additional Terms of Service prohibit "using third party software,
tools, or services to access the Service". Anvex therefore never connects to
Google for this provider. Full research and the Option A/B/C decision record:
`docs/ANTIGRAVITY_ASSESSMENT.md`.

What ships instead is a **dedicated adapter provider** (`ProviderKind.ANTIGRAVITY`,
isolated in `AntigravityClient`):

| Aspect | Contract |
|---|---|
| Endpoint | User-configured base URL of the user's own adapter (reference layout: `antigravity-oauth-proxy`, default `http://127.0.0.1:9878/v1`). No default is hardcoded. |
| Chat | `POST {base}/chat/completions` — OpenAI-compatible SSE streaming via the shared transport |
| Models | `GET {base}/models` — live catalog only (display names from `name`/`display_name`); never a bundled list |
| Account connection | Adapter-mediated: `POST {root}/admin/auth/start` → system browser → pasted redirect URL → `POST {root}/admin/auth/status`; state at `GET {root}/admin/status`. PKCE/state/tokens live adapter-side; Anvex never sees a Google credential |
| Auth to the adapter | Optional adapter key (the proxy's `ADMIN_API_KEY`), stored like every provider key: Android Keystore AES-256-GCM |
| Local vs remote | UI classifies the endpoint: loopback (cleartext OK) / private network (Android blocks LAN cleartext — use HTTPS or `adb reverse tcp:9878 tcp:9878`) / remote (HTTPS required) |
| Quota | **Not available** through this integration — shown as unavailable, never estimated |

Honesty guarantees: validation reports exactly what worked (catalog non-empty,
selected-model membership, optional 1-token chat probe when a key is set);
401/403/429/5xx surface verbatim with their status; no client fingerprint
spoofing (`User-Agent: Anvex/…`); the UI discloses that the route is unofficial
and that Google's terms put the account risk on the user's own adapter.
