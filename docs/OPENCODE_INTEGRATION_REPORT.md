# OpenCode Integration Report — Anvex v4.4.1

Date of live verification: **2026-10-15** (all probes re-run and confirmed via
`tools/opencode_probe.sh` (shipped in the source tree) — safe, non-destructive GET/1-token POST requests only).
Client identity in every probe: `User-Agent: Anvex/4.4 (Android; probe)`.

## 1. What was researched

| Reference | What it contributed | How it is (or is not) used |
|---|---|---|
| `github.com/decolua/9router` | Provider-registry patterns, how third-party routers mount OpenAI-compatible upstreams | Architecture reference only; no code reused |
| `github.com/0xgetz/opencode-free-9router` | Documents the **exact free-tier gate** and the fingerprint the official client sends (`User-Agent: opencode/<v>`, `x-opencode-client: desktop`, `x-opencode-session`, `x-opencode-request`, `Authorization: Bearer public`, `stream: true`, forced `tools` quartet `bash/glob/grep/read`) | **NOT implemented.** Reproducing that fingerprint is client impersonation to bypass an upstream restriction — explicitly out of bounds for Anvex |
| `docs.opencode.ai` (live) | The API-key tier is now **"OpenCode Console"** (formerly "Zen"): sign in, add credits, `Authorization: Bearer <console API key>`; endpoints moved to `/inference/<family>/...`; public models endpoint `GET /inference/v1/models` | Implemented as the permitted path (`OPENCODE_ZEN` kind, relabeled "OpenCode Console (API key)") |

## 2. Live endpoint tests (actual results)

All requests sent with Anvex's honest User-Agent. No attestation headers were forged.

| # | Request | Result |
|---|---|---|
| P1 | `GET https://opencode.ai/inference/v1/models` (no auth) | **HTTP 200** — 87 models, 11 with `-free` suffix. Discovery is public. |
| P2 | `GET https://opencode.ai/zen/v1/models` (legacy path, no auth) | **HTTP 200** — identical list. Legacy v4.4 rows keep working. |
| P3 | `POST /inference/openai/v1/chat/completions`, model `space-bunny-free`, no credential | **HTTP 403** `{"type":"error","error":{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode. Download it at https://opencode.ai and keep building."}}` |
| P4 | `POST /inference/openai/v1/chat/completions`, model `gpt-5.5` (paid), no credential | **HTTP 401** `{"type":"error","error":{"type":"AuthError","message":"Missing API key."}}` |
| P5 | Same as P3 against legacy `/zen/v1/chat/completions` | **HTTP 403** — same FreeTierError body |
| P6 | `POST chat/completions`, nonexistent model id | **HTTP 400** `{"error":{"type":"server_error","message":"Upstream request failed: Model is unavailable."}}` |
| P7 | `GET /inference/v1/models` with `Authorization: Bearer <invalid>` | **HTTP 401** `{"error":{"type":"server_error","message":"Invalid credential"}}` — the models endpoint validates the bearer when present, so an authenticated models fetch verifies a Console key at **zero token cost** |
| P8 | `GET /inference/openai/v1/models` (models next to chat) | **HTTP 404** — models live at the *family root* `/inference/v1/models`, not per-family. This is why `OpenCodeClient.modelsUrl()` drops the `/openai` segment |

### Capabilities actually supported by the verified upstream

- **OpenAI wire protocol** chat at `/inference/openai/v1/chat/completions` (SSE streaming
  shapes are standard OpenAI-compatible chunks — consumed via Anvex's shared
  `OpenAiCompatibleClient` SSE parser).
- **Public model discovery** at `/inference/v1/models` (and legacy `/zen/v1/models`).
- **Bearer authentication** for the Console (API-key) tier; 401 when the key is
  missing/invalid, 403 FreeTierError when a no-credential client requests free models,
  429/5xx behave like any OpenAI-compatible gateway (mapped by the shared error mapper).
- **Tool calling**: Anvex's provider contract is text-based (agent actions are parsed from
  response text by the SEARCH/REPLACE PatchEngine); native provider tool-calls are not
  part of the `ProviderClient` interface, so no tool-call wire format is exercised.

## 3. The free-tier blocker (explicit integration limitation)

**OpenCode's free tier is gated to the official OpenCode client.** Any third-party client
— including Anvex with its honest identity — receives HTTP 403 `FreeTierError`
(probe P3/P5). The `opencode-free-9router` reference adapter passes the gate by
reproducing the official client's fingerprint (attestation headers, forced UA, tool
quartet, `Bearer public`). Anvex deliberately does **not** do this.

Consequently, in Anvex:

- `OpenCode Free` exists as a distinct provider entry with **live model discovery**
  (public endpoint, filtered to the `-free` ids the endpoint itself reports — never a
  hardcoded list).
- The **test-connection** flow performs discovery first (works — reports the model count),
  then a real one-token chat probe and reports the exact upstream verdict. Today that is
  `HTTP 403 — OpenCode's free tier can only be used from within OpenCode…`, labeled as an
  upstream access restriction with a pointer to the Console provider. If upstream policy
  ever changes, the same probe will report success honestly — no code change needed.
- Chat requests through the free tier surface the real 403 as
  `AiError.Kind.ACCESS_RESTRICTED` (new error kind, split from 401 `AUTH` so the UI can
  tell a policy refusal from a bad key).
- No fabricated success, no impersonation, no bypass. **OpenCode Free direct chat is
  effectively unavailable until OpenCode establishes a permitted third-party path.**

## 4. The permitted path: OpenCode Console (API key)

- Kind `OPENCODE_ZEN` (enum name unchanged so persisted Room rows keep decoding),
  relabeled **"OpenCode Console (API key)"**.
- Default base URL updated to the current documented endpoint
  `https://opencode.ai/inference/openai/v1` (chat: `{base}/chat/completions`;
  models derived at the family root, see P8). Legacy `/zen/v1` rows continue to work.
- Auth: `Authorization: Bearer <console API key>` (documented method).
- Key verification at zero token cost: the models endpoint rejects invalid bearers with
  401 (P7), so `validateConnection()` does an authenticated models fetch. A missing key
  is refused *before* any request — the public endpoint would otherwise answer 200 and
  a "key verified" claim would be false.
- Free-tier models (`-free` ids) are also usable through the Console with a key
  (documented as "limited-time free"); Anvex does not promise any quota — availability
  and limits may change without notice.

## 5. What is honestly NOT verified

- **No live chat completion with a genuine Console API key** — no valid key exists in
  this environment. The Console path is implemented against the documented OpenAI wire
  protocol and validated end-to-end with MockWebServer (see `OpenCodeClientTest`), but a
  real-key smoke test remains for a device/user that owns a key.
- **No on-device (Android) smoke test** in this environment (JVM toolchain only).
- Free-model catalog contents drift upstream; discovery is live, so Anvex always shows
  what the endpoint reports at request time.
