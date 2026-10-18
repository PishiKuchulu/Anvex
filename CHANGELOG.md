# Changelog

## 4.4.5 (unreleased) — Composer UX rework

### Changed
- Composer toolbar: the five undifferentiated icon buttons (project, attach,
  image, mic, camera) are replaced by a single "+" entry point opening a
  labeled bottom sheet (Project files / Attach file / Photo library / Take
  photo). Mic stays dedicated as a first-class mobile input. The old
  horizontally-scrolling button strip (which cut off the thinking chip on
  narrow phones) is gone; the row is now [+][mic][thinking][model…][send].
  All handlers are unchanged — pure reorganization. Persian strings added.

## 4.4.4 (unreleased) — Code-review hardening pass

External code review (2026-10-16) findings addressed. No behavior change for
the happy path; all changes are null-safety, lifecycle, or dependency-hygiene
fixes. Not yet built here (no JDK/Android SDK in this environment) — run
`:app:assembleDebug` and the unit tests before release.

### Changed
- Command Deck (v4.4.4 design direction): the restrained Obsidian IDE aesthetic
  is now the default theme (`UiThemeStyle.PROFESSIONAL` — deep neutrals, blue
  accent, hairline borders over gradients, 8pt grid). Fresh installs and
  existing users who never picked a style get it; explicit choices (Nebula,
  Glass, Aurora) are kept. The signature control is the chat composer as a
  command palette: mono `❯` prompt glyph, 16dp hairline-bordered surface, and
  a send key that springs alive (damped spring) when a command is ready.
  Message bubbles and streaming surfaces tightened to the 8pt grid (16dp/4dp
  radii); project cards show technical metadata in mono with smooth
  insert/remove motion (`animateItem`).
- Smoothness (static audit, all verified in code): the chat root no longer
  recomposes on every 80ms stream tick — it collects a lightweight `streamUi`
  snapshot (emits only on visibility-relevant changes) while `StreamingBubble`
  collects the live session locally; message bubbles are skippable again
  (hoisted lambdas + `@Immutable` provider holder); auto-scroll follows the
  tail only when pinned to the bottom with instant jumps (chat, terminal,
  preview — no more animation fighting); terminal buffer capped at 2000 lines
  with stable keys; build/preview/harness/developer lists gained stable item
  keys; ThinkingCard timer no longer resets per token and its nested scroll
  was removed; HarnessScreen ZIP flow remembered (was silently dropping
  notifications after recomposition); composition-time work cached
  (`versionsFor`, `partition`, bidi scan, last-assistant scan).

### Removed
- Google Antigravity and Cline provider kinds retired from the product
  surface per product decision (v4.4.4): `AntigravityClient` and `ClineClient`
  (+ their tests), the Antigravity adapter card / account-connection UI, the
  adapter locality badge, and all kind-specific editor branches are gone.
  - The `ANTIGRAVITY` / `CLINE` enum constants are kept as `@Deprecated`
    (same contract as OLLAMA/LMSTUDIO): Room stores kind names, so removing
    the constants would crash `ProviderKind.valueOf` on upgrade for existing
    installs. Both kinds are excluded from `isSelectable` (no longer offered
    anywhere in the UI) and flagged via the new `isRetiredKind`: legacy rows
    show a "removed in 4.4.4" notice and are read-only (save blocked, same as
    legacy local-server rows); at runtime they fall through to the generic
    OpenAI-compatible client.
  - Research history is preserved in `docs/ANTIGRAVITY_ASSESSMENT.md`,
    `docs/ANTIGRAVITY_SIGNIN_FIRST_BLOCKER.md`, and
    `docs/CLINE_INTEGRATION_REPORT.md`.

### Fixed
- Eliminated every `!!` (non-null assertion) in production Kotlin sources:
  navigation `projectId` args in `AnvexApp.kt` now use `requireNotNull` with a
  clear message instead of crashing with a bare NPE; `ChatScreen.kt`,
  `HomeScreen.kt`, `ChatViewModel.kt`, `NodeCompatRuntime.kt`,
  `FallbackProviderClient.kt`, `GitLite.kt`, `NodeRuntime.kt`,
  `ProviderAgentPlanner.kt`, `AndroidTerminalSession.kt`,
  `HarnessWebServer.kt`, `StaticPreviewServer.kt`, and
  `SkillRuntimeService.kt` use local vals / `requireNotNull` / `?: error(...)`.
- `:core:android` no longer leaks dependencies via `api()` — all eight
  declarations are `implementation()` now; `:app` explicitly declares the
  `kotlinx.coroutines.android` and `kotlinx.serialization.json` artifacts it
  uses directly (previously resolved transitively).
- `HarnessBridge.stop()` now clears the `webView` reference so a stopped
  bridge cannot push JS events and does not retain the view.

### Deliberately unchanged (documented)
- `runBlocking` inside `@JavascriptInterface` methods (`HarnessBridge`) and
  the `InternalWebServer` status providers: the JS bridge contract is
  synchronous by platform design, and the providers run on dedicated daemon
  HTTP threads (loopback-only endpoint). Converting to async callbacks would
  break the `bridge.js` contract; tracked as a future redesign, not a bug.
- Termux `RUN_COMMAND` bridge: opt-in developer feature, kept as-is.
- Dependency upgrades (AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, Compose BOM
  2024.12.01, Room 2.6.1, …): deferred — requires a full Gradle build to
  verify, unavailable in this environment.

### Assessed (no code change)
- "Sign-in-first" Google Antigravity UX request (2026-10-17): re-verified
  against the official docs and the live Additional Terms of Service —
  no supported third-party OAuth/API flow exists and the ToS explicitly
  prohibits third-party tools (suspension/termination risk). Per the request's
  own stop condition, no implementation was attempted; the blocker is
  documented in `docs/ANTIGRAVITY_SIGNIN_FIRST_BLOCKER.md`. The 4.4.3
  user-configured adapter route remains the only integration path.

## 4.4.3 — Google Antigravity provider (adapter route, Option B)

> Research date 2026-10-16. Full evidence and decision trail:
> `docs/ANTIGRAVITY_ASSESSMENT.md`. Built and tested in this environment
> (JDK 17.0.20.1+1, Gradle 8.10.2, SDK 35): `test` → 281 tests, 0 new failures
> (1 pre-existing `BoltStreamParserTest` failure identical on the pristine 4.4.2
> baseline); `:app:assembleDebug` and `:app:assembleRelease` (R8) both green;
> `:app:lintDebug` → 0 errors / 52 warnings (all pre-existing files).

