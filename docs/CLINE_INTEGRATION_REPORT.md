# Cline Integration Report — Anvex v4.4.4

Date of research and live verification: **2026-10-17** (all probes run from
this environment with `curl`; safe, non-destructive GETs and auth-rejected
POSTs only — no credits spent, no account created).
Client identity in every probe: `User-Agent: Anvex/4.4 (Android; +https://anvex.app)`.

## 1. What was researched

| Reference | What it contributed | How it is (or is not) used |
|---|---|---|
| `docs/getting-started/authorizing-with-cline.mdx` (official) | Three auth paths: Cline usage-billing sign-in, ClinePass ($9.99/mo), BYOK. Sign-in is described only for Cline's own IDE extension and CLI (`cline auth` runs "the same auth flow as IDE setup") | Establishes that account sign-in is a first-party surface |
| `docs/api/authentication.mdx` (official) | Two methods: **API key** ("recommended authentication method for programmatic access", created at app.cline.bot → Settings → API Keys) and **account auth token** ("Generated automatically when you sign in", for "Cline extension and CLI" — "You do not need to handle these tokens manually"). Security guidance: do not embed keys in client-side code | API-key path implemented (user-supplied BYOK, never embedded); account tokens NOT used — they are first-party credentials, not a public third-party API credential |
| `docs/api/getting-started.mdx` (official) | `POST https://api.cline.bot/api/v1/chat/completions`, Bearer auth, SSE streaming, 401/402 troubleshooting ("To test without spending credits, use one of the free models", example `minimax/minimax-m2.5`) | Endpoint, auth header, streaming, and the 402 insufficient-credits semantic |
| `docs/api/chat-completions.mdx` (official) | Full request schema: `model` (`provider/model` format), `messages` (system/user/assistant), `stream` (default true), `tools` (OpenAI function-calling format), `temperature`; `delta.reasoning` for reasoning models; `usage` incl. `cost`; documented optional headers `HTTP-Referer` / `X-Title` | Wire format consumed via Anvex's shared `OpenAiCompatibleClient`; `X-Title: Anvex` sent (documented, honest) |
| `docs/api/models.mdx` (official) | Model-id format, reasoning/image capabilities, "Free experimentation: `minimax/minimax-m2.5`" | Catalog conventions; the free example is treated as documentation, not a hardcoded free list |
| `apps/cli` (official) | CLI distributed as macOS/Linux/Windows binaries (npm / platform binaries); OAuth login for Cline/ChatGPT-subscription/OCA; `cline auth` flows | CLI is not Android-compatible and its auth is first-party OAuth — not an integration mechanism for Anvex |
| Live `GET /api/v1/models` (this report, P1) | 467 models, `{id, object, created, owned_by}`, publicly readable, 16 `:free`-suffixed ids | The authoritative free signal: the catalog's own `:free` suffix |

## 2. Live endpoint tests (actual results, 2026-10-17)

