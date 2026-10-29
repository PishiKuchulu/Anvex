# Known Limitations — Anvex 4.10.5

This document describes the **actual** runtime rather than the original bolt.diy/WebContainer
assumptions.

## Runtime

- Preview uses the embedded WebView runtime with bundled Babel/React assets. It does not
  provide a general Node.js/npm/Vite development server.
- The separate Terminal/Agent runtime can execute `/system/bin/sh` in an app-private project
  workspace when policy and approvals allow it.
- Node.js is optional and only detected from supported system/toolchain locations; project-local
  executables are not trusted.
- Android builds use the app-managed Gradle/JDK/SDK toolchain. Build scripts are executable code
  and are therefore not a sandboxed operation.
- TypeScript preview support is type-stripping/transpilation only; no TypeScript type checker runs.

## Preview

- React previews transpile in WebView; large projects can take longer on low-end devices.
- Third-party CDN dependencies require network access.
- There is no HMR; Preview refresh reloads the page.

## Android specifics

- Agent foreground execution is explicit and uses a user-started `START_STICKY` service. The old
  repeating AlarmManager watchdog is intentionally removed for Android 12+ compliance.
- Android 13+ notification permission affects whether the Agent notification is visible in the
  notification shade; the foreground service itself remains subject to Android system policy.
- A physical-device smoke test must still be performed on a real Android device.

## Network

- The preview server is loopback-only.
- LAN status is loopback by default. Explicit LAN exposure is authenticated but uses HTTP rather
  than TLS and should only be used on a trusted network.
- Ollama/LM Studio commonly use local HTTP endpoints; these are intentionally treated as local
  provider exceptions rather than evidence that arbitrary remote HTTP is safe.

## Build verification

- Source-level verification does not prove a successful APK build. A real Android SDK/JDK/Gradle
  environment and device smoke test are required before a production release claim.

## v4.0 harness additions (honest scope)

- **GitLite is a local VCS**: init/status/add/rm/commit/log/branch/checkout/tag/diff/fileAtHead
  over loose objects; repositories stay compatible with the desktop `git` CLI. Network
  transport (clone/fetch/push), pack files, merge/rebase, submodules and hooks are not
  implemented; remote publishing continues through the GitHub REST repository.
- **Terminal**: a real interactive `/system/bin/sh` process with streamed output; Android
  without root cannot provide a true pty (termios), so full-screen TUI programs that need
  a controlling terminal may misbehave.
- **Diagnostics**: LightDiagnostics performs static checks (JSON/HTML/CSS/JS bracket
  balance, TODO/FIXME, duplicate top-level JSON keys) reported in LSP-shaped payloads;
  it is not a language server and performs no cross-file/type analysis.
- **MCP**: remote servers over streamable HTTP only; stdio servers cannot be hosted on
  Android. Keys are Keystore-encrypted at rest; private/OAuth flows are not supported.
- **In-app APK builds** require an imported Android-compatible toolchain bundle (JDK +
  SDK + Gradle with `toolchain.json`). A desktop glibc JDK is not a valid phone toolchain
  and is rejected. First builds download Gradle dependencies over the network.
- **Harness ZIP export**: the in-app source mirror excludes the mirror directory itself
  (it cannot contain itself); the exported archive otherwise contains the complete v4.0
  source tree, so it builds with JDK 17 + Android SDK exactly like the shipped zip.
- **Harness runtime assets**: React/ReactDOM/Babel, JSZip (`app/src/main/assets/harness/jszip.min.js`),
  and the JetBrains Mono font (`app/src/main/assets/harness/fonts/`) are bundled offline
  in app assets; the harness and ZIP export operate completely offline with no CDN dependencies.
- **Agent tool calls**: the agent drives tools through the bolt artifact/action protocol
  (file writes, shell/start with approvals). Native provider-side function-calling loops
  are not re-implemented in v4.0.

## v4.3 additions (honest scope; not yet compiled or device-tested)

- **Python sandbox**: Pyodide v0.26.4 is loaded from a pinned CDN on first run; code can use the
  network through the page. The WebView has no file access and exposes one bridge method.
- **MCP**: bearer tokens only (no OAuth). Tool calls need per-server permission plus a per-call
  "Allow once" prompt; the prompt is shown only while the chat screen is open, and unanswered
  requests are denied after 120 seconds.
- **Skills**: declarative text only; no code runs. Enabled skill text is capped at 8,000 characters.
- **Web search and images**: native search is available only for Anthropic and Gemini; image
  generation only for OpenAI and Gemini. Other providers keep the agent's fetch/search tools.