- **New provider kind `ANTIGRAVITY` ("Google Antigravity (via adapter)")** — the
  Option B route. Google publishes no third-party API for Antigravity and the
  official Additional Terms (retrieved 2026-10-16) prohibit "using third party
  software, tools, or services to access the Service". Anvex therefore connects
  ONLY to a **user-installed, user-controlled adapter** (documented layout:
  `antigravity-oauth-proxy`) and never to Google:
  - `AntigravityClient` (isolated in `:core:ai`, like `OpenCodeClient`): OpenAI-
    compatible chat streaming + live model discovery against the adapter,
    honest `User-Agent: Anvex/…` (no first-party fingerprint spoofing, no
    embedded OAuth client id/secret, no `cloudcode-pa.googleapis.com` calls).
  - **Adapter-mediated account connection**: `POST /admin/auth/start` → system
    browser → user pastes the final redirect URL → `POST /admin/auth/status`
    `{"code": …}` → `GET /admin/status` for the honest `configured` state.
    PKCE/state/token storage live adapter-side; Anvex never sees a Google
    credential and never logs the pasted code.
  - **Honest validation**: catalog must be non-empty; selected-model membership
    reported; with key + model a 1-token chat probe verifies end-to-end; 401/
    403/429/5xx surfaced verbatim; "no key set" disclosed instead of faked.
  - **Local vs remote distinction** in the UI (`AntigravityAdapterEndpoint`):
    loopback (cleartext allowed, adb-reverse/port-forward) / private network
    (Android blocks cleartext LAN; HTTPS or loopback forward required) / remote
    (HTTPS required; key + prompts leave the network).
  - **No quota/usage fabrication**: the adapter route exposes no reliable quota
    data; the UI states unavailability instead of estimating.
  - `ProviderKind.ANTIGRAVITY.defaultBaseUrl` is deliberately empty — no public
    proxy, maintainer service, or third-party relay is ever hardcoded.
- 26 new JVM tests (`AntigravityClientTest`): factory selection/registration,
  catalog parsing + malformed catalogs, validation honesty matrix (no models /
  key probe ok / probe refused / no key / model-not-in-catalog / 401/403/429/5xx
  / offline), SSE streaming + history + reasoning + AUTH error mapping, admin
  status/start/complete contracts incl. 404 handling and blank/oversized paste
  rejection, endpoint classification, admin-root derivation, secret hygiene
  (toString/masking/messages).
- UI: new `AntigravityCard` on the providers list (branding, honest disclosure,
  configured adapters, setup entry), adapter section in the provider editor
  (connect flow with paste field, account status, locality badge, disclosure),
  deep-link `provider/new?kind=ANTIGRAVITY`, Persian translations for the new
  strings, `Fetch models` enabled from the adapter URL alone.
- `ProviderRepository` requires the adapter base URL for the new kind (same
  Keystore-encrypted key storage as every provider); `ProvidersViewModel` gains
  the connect state machine and model-less connection testing for this kind.
- Docs: `docs/ANTIGRAVITY_ASSESSMENT.md` rewritten with the 2026-10-16 research
  (official ToS text, three community repos' mechanisms/licenses/maintenance,
  Options A/B/C evaluation); `docs/AI_PROVIDERS.md` documents the adapter route.
- No new dependencies, no new permissions, no WebView usage (system browser
  only), no copied third-party code (dvcrn/antigravity-oauth-proxy ships **no
  license** — only its documented HTTP API is consumed, user-run).

## 2026-10-16 — OpenCode runtime feasibility audit (docs-only; no code change)

> Mission: determine whether the **genuine OpenCode runtime** can run inside the
> Anvex app sandbox on Android 13/ARM64 so OpenCode Free works fully on-device
> (no PC, no Termux, no separate OpenCode app, no relay).
> Decision: **NO-GO** — full evidence: `docs/OPENCODE_RUNTIME_FEASIBILITY.md`.

- Official upstream verified live (`github.com/anomalyco/opencode` v1.19.1, MIT):
  **no Android target exists** in any inspected release (v1.18.0–v1.19.1) — and the
  official linux-arm64 artifacts are glibc/musl **dynamically linked ELFs**
  (177–186 MB uncompressed) that cannot load on Android's bionic. Same hard blocker
  the project already documents for Node.js. Licensing (MIT) would permit
  redistribution — the blockers are binary compatibility and platform policy.
- Genuine-runtime PoC (linux-x64 reference host, no login, `auth.json` never
  created): `opencode serve` healthy in ~2 s on loopback; real chat request through
  its documented HTTP API on free model `space-bunny-free` completed in ~0.7 s —
  response `PONG`, cost 0, tokens 1/4. The free tier is therefore gated on
  **official-client identity, not accounts**. Anvex's honest client receives
  HTTP 403 FreeTierError for the same request (re-verified the same day). Anvex
  does not and will not reproduce the official client fingerprint.
- Runtime environment findings recorded for the future: XDG state dirs + SQLite,
  inotify watcher, `/api/pty` endpoints (no termios pty on Android without root),
  CLI `run` mode hangs without a controlling terminal (completes under a pty),
  VCS features need a system `git`, SIGTERM shutdown (exit 143), ~6.3× APK size
  impact if it were bundleable.
- Provider-side policy facts surfaced from the live Console docs: free models are
  "free on OpenCode for a limited time"; **Cat Jam Free is unavailable in Iran**
  and 19 other countries; Muse Spark 1.3 Contributor Free discounts pricing in
  exchange for training-use permission (worth showing before users opt in).
- Fresh verification of the **unchanged** v4.4.1 tree (this environment, JDK
  17.0.20.1+1, Gradle 8.10.2, SDK 35): `cleanTest test` with the build cache
  disabled → **417 executions / 336 unique tests, 0 failures, 0 errors, 0
  skipped**; `:app:packageDebug` re-executed → APK **byte-identical** to the
  delivered v4.4.1 artifact (SHA-256 `82b685c0f6aeeb33c79d977b536d78cdb62663d7316c6e1468ce8810a80d2baf`,
  28,250,395 B); `:app:lint` → **0 errors / 57 warnings** (baseline).
- **No source files were modified.** The closest legitimate on-device architecture
  (v4.4.1's in-app provider: live discovery + honest identity + verbatim 403
  surfacing) already stands; this round adds only the evidence trail.

## 4.4.2 — reference-source consolidation round

