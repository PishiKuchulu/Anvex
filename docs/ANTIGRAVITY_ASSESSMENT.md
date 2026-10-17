# Google Antigravity provider — technical assessment (v4.4.3)

**Research date: 2026-10-16 (all sources inspected live). Decision: ship the
Option B user-configured adapter route. Options A and C rejected on evidence
below.** This document supersedes the v4.4.2 assessment (which shipped no
provider); the current brief explicitly re-opened the question and directed the
Option B evaluation that is implemented in 4.4.3.

## 1. What was inspected

- Official Antigravity documentation: `https://antigravity.google/docs/` — sitemap
  (139 URLs, retrieved 2026-10-16): IDE, CLI, IDE extensions (VS Code / Visual
  Studio / JetBrains / Zed / Xcode), SDK for building agents that run INSIDE
  Antigravity, MCP, skills, marketplace, plans, enterprise. **No third-party
  inference API for Antigravity model access exists in the official docs.**
- Official Google Antigravity Additional Terms of Service
  (`https://antigravity.google/terms/`, retrieved 2026-10-16) — verbatim:
  > "You must not abuse, harm, interfere with, or disrupt the Service. This
  > includes, but is not limited to, using the Service in connection with
  > products not provided by us. Using third party software, tools, or services
  > to access the Service (e.g. using OpenClaw with Antigravity OAuth) is a
  > breach of this Agreement. Such actions may be grounds for suspension or
  > termination of your Antigravity and/or Gemini CLI accounts."
- `github.com/dvcrn/antigravity-oauth-proxy` (Go; created 2026-01; last push
  2026-10-13; 7 stars; **no license file** — GitHub reports `license: null`).
- `github.com/cortexkit/antigravity-auth` (TS monorepo; MIT; created 2026-06;
  last push 2026-10-15; 56 stars; active).
- `github.com/JoshRob297/opencode-antigravity-auth` (MIT; created 2026-08; last
  push 2026-10-15; 45 stars; README states maintenance paused October 2026).