- **Sources**: plain text, not clickable.
- **Layout**: composer and per-message action rows use 48 dp touch targets and may be cramped
  on narrow screens.
- **Performance**: not measured on low-end devices.

## v4.3.1 maintenance (honest scope)

- **Single font copy:** JetBrains Mono lives in `app/src/main/assets/harness/fonts/` and is loaded by Compose through `Font(path, assetManager)`.
- **Exported source ZIP:** contains project source, Gradle files and the gradle wrapper. It omits the vendor bundles (React, Babel, JSZip), the fonts, `tools/`, `docs/audit/` and `.github/`. Those are in the installed app's assets. An exported project therefore builds, but its harness needs those files copied back in before the harness runs.
- **Room migration tests:** snapshots exist for schema versions 2 and 12 only. Versions 1 and 3-11 were never exported with the project, so `MigrationTest` cannot yet verify every step. Generating them requires building historical commits; they cannot be fabricated.
- **Android 15+ time limit:** the agent service handles `onTimeout` for `dataSync` and stops cleanly. The user restarts the runtime from the app.
- **Test coverage:** pure logic has unit tests (parsers, MCP target packing, approval broker, export detection, Markdown and TTS text). ViewModels, background services, and the local web servers have no unit tests yet.
- **LAN exposure:** bearer-token authentication is required, but traffic is plain HTTP, so use it only on a trusted network.

## v4.4 additions (honest scope)

### Persian language
- The in-app language switch applies the platform locale immediately (API 33+ via
  `LocaleManager`, API 26-32 via legacy recreate). All `tr()`-based strings (385 entries)
  render Persian under the فارسی setting. A short list of legacy literals (mostly
  non-interactive captions in Home/Chat chrome, e.g. relative-time labels and the
  onboarding blurb) remains English-only until those call sites are converted to `tr()`;
  nothing displays blank — `tr()` falls back to the English key.
- The Compose layout direction flips to RTL together with the locale (not before), so
  Persian text renders in a right-to-left frame with correct bidi per paragraph (BidiText).

### Node.js on Android
- A real Node binary cannot ship inside an APK (Node targets glibc; Android runs bionic).
  Anvex is honest about this and offers two backends (Settings → Workspace & build):
  - **In-app compat layer**: plain JavaScript on the WebView engine with a Node-style API
    subset (`console`, `process`, sync `fs`, relative `require`). No npm packages, no
    network APIs, no Buffer/streams/child_process — by design, not by accident.
  - **Termux bridge (RUN_COMMAND)**: forwards `node`/`npm` to a real Node installation
    inside Termux. Execution and its output happen in Termux; Anvex reports exactly that
    and never fabricates output.
- The terminal's interactive `/system/bin/sh` session remains unchanged; `node`/`npm`
  commands are intercepted and routed by the backend setting.

### Z.ai (GLM Coding Plan)
- Login uses the ZCode-CLI-compatible polling flow verified against 9Router ce4460e
  (zcode.z.ai/api/v1/oauth/cli/init → poll → api.z.ai/api/auth/z/login → coding-plan
  key). These endpoints are undocumented for third parties; if Z.ai changes the protocol,
  the coordinator (core/ai/zai) is the single place to update. The coding-plan key has
  NO refresh grant — expiry means re-login (same as the official CLI), and Anvex says so.
- No quota numbers are displayed: the verified usage endpoint requires account-scope
  data Anvex does not hold, and fabricated quotas are forbidden by the spec.
- Live upstream behavior of OpenCode's free tier was probed from this environment
  again on 2026-10-15 (v4.4.1): `HTTP 403 FreeTierError` — "OpenCode's free tier can
  only be used from within OpenCode" (an earlier v4.4 probe from this environment
  observed `RegionError`; either way both are surfaced verbatim). Anvex never claims
  the free tier is connected and does not reproduce the official client fingerprint
  to bypass the gate.

### OpenCode (v4.4.1)
- **Free tier is gated to the official OpenCode client.** Direct chat requests from
  Anvex answer HTTP 403 FreeTierError (verified live — see
  docs/OPENCODE_INTEGRATION_REPORT.md). Anvex surfaces the restriction verbatim with
  the exact status; free-tier chat stays effectively unavailable until OpenCode
  establishes a permitted third-party path. Model discovery IS public and works.