> Built on 4.4.1. Static review only in this environment: no Gradle/JDK/SDK was available,
> so nothing below was compiled or executed here (4.4.1's own verification claims are its own).

### Added / changed
- **App-wide terminal** (`AndroidRuntimeGateway.HOME_PROJECT_ID`): opens without any project, in app-private
  `terminal-home`; Home "Terminal" tile and Settings → Terminal use it. Project terminals unchanged.
- **New project dialog** reduced to name + folder. A folder that already contains files is now **adopted as-is**
  (nothing is overwritten, no starter is written, type is inferred) instead of failing with "must be empty".
  Default starter is Blank.
- **Aurora** (the AI Studio cyan/slate design, ported in 4.4.x) is now the default theme for fresh installs.
  Existing installs keep their saved choice (Settings → Appearance).
- `tr()` follows the in-app language choice (`LocalAppLanguage`) before the platform locale.
- Chat error card explains provider 403 restrictions and the permitted alternatives.

- **Launcher icon** replaced with the supplied "A / AI brain" artwork: adaptive foreground (feathered cut-out on the
  existing `#0B1020` background), monochrome layer for themed icons, legacy square and round icons, all densities.
  Reproducible with `tools/generate_launcher_icons.py` (source image in `tools/icon-source/`).

### Fixed
- GitHub push failed for files that did not exist yet (404 on the sha lookup).
- `cleanEscapedTags` corrupted real HTML/JSX containing `&lt;`/`&gt;`.
- `GitLite.rm` deleted untracked files.
- Editor: `fillMaxSize()` inside a Column pushed the undo/redo toolbar to zero height.
- `HarnessBridge.version()` reported 4.2.2; the download self-test panel was removed from the Harness page.

### Deliberately not added
- Google Antigravity provider — see `docs/ANTIGRAVITY_ASSESSMENT.md`.
- OpenCode free-tier workaround — Anvex still does not imitate the official client (see 4.4.1).
- Duplicates of r3 features that 4.4.1 already implements differently (thinking, SEARCH/REPLACE `PatchEngine`,
  Node compat + Termux bridge, cloud build, edit message, diagrams).

## 4.4.1 — OpenCode integration round (this ZIP)

> Live endpoint verification: 2026-10-15, honest client identity, safe probes only
> (docs/OPENCODE_INTEGRATION_REPORT.md). Build/test claims executed in this
> environment with a real JDK17 + Android SDK toolchain.

### OpenCode Free — distinct provider, honest about the gate
- New isolated adapter `core/ai/.../OpenCodeClient.kt` (both OpenCode tiers);
  the shared `OpenAiCompatibleClient` no longer carries OpenCode special cases.
- Live model discovery from the public `GET /inference/v1/models` (verified HTTP 200,
  87 models / 11 `-free`) — never a hardcoded list. Free tier filters the endpoint's
  own `-free` ids; the models path is derived from the chat base (family-root
  derivation, `.../inference/openai/v1` → `.../inference/v1/models`, because the
  per-family models path answers 404 — verified).
- The verified upstream restriction is surfaced, not bypassed: chat requests answer
  **HTTP 403 FreeTierError** ("OpenCode's free tier can only be used from within
  OpenCode") for any non-official client. Anvex does NOT reproduce the official
  client fingerprint (the opencode-free-9router approach — UA `opencode/<v>`,
  `x-opencode-client/-session/-request`, `Bearer public`, forced tools quartet —
  is impersonation and out of bounds). Test-connection reports both facts:
  discovery works (N models), chat is blocked upstream (exact status + verbatim
  upstream message + pointer to the Console provider).
- New `AiError.Kind.ACCESS_RESTRICTED` (403) split from `AUTH` (401) so the UI and
  fallback router can tell a policy refusal from a bad key.

### OpenCode Console (API key) — the permitted path
- `OPENCODE_ZEN` relabeled "OpenCode Console (API key)" (enum name unchanged;
  persisted rows keep decoding). Default base URL moved to the current documented
  `https://opencode.ai/inference/openai/v1`; legacy `/zen/v1` rows still work.
- Auth per docs: `Authorization: Bearer <console API key>`. Key verification at
  zero token cost — the models endpoint validates the bearer (bogus key → 401
  "Invalid credential", verified live). A missing key is refused before any request
  (the public endpoint would otherwise 200 and a "verified" claim would be false).
- No quota/limit claims: availability and limits of free models may change without
  notice (said so in the provider UI, EN + FA).

### Security & privacy
- Fixed honest User-Agent `Anvex/4.4 (Android; +https://anvex.app)` for OpenCode
  traffic via an isolated OkHttp interceptor — asserted by test to never equal or
  start with the official client's UA and to carry no `x-opencode-*` headers.
- API keys remain AES-GCM/Keystore-encrypted at rest; `toString` redaction covered
  by tests; no keys, auth headers or prompts are logged.

### Tests
- New `OpenCodeClientTest` (27 tests): model-list parsing + discovery (both tiers),
  response parsing, SSE streaming, reasoning deltas, 401/403/429/5xx mapping,
  malformed JSON/SSE, timeout, silent cancellation, persistence round-trip,
  secret redaction, anti-impersonation assertions, factory dispatch + shared-client
  regression. Tool-call parsing: N/A by design (text-based agent protocol).
- All existing suites re-run (see docs/CHANGES_V4_4_1.md for the exact matrix).

### Also
- In-app version bumped to 4.4.1 / versionCode 66 (was 4.3.0 / 65).

## Unreleased - review round 2 (NOT COMPILED)

### Blocking problem found (not fixed here)
- The `build` source packages are missing from this ZIP: `core/android/.../build/` (`ToolchainManager`, `AndroidBuildEngine`, `SafeDirUnzip`) and `app/.../ui/screens/build/` (`BuildScreen`). `AppGraph`, `HarnessBridge`, `ReliabilityRepository`, `AgentIdeBridge`, `BuildViewModel` and `AnvexApp` reference them, so the project cannot compile until they are restored.
- Root cause: `.gitignore` contained a bare `build/` rule, which ignores source packages named `build`. Patterns are now anchored (`/build/`, `/app/build/`, `/core/*/build/`).

### Security
- Agent `fetch`/`search`: addresses are now validated at connect time through an OkHttp `Dns` hook (closes the DNS-rebinding gap between the pre-check and the real connection).
- AI shell allowlist: globs, recursive `grep`, and `find -fprint/-fprintf/-fls` are rejected so `cat *` / `grep -r` cannot bypass the sensitive-path check.
- Skill install from GitHub: owner/repo validated, tarball download streamed with a hard 20 MB cap (was fully buffered), default branch resolved via `HEAD`, tar reader stops at the end-of-archive marker.
- Harness server: rejects `Sec-Fetch-Site: cross-site|same-site` requests and sends `Cross-Origin-Resource-Policy: same-origin`.
- Release build: ProGuard now keeps every `@JavascriptInterface` method (the old rule covered only the preview package, so the Harness bridge would break under R8).
- `RuntimeBootReceiver` is no longer exported.

### UI
- Composer: tool buttons scroll horizontally and the Send button is pinned at the end (it was pushed off-screen on narrow phones).
- Root-cause fix for the large empty bands at the top/bottom of screens: the root `Scaffold` padding is now consumed, so per-screen Scaffolds no longer add the system-bar insets a second time; `ProjectScaffold` also consumes its bar padding so `imePadding()` does not add it again above the keyboard.

### Tests added (also not run)
- `RuntimeSecurityTest`, `AgentToolExecutorTest`, `SkillGitInstallerTest`.

## 4.3.0 — Chat experience, conversations, providers, skills & MCP (NOT YET COMPILED)

> Status: source-level changes only. These changes have not been compiled, unit-tested, or
> run on a device. Run `./gradlew :core:engine:test :core:android:testDebugUnitTest :app:assembleDebug`
> and a device smoke test before treating any item below as working.

### Rename
- The app is renamed from BoltDroid to **Anvex**. Package is now `com.anvex.app` (new applicationId),
  so this build installs next to the old one but does not carry its data. Protocol tags (`boltArtifact`,
  `boltAction`, `boltMemory`) and the bolt.diy attribution are unchanged.

### Chat
- Markdown rendering for assistant replies (headings, lists, quotes, bold/italic, inline and fenced code, RTL-aware).
- Code blocks with language label, copy button, and a Run button for Python blocks.
- Regenerate the last reply (replaces the reply instead of duplicating history).
- Read aloud (TextToSpeech) and voice input (SpeechRecognizer) from the composer.
- Camera capture into attachments; PDF pages rendered to JPEG for vision models (first 6 pages).
- Share sheet: text and files from other apps go to a chat you choose or a new chat.
- Web search toggle (Anthropic and Gemini native search) with a Sources list appended to the reply.
- `/image <prompt>` generates an image (OpenAI and Gemini image endpoints).
- "Ask another model" compares a reply with another configured provider.
- Active model name shown in the composer.

### Conversations and projects
- Search across chat titles and message text.
- Pin / unpin chats (stored in SharedPreferences, no database migration).
- Share a chat as Markdown (API key patterns redacted before export).
- Project instructions and text files (under 200 KB each) added to every chat in the project.

### Skills & MCP
- New "Skills & MCP servers" screen: install skills from ZIP or a public GitHub repo; enable, disable, remove.
- Enabled skill instructions are added to the system prompt (capped at 8,000 characters).
- Add MCP servers (HTTPS, optional bearer token encrypted with Android Keystore); test connection; cache tool list.
- The assistant can call MCP tools only when the user allows calls for that server, and each call
  requires an "Allow once" approval. Calls time out and are denied after 120 seconds.
- MCP tool results are untrusted and capped at 8,000 characters.

### Export reliability
- Wider phrases detect file requests ("give me the source", "as a file", "فایل بده").
- If a requested file is not delivered, the app asks the model once more with an explicit correction.

### Accessibility
- Icon buttons raised to the 48 dp minimum touch target.

### Source and CI hardening (2026-10-14)
- Fixed the Linux Gradle wrapper failure caused by CRLF line endings; added `.gitattributes` rules to preserve shell/batch line endings on future checkouts.
- Repaired the static audit's stale references to the removed watchdog receiver and an outdated font resource path. The source audit now passes 65/65 checks; Room schema/migration verification passes.
- Added `tools/verify_launcher_icon.py` to validate Manifest wiring, adaptive icon layers, per-density PNG dimensions, and (in CI) pixel equality between the committed icon resources and files packaged into the generated APK.
- Hardened the GitHub workflow: Android SDK packages are installed explicitly, the debug build starts clean, tests run before packaging, and the published APK is accompanied by a build-provenance file and SHA-256 checksum. Artifact names are unique per workflow run/attempt to reduce stale-download confusion.
- Preferred release-signing properties are now `ANVEX_*`, with `BOLT_*` compatibility aliases; partially supplied signing settings fail rather than silently producing an unsigned artifact.
- Updated stale build/font/schema documentation and stopped embedding private implementation plans and generated machine-specific audit reports inside the app's downloadable source mirror.
- Verification limitation: this environment cannot resolve `services.gradle.org`, so Gradle distribution download, JVM tests, APK compilation, and on-device smoke tests could not be run here. The CI workflow now performs these build-level checks on an online runner. No compiled-build or device-test success is claimed in this ZIP.

### PFP audit (2026-10-14)
Checked against the PFP coding-rules, architecture and security standards, with the PFP scanner.
- Fixed: Room entity leaked into the UI layer (`TaskRunEntity`); the UI now uses `TaskRunItem` from `:core:model` via `observeTaskItems()`.
- Fixed: 8 silent `catch (_: Exception)` blocks now log a non-fatal warning with the exception type.
- Fixed: unused sealed error hierarchy (`AnvexError`) removed.
- Changed: the generated source mirror (`app/src/main/assets/source/`) is no longer versioned; CI generates it before tests and before the APK build.
- Open debt (not fixed in this pass): about 40 `!!` operators to review; `runBlocking` in `HarnessBridge` (WebView bridge threads) and in `AppGraph` providers; three oversized files (`ChatScreen.kt`, `HarnessBridge.kt`, `ChatSession.kt`); `WorkspaceRuntime` is referenced only by tests; no `PFP_PRODUCTION_COMPLIANCE.json` yet, which needs device evidence.

### Device-test fixes (round 1)
- Persian UI: the app language now shows Persian labels for the main screens (Settings, Home, chat, providers, skills and MCP), not only right-to-left layout. Untranslated strings and dynamic text fall back to English.
- Settings grouped into Appearance, Editor, AI, Assistant, Workspace, Storage & data, Developer, About.
- Terminal entry in Settings > Workspace opens a shell for the chosen project.
- About shows the developer name.

### Maintenance (review follow-up)
- Source mirror limited to source: vendor bundles, fonts, `tools/`, `docs/audit/` and `.github/` are no longer copied into the APK.
- JetBrains Mono is stored once (assets) instead of twice; Compose loads it from assets.
- Removed the unregistered `AgentWatchdogReceiver`.
- Foreground agent service handles the Android 15 `dataSync` time limit (`onTimeout`).
- `ExtensionsViewModel` moved to the `vm` package.
- Product-internal `Bolt*` names renamed to `Anvex*`; protocol names (`BoltAction`, `BoltActions`, `BoltStreamParser`) kept.
- Added unit tests for MCP target packing and the MCP approval broker.

### Known limitations
- Python sandbox (Pyodide) loads from a pinned CDN, so the first run needs network. Python code can
  reach the network through the page; no file access is granted.
- MCP OAuth is not supported; bearer tokens only. Streamable HTTP only; stdio is not possible on Android.
- Composer and message action rows may be cramped on narrow (≈360 dp) screens.
- Sources in replies are plain text, not clickable links.
- Performance on low-end devices has not been measured.


## 4.2.2 — Hardening, audit repair, build & verification gates

First verified compile of the v4.2 codebase on Windows build host:
- **Build & test verification:** All 244 JVM tests passing across modules (core:model 0, core:engine 150, core:ai 39, core:android 47, app 8; 0 failed). Room schema v12 and migrations 1->12 intact.
- **Artifact packaging fixes:** Secret scanning in ArtifactPackaging.kt now matches the full key prefix for quoted secrets (e.g. keyPassword) and scans generated document text as config-style content (generated.env) to detect unquoted bare assignments.
- **Debug APK built:** `app-debug.apk` verified (27.2 MB) packaging offline harness assets (jszip.min.js, index.html, bridge.js, offline fonts).
- **Audit tooling repaired on Windows:** `tools/deep_audit_30.py` (PASS 63/63 with UTF-8 encoding pinned), `tools/verify_build_environment.py` (READY with Windows batch wrapper support), `tools/verify_room_schema_snapshots.py` (PASS). Automated release gate in `tools/final_release_audit.py`.
- **Documentation corrections:** Truthful documentation alignment for offline assets (JSZip, JetBrains Mono fonts), AgentToolExecutor bounded execution loop, and version metadata.
- **Version bump:** Advanced to 4.2.2 / versionCode 64 across Gradle configuration, Settings About row, harness bridge, and README.
- **Device testing status:** Physical-device smoke test still pending (no device on build host).

## 4.2.1 — fixes from first device test

- Source ZIP no longer aborts on a "likely secret": detection now ignores variable references and placeholders, and real literals are redacted (file + line reported on the file card) instead of blocking delivery.
- Thinking level is a dropdown in the composer; quick prompts only on an empty chat; timestamps no longer monospace; searchable model picker.

## 4.2.0 — Memory, real files, tools, Nebula UI

### Fixed
- **Compile blocker:** `tools/build_source_mirror.py` and `ArtifactHygienePolicy` excluded every path segment named `build`, silently removing the `core/android/.../build/` package (ToolchainManager, AndroidBuildEngine, SafeDirUnzip) and `screens/build/BuildScreen.kt` from exported sources. Both filters now only drop Gradle output directories; the missing classes were rebuilt against every existing call site.
- **ZIP request produced HTML:** chat sessions used a system prompt that never described the `artifact` action, and the intent classifier treated "make a ZIP" as a plain file edit. New `UserIntentClassifier.requestsExport` + `SystemPromptFactory.buildAssistantPrompt` (delivery rules, export contract).
- Assistant messages consisting only of actions were sent back to the provider with empty content (rejected by Anthropic-style APIs); history now carries a short action summary.
- Per-action timeout raised 30 s → 60 s (large ZIP/PDF generation).

### Added
- Long-term memory (`MemoryRepository`, `<boltMemory>` tag protocol, `MemorySafety` secret filter, Memory screen). Untrusted web content can never add memories.
- Agent tools `read` / `list` / `fetch` / `search` with a bounded multi-round loop (`AgentToolExecutor`).
- Custom instructions and web-tools toggle in Settings.
- `DeliverableCard` (Save to Downloads / Share) for generated files.
- Nebula theme (new default; Dynamic color now opt-in).

### Not verified
- Sources were reviewed statically; no Gradle/Android SDK was available for a compile or device test. Run `./gradlew :core:engine:test :core:android:testDebugUnitTest :app:assembleDebug` first.

## 4.1.0 — Nebula (offline ZIP + workspace downloads)

The v4.1 release makes the web harness **fully offline** and hardens the two
download paths that the preview WebView can actually use. `blob:` URLs and
`URL.createObjectURL` remain banned — the WebView blocks both — so every byte
moves as base64 over the JS bridge or as an attachment over the loopback server.

### ZIP export via the bridge (MediaStore.Downloads)
- `HarnessBridge.saveZip` rewritten to the canonical MediaStore contract: insert
  into `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `IS_PENDING=1`, stream
  the decoded bytes through `openOutputStream`, then flip `IS_PENDING=0`.
  Failed writes delete the pending row instead of leaving an empty husk.
- Hardened payload validation: strict base64 decode, empty/oversize guards and
  the `PK` magic check now run before any MediaStore insert; failures are logged
  (`Log.e("HarnessBridge", "saveZip failed", e)`) and surfaced through the
  `zip_error` harness event.
- `shareZip` keeps the same storage pipeline (`decodeAndStoreZip` now routes
  through the identical `MediaStore.Downloads` helper).

### Offline harness assets (no CDN anywhere)
- JSZip 3.10.1 is bundled at `assets/harness/jszip.min.js` and referenced as
  `./jszip.min.js` (script tag in `index.html` + local dynamic fallback in
  `bridge.js`). The jsdelivr CDN loader was removed entirely.
- JetBrains Mono (400/500/700, SIL OFL) is bundled at
  `assets/harness/fonts/*.ttf` with `@font-face` in `styles.css`; the Google
  Fonts `<link>` was removed. `HarnessWebServer` whitelists `/harness/fonts/`.
- `HarnessWebServer` whitelist extended for `jszip.min.js` and the font files.

### Workspace downloads over loopback HTTP
- New route `GET /__boltdroid__/download?path=<relative>` in
  `HarnessWebServer`: streams a workspace file (e.g.
  `.boltdroid/attachments/<sha>…BoltDroid….zip`) as
  `application/octet-stream` with `Content-Disposition: attachment`. The server
  binds 127.0.0.1 only (unchanged); paths are percent-decoded without
  `'+'→space` mangling, must be relative, must not contain `..` or `\`, are
  canonicalized, and must stay inside the workspace root (zip-slip guard).
  400/403/404 for unsafe/escaping/missing paths.
- `HarnessScreen` wires the project workspace root into the server.

### Test surface + housekeeping
- `index.html` ships a self-test panel: one button drives the full
  JSZip → base64 → `Android.saveZip` export, the other downloads a workspace
  file through `/__boltdroid__/download` (HEAD probe + `<a>` click, path
  auto-prefilled from the first `.boltdroid/attachments/` entry).
- `gradle.properties`: `org.gradle.java.home=/home/z/jdk` removed — the JVM now
  comes from `JAVA_HOME`/`PATH` (JDK 17+) so the tree builds on any machine.
- Version `4.1.0` / `versionCode 61` (Settings row + `bridge.version()` updated).

## 4.0.0 — Harness

The v4.0 release turns BoltDroid into a full on-device agent harness. It also,
importantly, makes this source tree *actually buildable*: the v3.3.9 archive
referenced platform styles that do not exist, was missing three referenced
source files, and contained multiple packaging-corrupted sources. All of that
was repaired and then verified by a real Gradle build and the full JVM test
suite (engine/AI/Android/app) before release.

### GOAL 1 — audit fixes
- MainActivity: bounded single-recreate locale application (guard + LocaleManager on API 33+) and SYSTEM-mode RTL derived from the resolved configuration.
- network_security_config: HTTPS fail-closed; cleartext only for loopback.
- BackupScreen: CharArray password state, zero-filled after every operation.
- ChatScreen: paired take/release persistable URI grants, contentResolver MIME detection, cached pending-changes snapshot.
- ProvidersScreen: parenthesized fetch-models enable condition.
- Settings: version 4.0.0, destructive-action confirmation dialogs.
- Manifest: dataExtractionRules + fullBackupContent wired.
- Theme: real Material Components `Theme.Material3.DayNight` (the old themes referenced non-existent platform styles).
- NewProjectHandoff thread safety; HistoryScreen composite keys.

### GOAL 2 — harness
- New OPENCODE-style web harness (offline React + Babel, Linear/Vercel dark, JetBrains Mono, 8pt grid) served by a loopback whitelist server.
- HarnessBridge JS protocol: agent turns, files, watcher, diagnostics, terminal, git, skills, MCP, build, ZIP export.
- GitLite: pure-Kotlin local git (loose objects, SHA-1, zlib, real DIRC v2 index with stat data, refs, reflogs) — repositories remain readable by the desktop git CLI (tested).
- McpClient: MCP streamable-HTTP JSON-RPC 2.0 with Keystore-encrypted keys.
- LightDiagnostics + ProjectFileObserver; SkillGitInstaller (GitHub tarball); agent-event notifications from the foreground service.

### GOAL 3 — in-app builds
- Restored/created ToolchainManager, AndroidBuildEngine, SafeDirUnzip, BuildScreen, BuildViewModel: imported toolchain bundles, bounded cancellable Gradle execution with streamed logs, APK artifacts with FileProvider share/install.

### GOAL 4 — design system
- Professional dark (default) + Glass (Glassmorphism/Expressive M3) theme families; UiThemeStyle setting; JetBrains Mono bundled; 8pt token grid.

### GOAL 5 — cloud-only providers
- Ollama/LM Studio removed from the UI (deprecated, DB-v12-compatible), creation blocked, honest copy everywhere.

### GOAL 6 — ZIP download fix
- JSZip assembles the full v4 source in the page from chunked bridge reads; `Android.saveZip`/`shareZip` store it through MediaStore.Downloads (API 29+) or FileProvider (26-28); Snackbar offers Open/Share; plain browsers fall back to `data:application/zip;base64` (never `blob:`).

### Repairs to the packaged v3.3.9 source
- ProjectTemplates unescaped quotes; WorkspaceRuntime missing import; shell/terminal regex escaping; BoltApp `List<…]]` corruption; ChatSessionTest stripped brackets; DiffSheet missing constant; HomeScreen stripped annotations/imports; TaskContext payload API drift in AgentTaskBridge/AgentIdeBridge; PortableBackup JVM-safe Base64; BidiText surrogate-pair RTL detection; ProjectSearch skip semantics; stale migration-count test; echo-redirect safety carve-out.

- Version 4.0.0 / versionCode 60.

## 3.3.9 — Audit Reconciliation / CI Signing Hardening

- Reconciled external 32-finding audit against the actual v3.3.8 source.
- Fixed CI signing material handling: runner-temp keystore, masked secrets, environment-backed Gradle project properties, unconditional cleanup.
- Added `docs/audit/AUDIT_RECONCILIATION_V3.3.9.md`.
- Version 3.3.9 / versionCode 59.

## 3.3.8 — Deep Audit Hardening
- Deep-audit pass fixed LAN web-server wiring, command-translator safety classification, chat artifact execution wiring, build-script confirmation flow, secure legacy provider-header fallback, atomic artifact publishing, and documentation/test-count drift.
- Version 3.3.8 / versionCode 58.

## 3.3.7 — Final Audit Findings Remediation
- Deep-audit remediation pass: fixed LAN web-server wiring, shell translator safety/worst-tier reporting, chat artifact execution wiring, imported-project build confirmation flow, secure legacy provider-header fallback, and atomic artifact publishing.

- Added streaming workspace writes for ZIP import on real and SAF-backed filesystems.
- Upgraded generated PDF output to bidi-aware Android `StaticLayout` plain-text rendering.
- Added Gradle build-script risk scanning and an explicit confirmation control on the Build screen.
- Added separate runtime-capability visibility in Agent Workspace.
- Corrected release-facing verification language for test counts, historical APK size, and cleartext networking.
- Restored the app-owned build/toolchain/build UI source boundary required by AppGraph and navigation.
- Version 3.3.7 / versionCode 57.

## 3.3.6 — Chat Execution Contract

- Explicit project-mutation intent now selects an execution-required system contract.
- Prevents "I will fix it" acknowledgement-only responses for direct file-change requests.
- Conversational prompts such as greetings remain normal chat and do not force file generation.
- Added deterministic intent regression tests.

## 3.3.5 — Chat / Artifact Intent Fix

- Normal conversation, greetings, questions, and explanations no longer instruct the model to emit generated files.
- Artifact protocol is now conditional on an explicit project mutation or export/package request.
- Added regression coverage preventing the open project context from being treated as an implicit file-generation request.
- Version metadata aligned to 3.3.5 / versionCode 56.

## 3.3.4 — Final Release Audit Gate

- Added source-only `tools/final_release_audit.py` orchestrator.
- Release audit no longer depends on private PFP evidence.
- Room schema verification explicitly distinguishes migration PASS from missing-snapshot BLOCKED.
- Added machine-readable `docs/FINAL_RELEASE_AUDIT.json`.
- No BUILD_VERIFIED or runtime claim is made when Gradle/KSP is unavailable.

# Changelog

## 3.3.3 — Artifact Packaging Hardening
- Streaming ZIP output avoids retaining the complete archive in memory.
- Central generated-content secret policy for TXT/MD/JSON/PDF.
- JSON artifact validation.
- Artifact storage quota: 100 files / 500 MB per project.
- Version metadata aligned to 3.3.3 / versionCode 55.
- Artifact writes use sibling temporary files with sync-before-publish and cleanup on failure.
- ZIP streaming no longer sorts `ArtifactFileSpec` objects internally; callers provide deterministic path order.
- Quota enforcement protects the newly published artifact from eviction.

## 3.2.7 — External audit remediation
- Increased AI provider total call timeout from 10 to 30 minutes for long reasoning/streaming sessions.
- Added conservative, variant-aware Gemini capability detection.
- Added regression coverage for Gemini 2.5 variants, Gemini 3 variants, and unknown future families.
- Verified app-level Network Security Config is actually wired; retained private/loopback HTTP enforcement at provider validation because arbitrary private-IP HTTP cannot be narrowly allowlisted by static Android NSC.
- Verified Room 1→12 manual migration chain and migration tests; no fake/stale `1.json` was added. Current schema export remains a build-time artifact and must be regenerated by a successful Room/KSP build.
- Verified current Gradle/version metadata: AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Gradle 8.10.2, app 3.2.8/versionCode 52.

## 3.2.4 — Final UI/UX Deep Audit remediation
- Fixed compact-phone Chat layout: horizontal Thinking chips, reduced transcript top spacing, and truthful Chat mode labeling.
- Restored missing build/toolchain source files referenced by the navigation and DI graph.
- Added verified APK artifact presentation and sharing in Build.
- Full Gradle verification remains pending due to unavailable Gradle distribution in the audit environment.

## v3.2.1 — UI/UX Pro Max + PFP refinement

- Refined editor tab and bottom action hierarchy with semantic surface tokens.
- Improved mobile file explorer header and file-list grouping.
- Hardened Developer Center LAN UI: bearer tokens are never rendered or copied from the screen.
- Preserved PFP capability boundaries and durable runtime flows.
- Build/runtime verification remains pending until a network-capable Android/Gradle environment is available.

# v3.1.4 — Runtime, Build & Workspace Integration

- Added a real on-device Terminal UI backed by AndroidRuntimeGateway for app-private workspaces; SAF workspaces remain fail-closed.
- Added project-level shortcuts from Home for Editor, Terminal, Build APK and GitHub Sync.
- Connected ZIP Import to the Home UI.
- Added Android toolchain ZIP import with bounded extraction, traversal protection, manifest/ABI validation and atomic activation.
- Expanded Build UI with Debug/Release variants, durable cancellation, APK artifact listing and SAF export.
- Added GitHub Pull with explicit overwrite confirmation.
- Added editor file create/delete/rename/duplicate mutations to the persistent change journal.
- Improved History revert handling for rename operations.

Verification note: static audit passes 22/22; full Gradle build/test remains blocked in this environment because Gradle 8.10.2 cannot be downloaded.

# v3.1.3 — Agent Runtime & UI Integration Fixes

- Connected durable Agent shell/start execution to the controlled AndroidRuntimeGateway instead of the limited artifact command dispatcher.
- Added exact-action approval enforcement through CommandApprovalService and durable approval fingerprints.
- Added approval/resume flow for WAITING_APPROVAL agent tasks without granting broad shell permissions.
- Persisted dispatcher-backed agent file mutations into the project mutation journal.
- Added a real Agent Center task-creation UI with project selection, durable task status, approval and resume controls.
- Added Agent Center navigation from Settings.
- Added a dedicated Agent-specific system prompt so the planner no longer inherits preview-only runtime claims.
- Ordered provider fallback with the explicitly selected default provider first.
- Restored LAN settings during settings backup restore.
- Fixed the New Project dialog so its content scrolls and project type remains reachable on small screens.
- Corrected the stale About version display.

Verification note: source-level checks completed; full Gradle verification remains blocked in this environment because the Gradle 8.10.2 distribution is unavailable offline.

# v3.1.2 — Runtime Wiring & External-Audit Hardening

- Added the initial provider-backed durable Agent task/planner wiring; final runtime execution integration was completed in 3.1.3.
- Added fail-closed runtime gateway for app-private workspaces and SAF boundaries.
- Hardened interactive terminal AI syntax checks against CR/LF and shell escaping.
- Removed full LAN bearer token from status DTO; token is exposed only through the explicit in-memory accessor used by the local settings UI.
- Added provider-backed AgentPlanner using the existing boltAction streaming protocol.
- Added regression coverage for runtime security and planner parsing.

# Changelog

## v3.2.7 — External AI-layer audit remediation

- Restored the missing Android build/toolchain/build UI source boundary in the packaged source tree.
- Hardened provider runtime stringification so decrypted API keys and header values are never exposed by `toString()`.
- Fixed attachment routing to the last user turn using stable list indices instead of reference identity.
- Fixed Gemini base URL normalization, explicit safety/block handling, and model capability gating for thinking configuration.
- Avoided sending empty `Authorization: Bearer` headers to local OpenAI-compatible providers.
- Made SSE parsing preserve valid empty `data:` events and stopped exposing raw non-JSON error bodies.
- Made fallback model discovery surface the last provider error instead of silently returning an empty list.
- Added an app-owned, validated Android JDK/SDK/Gradle toolchain manager and evidence-gated cancellable Gradle runner.
- Added regression tests for provider secrets, attachments, Gemini URL/safety behavior, empty auth, SSE empty data and fallback errors.
- Version aligned to 3.2.7 / versionCode 51.

## 3.1.1 — External Security Audit Hardening
- Reject CR/LF and shell backslash escaping in AI command safety gates.
- HTML-script-safe escaping for preview entry paths.
- Disable Android OS app-data backup because Keystore keys are installation-bound.
- Surface Keystore credential decryption failures instead of silently using an empty key.
- Clarify loopback Host-header limitation.


## 3.1.1 — Complete 19–30 capability hardening
- Secure declarative plugin package installation and dependency constraints.
- Durable skill compilation runtime with capability gates.
- Foreground Agent lifecycle, watchdog and reboot recovery.
- Authenticated LAN status/tasks/logs UI and persistent web-port lifecycle.
- Advanced backup snapshot migration and device capability detection.
- Resource-scoped command approval.




## v3.2.0 — UI/UX foundation refresh

- Added Obsidian IDE design-system tokens and shared Bolt UI primitives.
- Applied the shared typography and shape system globally.
- Redesigned Home around projects, Agent workspace, provider state and developer quick actions.
- Promoted Agent Center to root navigation so durable agent capabilities are no longer orphaned.
- Redesigned Settings into grouped, scannable cards with explicit advanced/developer access.
- Refined project overflow actions and workspace navigation hierarchy.
- Updated `docs/DESIGN_SYSTEM.md` with the v2 visual/UX specification and verification checklist.
- Preserved PFP security, architecture and evidence requirements; UI/UX guidance remains advisory.


## v3.2.4 — Mobile IDE workflow polish

- Refined Agent Workspace into a clearer Goal → Runtime → Task Queue workflow.
- Added active-task and exact-approval status summaries.
- Improved task action sizing and mobile readability.
- Refined Terminal into a focused IDE-style console with connection state, copy output, safer spacing and a dedicated run control.
- Preserved app-private terminal boundaries and PFP runtime gates.
- Bumped app version to 3.2.4 (versionCode 47).



## 3.2.4 — Final UI/UX Deep-Audit Hardening
- Persist verified APK outputs outside disposable Gradle build workspaces.
- Added an APK Artifacts section to Build with secure share via FileProvider.
- Added mobile-safe scrolling and explicit Back navigation to GitHub/Build flows.
- GitHub access tokens now use password visual transformation and are cleared after save.
- Floating Agent status respects the bottom navigation safe area.
- Replaced remaining hard-coded artifact status colors with semantic Material 3 tokens.
- Final UI/UX P0/P1 remediation pass applied; real Gradle/device verification remains pending.


## 3.2.6 — Grok audit remediation

- Fixed missing coroutine launch imports and normalized the app to one NavHost instance.
- Rebuilt Android Gradle execution as a cancellable coroutine with a hard timeout and forced process cleanup.
- Build now uses the trusted app-managed Gradle binary instead of executing project-local `gradlew`.
- APK verification is limited to fresh `build/outputs/apk` artifacts.
- Agent file mutations now use the exact durable approval decision instead of an unconditional approval flag.
- Provider changes rebuild the live ChatSession without requiring process restart.
- Removed the Android 12+ incompatible repeating AlarmManager FGS watchdog.
- Added WebView lifecycle destruction and R8 keep rules for `@JavascriptInterface`.
- Project-local Node executables are no longer trusted.
- LAN status defaults to loopback with explicit LAN exposure and copy-token UX.
- Security and Known Limitations documentation now describe the real process/build/runtime model.


## 3.2.6 — External audit remediation

- Fixed build imports, Chat provider lifecycle, Gradle cancellation, execution boundaries, WebView cleanup/R8, LAN exposure defaults, Node trust, runtime truthfulness, local HTTP provider policy, and version metadata.


## 3.2.6 — Persian language support
- Added persistent AppLanguage setting: System default / English / Persian (فارسی).
- Added Android per-app locale configuration for `en` and `fa`.
- Persian mode applies RTL across the Compose root and updates the application locale.
- Language is included in settings backup/restore.

## 3.3.4 — Build & Room Verification Gate
- Fixed Room migration-chain verification to parse declarations regardless of Kotlin property syntax.
- Added `tools/verify_build_environment.py` for an explicit Gradle/KSP environment gate.
- Added source documentation for verification gates.
- Room schema JSON remains authoritative and is never fabricated by audit tooling.

## 4.4.0 — round 3 (this ZIP)

> Verified in this environment with a real JDK17/Android SDK setup:
> `:app:assembleDebug` + JVM unit tests pass (see docs/CHANGES_V4_4.md for the
> exact commands and outputs). No on-device smoke test was performed here.

### Fixed (user-reported)
- **Persian language**: switching to فارسی in Settings now applies the platform
  locale (not just RTL). Root cause: the once-per-instance apply guard consumed
  the initial null emission, so the persisted choice never reached
  `LocaleManager`/legacy config. The apply now tracks the last applied value and
  re-runs on every real change. FA map grew 258 → 385 entries (0 untranslated keys).
- **Settings redesigned**: six accent-coded categories (AI / Memory / Workspace &
  build / Appearance / Data & security / Developer & about), sorted rows, and the
  About block credits "developer farzad sholeh".
- **Terminal**: quick access stays available from Home, Settings and the project
  menu; `node`/`npm`/`npx` typed in the terminal are now dispatched by the
  configured Node backend instead of failing in the bare shell.
- **Node.js in the project**: two backends (Settings → Workspace & build) — the
  in-app compatibility layer (WebView engine, Node-style API subset, bundled
  `runtime/node_compat_prelude.js`) and the Termux RUN_COMMAND bridge.

### Fixed (architect review, round 2 ZIP was NOT COMPILED)
- Restored the missing `core/android/.../build/` package (ToolchainManager,
  AndroidBuildEngine, SafeDirUnzip) and `BuildScreen` from the documented contract.
- Fixed the pre-existing compile defects: missing `tr()` imports (25 files),
  `ProviderFeatures` missing `put`/`add` imports, the absent
  `Exception.toUserMessage()` mapping, duplicated `Routes.terminal`, and the
  unresolved `projects` binding in Settings.
- Removed the stale `app/src/main/assets/source/` mirror from the tree
  (git-ignored; regenerated by tools/build_source_mirror.py at build time).

### Added (architect review features)
1. Extended thinking end-to-end: `reasoning_content` / `thinking_delta` / Gemini
   thought parts → live `streamReasoning` → persisted `ChatMessage.reasony`
   (Room v13 + MIGRATION_12_13) → collapsible ThinkingCard.
2. Targeted edits: `PatchEngine` SEARCH/REPLACE blocks applied to existing files
   (fail-safe, file untouched on mismatch) + the artifact-contract prompt update +
   editor Canvas chips (Review/Fix/Add logs/Optimize/Port to language).
3. Artifacts: Mermaid (offline bundle) and sandboxed SVG preview + journal-based
   version history with restore on ArtifactCard.
4. Real builds: on-device toolchain runner + GitHub Actions cloud builds
   (dispatch, bounded polling, log tail, artifact APK download).
5. Auto-fix hand-off: build failures go to the assistant through the share intake.
6. Web tools already existed; Vision got a "send preview screenshot to chat" action.
7. Branch editing: editing an earlier user message re-sends it as a new turn,
   rolling back journaled file changes made after that message (nothing deleted).
8. Anthropic prompt caching (`cache_control` ephemeral on the system block).

### Added (providers)
- **Z.ai (GLM Coding Plan)** account login: the ZCode-CLI-compatible polling flow
  (init → authorize_url → poll → business token → coding-plan key), verified
  against 9Router ce4460e; single-flight, bounded, secret-free progress reporting;
  17 unit tests. Transport: Anthropic protocol at api.z.ai/api/anthropic with the
  9Router-verified GLM catalog fallback.
- **OpenCode Free** and **OpenCode Zen** providers: live model discovery
  (opencode.ai/zen/v1/models), `-free` filtering for the free tier, and honest
  chat-probe validation that surfaces upstream 403 bodies verbatim.

### UI/UX
- New AURORA theme (the untitled.zip web-prototype design language) alongside the
  existing three; full design-token mapping documented in
  docs/DESIGN_SPEC_WEB_PORT.md.