| # | Request | Result |
|---|---|---|
| P1 | `GET https://api.cline.bot/api/v1/models` (no auth) | **HTTP 200** — 43,404 bytes, 467 models, shape `{id, object, created, owned_by}`. 16 ids end with `:free` (e.g. `nvidia/nemotron-3.5-super-vl:free`). `minimax/minimax-m2.5` (the doc's free example) is present but **not** `:free`-marked — the free mark rotates; hardcoding it would be wrong |
| P2 | `GET /api/v1/models` with `Authorization: Bearer <invalid>` | **HTTP 200** — the catalog ignores auth; discovery is public |
| P3 | `POST /api/v1/chat/completions`, no key, `:free` model | **HTTP 401** `{"error":"Unauthorized: …"}` — even free models require a key |
| P4 | `POST /api/v1/chat/completions`, invalid key, `:free` model | **HTTP 401** |
| P5 | `GET /api/v1/api-keys` (documented), invalid key | **HTTP 401** (not 404 — the endpoint exists). With a valid key this is 2xx, so it validates a key at **zero token cost** |
| P6 | `POST /api/v1/chat/completions`, invalid key, nonexistent model | **HTTP 401** — auth is checked before model routing |

## 3. Feasibility verdicts

### Path A — official Cline account sign-in for a third-party Android app: NOT SUPPORTED

- The official docs describe sign-in exclusively for Cline's own IDE
  extension and CLI. No third-party OAuth client registration, no documented
  redirect URIs for third parties, no public client exists.
- The account auth token is explicitly scoped to "Cline extension and CLI"
  and "generated automatically when you sign in" — it is a first-party
  credential, not a public third-party API credential. Extracting or replaying
  it from another application would violate the task's own constraints
  (no token extraction, no bypassing client restrictions / quotas).
- The `cline-free/*` promotion (FREE-tagged models in CLI/IDE) is tied to
  that first-party sign-in and is **not available through the API**.
- **Therefore Anvex implements no sign-in button for Cline**, and the UI says
  so honestly instead of silently substituting a key form for the requested
  login experience.

### Path B — official Cline API with API key: SUPPORTED (implemented)

- Documented, versioned, OpenAI-compatible; API keys are the documented
  recommended method for programmatic access.
- The docs' "do not embed keys in client-side code" guidance targets
  hardcoding keys into distributed apps. Anvex's integration is BYOK: the
  user creates their own key at app.cline.bot → Settings → API Keys, pastes
  it once, and Anvex stores it encrypted (Android Keystore, AES-GCM) like
  every other provider key. Nothing is embedded.
- Free models: identified **only** by the live catalog's `:free` suffix
  (OpenRouter-style convention the API itself uses). 16 marked at research
  time; the set rotates upstream. No id is hardcoded as permanently free;
  unmarked models show no FREE badge (unknown, not fabricated).

## 4. What was implemented (v4.4.4)

- `ProviderKind.CLINE` ("Cline (API key)", fixed base
  `https://api.cline.bot/api/v1`, no URL override) — `core/model/.../Provider.kt`.
- `ClineClient` (`:core:ai`), factory-dispatched in `ProviderClientFactory`.
  Isolated like `OpenCodeClient`/`AntigravityClient` so Cline-specific
  behavior (free classification, key validation, 402 mapping) can never leak
  into the shared adapters.
- Transport reuse: streaming, SSE parsing, reasoning deltas (`reasoning` /
  `reasoning_content`), usage + cost, tool-call wire format, image
  attachments and cancellation all come from the shared
  `OpenAiCompatibleClient` — no parallel agent runtime, no new dependencies.
- Honest identity on every request: `User-Agent: Anvex/4.4 (Android;
  +https://anvex.app)` plus the documented optional `X-Title: Anvex` header.
- `listModels()`: live `GET /models` only; `:free` ids get a `(FREE)` display
  badge, ids kept verbatim for requests; malformed bodies degrade to an empty
  list.
- `validateConnection()`: (1) catalog must be non-empty; (2) key validated at
  zero cost via documented `GET /api-keys` (401 = invalid key, disclosed);
  missing key disclosed, not pretended away; (3) selected-model catalog
  membership reported; (4) 1-token chat probe **only** for `:free` models
  (zero cost) — skipped with disclosure for paid models to avoid spending the
  user's credits; (5) HTTP 402 mapped to the documented "out of credits —
  add credits at app.cline.bot" remedy.
- UI (`ProvidersScreen.kt`): "Cline API key" label, the honest auth note
  (no third-party OAuth; `cline-free/*` not via API; FREE badge meaning),
  "Fetch models" enabled with no key (public catalog), Cline model-id
  placeholder. `ProvidersViewModel.buildRuntime` gained `requireModel = false`
  for discovery so "Fetch models" works before a model is chosen (test/save
  still require one).
- i18n: English + Persian strings in `Strings.kt`.
- Never logged: API keys, request bodies, key-metadata responses.

### Capabilities actually supported by the verified upstream

- OpenAI wire-protocol chat at `/api/v1/chat/completions` (SSE default,
  `stream: false` supported, `tools` in OpenAI function format,
  `finish_reason: tool_calls`, `delta.reasoning`, usage with `cost`).
- Public model discovery at `/api/v1/models` (auth ignored).
- Bearer <redacted> 401 when missing/invalid (even for `:free` models);
  documented 402 for insufficient credits; 429/5xx map through the shared
  error mapper.
- Anvex's agent pipeline consumes the provider as text (tools are parsed from
  response text by the existing PatchEngine); native `tool_calls` returned by
  the API are parsed by the shared transport but Anvex does not execute
  provider-declared tools — same boundary as every other OpenAI-compatible
  provider. No fabricated tool results.

## 5. Limitations (explicit, not silent)

- **No OAuth sign-in** — by design (Path A unsupported). Users who want the
  `cline-free/*` IDE/CLI promotion must use Cline's own extension/CLI.
- **Free set rotates** — the FREE badge reflects the catalog at fetch time;
  a model free today may be paid tomorrow. The UI note says the mark rotates.
- **402 is account-level** — out-of-credits failures name the remedy
  (app.cline.bot); Anvex cannot top up or check balances (no documented
  balance endpoint was used).
- **Live verification: NOT performed with a real key** — no Cline API key was
  available in this environment. All wire-contract claims are proven against
  MockWebServer in the JVM suite; live checks (real key validation, real
  `:free` probe, streaming, tool calls, 402 path) remain blocked on
  credentials. `./gradlew` could not run here (no JDK/Android SDK in this
  environment) — run `:core:ai:test` and `:app:assembleDebug` before release.

## 6. Dependency and license review

- **No new Gradle dependencies**; no new Android permissions; no WebView;
  no copied code from any repository. All Cline knowledge comes from Cline's
  own public documentation and live public endpoints.
- Existing stack unchanged: OkHttp 4.12.0, kotlinx.serialization, Compose,
  Android Keystore-backed credential storage.