- **OpenCode Console (API key)** is the permitted path; its key is verified at zero
  token cost through the authenticated models fetch. No live chat with a genuine
  Console key was performed in this environment (no key available) — the wire path
  is covered by MockWebServer tests instead.
- The `-free` model filter is a heuristic from the endpoint's own ids; the upstream
  pricing page also lists a few free models without the suffix (e.g. `big-pickle`).
  Users can type any model id; the chat gate applies regardless.

### Architect-review features — delivered vs. deferred
Delivered in v4.4: reasoning streams (reasoning_content / thinking_delta / Gemini thought
parts) with the ThinkingCard; SEARCH/REPLACE targeted edits + editor Canvas chips;
Mermaid/SVG artifacts with version history; cloud builds via GitHub Actions (dispatch,
bounded polling, log tail, artifact APK download); the auto-fix hand-off (build errors go
to the assistant through the share intake — the user approves what is sent); Vision for
screenshot→chat; edit-and-branch conversation turns with journal-based file rollback;
Anthropic prompt caching (`cache_control`) on the system block.
Deferred honestly (not implemented, not claimed): pack files in GitLite; HMR in the
preview; a real TypeScript checker; TLS for LAN exposure (documented warning instead);
full branch-tree navigation (editing re-sends as a new turn and rolls files back — the
older replies stay visible, but there is no v1/v2/v3 branch switcher UI).

### Cloud builds
- The GitHub Actions path requires an existing repository (the Contents API cannot create
  repos), the saved GitHub Sync token, and network. Polling is bounded (5s × 40). Rate
  limits (HTTP 403/429) are surfaced verbatim from the API.

## OpenCode Free — on-device runtime feasibility (audited 2026-10-16)

- **The genuine OpenCode runtime cannot run inside the Anvex APK on Android.** The
  upstream publishes no Android target (verified across releases v1.18.0–v1.19.1),
  and the official linux-arm64 builds are glibc/musl dynamically linked ELFs —
  Android runs bionic, so they cannot load (the same reason a real Node binary
  cannot ship inside an APK). Full evidence and the NO-GO decision:
  `docs/OPENCODE_RUNTIME_FEASIBILITY.md`.
- **The free tier is gated to the official client, not to accounts** — verified
  two-sided on 2026-10-15/16: the genuine runtime completed a free-model request
  *without login* on a reference host, while Anvex's honest client receives
  HTTP 403 FreeTierError for the identical request. Anvex does not impersonate the
  official client; free-tier chat stays unavailable in Anvex until upstream opens
  a permitted third-party path or ships an Android-compatible runtime.
- **Region policy (provider-side):** Cat Jam Free is unavailable in Iran and 19
  other countries; free models are "free on OpenCode for a limited time" per the
  live Console docs. Availability of other free models from Iranian networks is
  untested from this environment and not claimed either way; upstream errors are
  surfaced verbatim either way.
- **OpenCode Console (API key)** remains the permitted direct path; still no live
  key chat test in this environment (no key exists here).

## Google Antigravity — adapter route (4.4.3)

- **No official third-party API exists.** Google's Antigravity Additional Terms
  (retrieved 2026-10-16) prohibit accessing the Service with tools not provided
  by Google. Anvex ships an **adapter route only** (user-installed, user-run
  adapter; see `docs/ANTIGRAVITY_ASSESSMENT.md`) and never talks to Google
  directly for this provider.
- **Account risk sits with the user's adapter.** The adapter itself logs into
  Google with the user's account using the first-party client identity — that
  is the adapter's behavior, disclosed in Anvex's UI; Google has reportedly
  suspended accounts for exactly this. Anvex does not impersonate any client.
- **Quota/usage data is unavailable** through the documented adapter API and is
  never estimated or fabricated.
- **Live verification was not possible in this environment** (no
  Antigravity-entitled account, no authorized adapter host). All wire contracts
  are covered by MockWebServer JVM tests; the first real connection happens on
  the user's device.
- **Browser sign-in via the adapter admin API** requires an adapter build that
  exposes `/admin/auth/*` (e.g. the antigravity-oauth-proxy Workers/KV layout).
  Local file-credential deployments answer 404 — the UI then directs the user
  to the adapter's own login helper and the honest status check.
- **LAN cleartext is blocked by Android's network policy** (platform
  limitation, pre-existing): a phone reaching a desktop adapter over Wi-Fi
  needs HTTPS (reverse proxy / tunnel) or a USB loopback forward
  (`adb reverse`).