- Google AI developer forum (discuss.ai.google.dev) reports referenced by the
  v4.4.2 assessment (403 "disabled in this account for violation of Terms of
  Service" after using Antigravity OAuth plugins) — consistent with the official
  ToS text above.
- The "Antigravity agent" page on the Gemini API (ai.google.dev, 2026-10-13): a
  **managed agent on the Gemini API** — a different product billed under Gemini
  API quotas, not Antigravity plan entitlement.

## 2. How the community implementations actually work

All three references reach Antigravity models the same way: they present
themselves to Google as the first-party Antigravity client and call the
undocumented internal Cloud Code API.

| Reference | Mechanism (evidence) | Client-facing API | License / status |
|---|---|---|---|
| dvcrn/antigravity-oauth-proxy | Hardcodes Google's first-party OAuth client id + secret + `http://localhost:51121/oauth-callback` redirect (`internal/credentials/types.go`); calls `https://daily-cloudcode-pa.googleapis.com` (`CodeAssistEndpoint = https://cloudcode-pa.googleapis.com`, `v1internal`); spoofs `User-Agent: antigravity/cli/1.1.13 (aidev_client; …)` (`internal/antigravity/constants.go`) | **Documented** local/Workers HTTP API: OpenAI-compatible `POST /v1/chat/completions` (SSE), public `GET /v1/models`, Gemini-compatible `/v1beta/...`, MCP `/mcp`, admin API (`/admin/auth/start`, `/admin/auth/status`, `/admin/status`; `ADMIN_API.md`) | **No license (all rights reserved)**; active |
| cortexkit/antigravity-auth | OAuth PKCE reusing "the publicly observable behavior of the upstream Antigravity CLI" client identity + device fingerprint + `User-Agent` / `X-Goog-Api-Client` / `Client-Metadata` header transforms; multi-account pool with cooldowns; README ToS warning: "Using this software (and any proxy for Antigravity) violates Google's Terms of Service… suspensions, bans, and shadow-bans" | OpenCode v1/GA + Pi host plugins + CLI — **not** a standalone HTTP endpoint | MIT; active |
| JoshRob297/opencode-antigravity-auth | "Official Antigravity CLI v1.2.7 Signature Parity" — deliberately reproduces the official client fingerprint; quota via internal `/v1internal:retrieveUserQuotaSummary`; thinking-signature cache; README ToS warning: bans/shadow-bans reported | OpenCode plugin only — not a standalone endpoint | MIT; **maintenance paused** (Oct 2026) |

Conclusion: a public repository is not an officially supported integration. All
three depend on an undocumented internal API and first-party client
impersonation, which Google's ToS explicitly prohibits.

## 3. Strategy evaluation (per the brief)

### Option A — officially supported integration: NOT AVAILABLE
- No documented Google authentication + inference interface permits third-party
  clients to use Antigravity entitlement. The official ToS (§1) explicitly bars
  "products not provided by us".
- The Gemini API "Antigravity agent" and Gemini API keys are different products
  with different billing/quota; substituting them silently is forbidden by the
  brief and would misrepresent the user's entitlement.

### Option C — unsupported/undocumented upstream integration: OUT OF BOUNDS
- Would require embedding Google's first-party OAuth client id/secret in the
  APK, calling `cloudcode-pa.googleapis.com/v1internal`, and/or forging the
  official CLI fingerprint. All three are exactly what the brief forbids
  (bypassing access controls, forging client-attestation signals, impersonating
  a first-party client), and the official ToS prohibits the access itself.
  **Not implemented. None of that code or data appears in Anvex.**

### Option B — user-configured external adapter: CHOSEN (implemented in 4.4.3)
The user separately installs and runs an adapter they control (reference
layout: `antigravity-oauth-proxy` on their own machine, default
`http://localhost:9878`). Anvex speaks ONLY to that user-configured endpoint:

- **Provider registration**: `ProviderKind.ANTIGRAVITY`
  ("Google Antigravity (via adapter)"), isolated `AntigravityClient` in
  `:core:ai` (factory-dispatched), `defaultBaseUrl` deliberately empty — no
  public proxy, maintainer service, or third-party relay is ever hardcoded.
- **Account connection (adapter-mediated)**: `POST {root}/admin/auth/start`
  returns an `authorizationUrl` opened in the **system browser**; the user's
  Google sign-in happens there; the pasted final redirect URL is handed to
  `POST {root}/admin/auth/status` `{"code": …}`; `GET {root}/admin/status`
  reports the honest `configured` state. PKCE, state validation and token
  storage are the ADAPTER's documented job; Anvex never sees or stores a Google
  credential, never logs the pasted code, and sends it only to the
  user-configured endpoint. Adapters without the admin API (local
  file-credential deployments) answer 404 → the UI says so and points to the
  adapter's own login helper.
- **Model discovery**: live `GET {base}/models` only (OpenAI-style catalog;
  display names from `name`/`display_name`). No hardcoded catalog; malformed
  bodies degrade to an empty list; entitlement-restricted models simply do not
  appear.
- **Inference**: `POST {base}/chat/completions` with SSE streaming through the
  shared, battle-tested OpenAI-compatible transport (reasoning deltas, usage,
  cancellation, timeouts). Honest `User-Agent: Anvex/… (Android)`.
- **Connection test**: catalog must be non-empty; selected-model membership is
  reported; with key + model a 1-token chat probe verifies end-to-end; 401/403/
  429/5xx are surfaced verbatim with their status; "no adapter key set" is
  disclosed instead of pretended away.
- **Local vs remote distinction**: loopback (cleartext HTTP allowed — adb
  reverse / port-forward), private network (Android network policy blocks LAN
  cleartext → HTTPS or loopback forward required), remote (HTTPS required; the
  UI warns the adapter key and prompts leave the network).
- **Trust boundary, disclosed in the UI**: the adapter is a separate program
  the user chose to run and authenticate with their Google account; it sees
  every prompt and reply. Anvex states that this route is **unofficial**, that
  Google's Antigravity terms prohibit third-party tools and accounts may be
  suspended, and that **usage quota is not available** through this integration
  (never estimated, never fabricated).

**Explicitly out of scope / refused**: embedding any adapter, proxy binary or
relay; bundling community repo code (dvcrn's repo has no license); sending the
adapter key or prompts anywhere except the user-configured endpoint; any
client-impersonation logic; any claim of official support.

## 4. Dependency and license review

- **No new Gradle dependencies**; no new Android permissions; no WebView
  (system browser only); no copied source from any reference repository.
- dvcrn/antigravity-oauth-proxy: **no license** → its code must not be copied
  into Anvex (and is not). Anvex only consumes its documented HTTP API on a
  user-run instance — API consumption, not code redistribution.
- cortexkit/antigravity-auth (MIT) and JoshRob297/opencode-antigravity-auth
  (MIT): used strictly as research references; no code, assets, or headers
  copied. Their ToS warnings informed Anvex's disclosures.
- Existing stack unchanged: OkHttp 4.12.0, kotlinx.serialization, Compose,
  Android Keystore-backed credential storage.

## 5. Verification summary (this build)

- `./gradlew test` (JDK 17.0.20.1+1, Gradle 8.10.2, SDK 35): **281 tests** —
  0 new failures. 1 pre-existing failure (`BoltStreamParserTest.file action
  strips markdown fence and unescapes tags`) reproduced identically on the
  pristine 4.4.2 baseline — not a regression.
- New `AntigravityClientTest`: **26/26 pass** (factory selection, catalog
  parsing, validation honesty matrix, streaming + history + AUTH mapping, admin
  contracts incl. 404/blank/oversized handling, endpoint classification,
  admin-root derivation, secret hygiene).
- `:app:assembleDebug` ✓ (27.6 MB), `:app:assembleRelease` unsigned with R8 ✓
  (7.2 MB), `:app:lintDebug` → **0 errors**, 52 warnings — all in pre-existing
  files, none in the changed/added files.
- Project gates: `verify_room_schema_snapshots` PASS, `verify_launcher_icon`
  PASS, `verify_build_environment` READY, `final_release_audit` result
  **identical to the pristine baseline** (its `deep_audit` gate fails the same
  3 pre-existing checks on both trees).
- **Live verification: NOT performed** — no Antigravity-entitled test account
  and no authorized adapter environment were provided. All wire-contract claims
  come from the adapters' documented API and are proven against MockWebServer
  in the JVM suite. Live checks (real account connection, real model discovery,
  a harmless prompt, streaming, token refresh) remain blocked on credentials;
  see `docs/KNOWN_LIMITATIONS.md`.

## 6. Blockers that would reopen Option A

A documented Google authentication + inference interface that permits
third-party clients to use the user's Antigravity entitlement. If Google
publishes one, add it as an isolated `ProviderClient` using the existing
Keystore-backed credential store — the 4.4.3 `AntigravityClient` already
isolates everything Antigravity-specific behind the adapter boundary, so an
official route would slot in without touching other providers.
