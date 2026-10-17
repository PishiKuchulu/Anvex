# Anvex v1.1 Audit (Phase 1 Re-Audit)

Date: 2026-09-08 · Auditor: v1.1 hardening pass · Scope: complete re-inspection of the
v1.0.0 source tree (`anvex-source.zip` == live tree, verified by recursive diff;
only `build/` artifacts differ) against the v1.1 MASTER FIX PROMPT and upstream
bolt.diy semantics.

Baseline verification performed before this audit (toolchain re-verified, prior
claims NOT trusted):

- `./gradlew --offline test :app:assembleDebug` — **PASSED** (50/50 unit tests green,
  debug APK produced offline). Toolchain: JDK 17 (toolchain dir), Gradle 8.10.2,
  AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 26.
- Test inventory: engine 42 (parser 9, translator 12, artifact runner 6, diff 4,
  zip 3, path 6, prompts 2), ai 8 (provider wire protocols via MockWebServer).

---

## 1. Current architecture (preserved)

| Module | Responsibility | Verdict |
|---|---|---|
| `:app` | Compose M3 UI (home/chat/editor/preview/providers/settings/debug), ViewModels | Sound, keep |
| `:core:model` | Pure-JVM domain types (Project, Chat, Provider) | Sound, keep |
| `:core:engine` | BoltStreamParser, ArtifactRunner, CommandTranslator, DiffEngine, PathSanitizer, ZipCodec, PreviewHarness, ContextBuilder, SystemPromptFactory, ProjectTemplates | Sound, keep (engine semantics need fixes below) |
| `:core:ai` | ProviderClient abstraction, OpenAI-compatible / Anthropic / Gemini adapters, hand-written SSE reader | Sound, keep |
| `:core:android` | Room, KeystoreCrypto, RealProjectFileSystem, repositories, StaticPreviewServer, ChatSession | Sound structure; ChatSession + DB config need P0 fixes |

Verified provider surface: OpenAI, Anthropic, Gemini, OpenRouter, DeepSeek, Ollama,
Mistral, Groq, LM Studio, Custom-OpenAI, Custom-Anthropic — all routed through three
real wire-protocol adapters (OpenAI-compatible family covers 8 kinds; base URLs and
headers configurable; connection tests perform real HTTP requests with 404/405
fallback to a minimal chat call).

## 2. P0 bugs (confirmed by code inspection)

### P0-1 ChatSession action race condition — CONFIRMED (HIGH)
`core/android/.../session/ChatSession.kt`
- `onActionClose` → `scope.launch { executeAction(...) }` is fire-and-forget.
- `runStream` finishes the HTTP stream, calls `streamParser.flush()`, then
  immediately builds `runner.summaryFor(...)` and persists the assistant message.
- Consequences observed in code: (a) persisted message can contain an **empty or
  partial artifact** (actions still running), (b) `filesRevision` bumps and
  `onProjectFilesChanged()` may fire before/after actions complete, (c) `close()`
  cancels the scope mid-action leaving partial file writes with no record,
  (d) no per-action timeout, no duplicate-action dedup, no partial-stream handling
  contract, (e) summary is attributed to `_state.value.currentArtifact` at an
  arbitrary moment (multiple artifacts collapse to the last one).
- Required fix: structured concurrency — queue actions, await every action
  (success/failure/cancellation/timeout) before session finalization; stream
  completion ≠ session completion.

### P0-2 Artifact before/after corruption — CONFIRMED (HIGH)
`core/engine/.../ArtifactRunner.kt` → `applyEffect` → `Effect.Write`:
```kotlin
if (effect.append) fs.appendFile(...) else fs.writeFile(...)
val before = fs.readFile(effect.path)      // read AFTER mutation
changes[effect.path] = PendingChange(..., before, fs.readFile(effect.path))
```
- For overwrite: `before == after` (diff always empty, revert is a no-op that
  destroys the original content). For append: `before` contains the appended text.
- `Effect.Move`/`Effect.Copy` produce **no change record at all** (no RENAME kind),
  so renames are not diffable and not revertible.
- `applyShellAction` maps `Tier.PARTIALLY` → `ActionStatus.COMPLETE` — dishonest
  status reporting (§9 of the fix prompt).
- `applyFileAction` (boltAction type="file") reads before write correctly, but
  there is no unified `FileMutation` transaction (path/operation/before/after/
  timestamp/source) and no timestamp/source tracking anywhere.

### P0-3 Room destructive migration — CONFIRMED (HIGH)
`core/android/.../AppGraph.kt` line ~20:
`Room.databaseBuilder(...).fallbackToDestructiveMigration().build()`
- Any future schema bump silently wipes projects, conversations, provider configs.
- DB is `version = 1`, `exportSchema = false` — no migration infrastructure at all,
  no migration tests. Required: remove destructive fallback, add explicit
  migrations, export schemas, add data-preservation tests.

### P0-4 Artifact ID compatibility — CONFIRMED (MEDIUM)
`BoltStreamParser.kt`: `val artifactId = "$messageId-${st.artifactCounter}"`
- Upstream bolt.diy currently also generates IDs (its `id` extraction is commented
  out) — but the system prompt contract (both upstream and ours) instructs the
  model to supply a stable `id` attribute and **reuse the prior identifier for
  updates**. Our generated-only IDs make artifact continuation/update linkage
  impossible: every artifact is treated as brand new.
- Required: respect supplied `id` (e.g. `<boltArtifact id="project">` →
  `artifact.id == "project"`), keep generated fallback when absent/blank, handle
  continuation (same id again), updates, references, and multiple artifacts.

## 3. Additional functional bugs found (not previously reported)

| # | Where | Bug | Severity |
|---|---|---|---|
| B-1 | ChatSession.runStream | **Duplicate user turn**: user message is persisted BEFORE `historyProvider()` runs, then appended again as the final turn → the user text appears twice in every request | HIGH |
| B-2 | ChatViewModel.readProjectFile | `runBlocking` + IO from a composable-driven call on the main thread — ANR risk for large files | MEDIUM |
| B-3 | PreviewScreen onConsoleMessage/onReceivedError | JSON built via string interpolation; a console message containing quotes/braces produces malformed JSON silently dropped by the VM parser | MEDIUM |
| B-4 | Sse.kt data-line handling | `.trim()` after `data:` strips ALL leading whitespace instead of the single leading space per SSE spec; `id:`/`retry:` silently dropped (should at least not reset state) | LOW |
| B-5 | CommandTranslator mkdir/touch/rm | Invalid paths silently dropped via `mapNotNull` — command reports "Created N directories" with N reduced, no error surfaced | MEDIUM |
| B-6 | ContextBuilder | Priority list only; no "recently changed files" input wired from the session (diffNote/openFilePath params exist but ChatSession never passes them) | LOW |
| B-7 | ZipCodec.import | Whole entry buffered in RAM (bounded 64 MB per entry — too generous for 4 GB devices); no duplicate-entry policy; no cancellation support | MEDIUM |
| B-8 | Editor | No large-file safeguards (no size cap, no read-only fallback), no file-size-based syntax highlighting cutoff check in CodeEditorField | MEDIUM |

## 4. Runtime & offline capability gaps (fix prompt §10–11)

- Preview React tier depends on **external CDN**: `@babel/standalone` from jsdelivr
  and bare imports from `esm.sh` (see `PreviewHarness.BABEL_CDN`, import map
  builder). No bundled React/ReactDOM/Babel. Offline React preview is impossible
  today; static HTML/CSS/JS works offline.
- No embedded JS engine evaluation exists (QuickJS/Hermes/esbuild-WASM were never
  prototyped). `docs/RUNTIME_EVALUATION.md` does not exist. Required by §10.
- Honest tier reporting exists in the system prompt (good) but must be backed by a
  real `CommandCapability`/`ProjectRuntime` surface (§40 future-ready interfaces).

## 5. Security review (details → docs/SECURITY_AUDIT_V1_1.md after fixes)

- KeystoreCrypto: AES-256-GCM, hardware-backed AndroidKeyStore key, randomized IV,
  base64(iv‖ct) at rest — sound. **No tests** (android-only object; needs interface
  seam for JVM tests).
- WebView: `javaScriptEnabled`, `domStorageEnabled` (needed); `allowFileAccess=false`,
  `allowContentAccess=false` (good); mixed content default = NEVER_ALLOW on API 21+
  (good); bridge `__anvex` exposed to **any page loaded** in that WebView
  (origin not validated); no Host-header validation on the localhost server;
  no `SafeBrowsing` explicit config.
- Filesystem: PathSanitizer (traversal/absolute/drive/null/255/`..`) +
  canonical-path containment re-check — solid, symlink escape covered by the
  canonical check.
- ZIP: entry-count (10k), per-entry (64 MB), total (256 MB) caps + zip-slip
  rejection + bounded buffer — solid, see B-7 for RAM improvements.
- Network security config: cleartext **denied** globally, allowed only for
  127.0.0.1/localhost loopback preview — correct.
- Manifest: only INTERNET + ACCESS_NETWORK_STATE; single exported launcher
  activity; `allowBackup=true` — API keys are Keystore-encrypted (not raw backup
  material) but DB rows (ciphertext) get backed up; acceptable, document.
  No logging of keys anywhere (grep-verified: no Log./println/printStackTrace).

## 6. Test gaps

- No tests for: ChatSession (race/ordering), artifact before/after regression,
  SSE spec-level parsing (fragmentation, multi-event chunks, CRLF, retry/id),
  Room migrations, KeystoreCrypto, import/export round-trip with security
  rejection, CommandCapability tiers, context builder bounds, diff UI semantics
  (RENAME), streaming cancellation.
- 50 tests exist; goal ≥ 100 meaningful tests in the same areas + the above.

## 7. Build & packaging status

- Baseline offline build verified this session (see header).
- Signing: release keystore property-injected (BOLT_KEYSTORE…), unsigned otherwise;
  previous v1.0 shipped a signed APK; must re-verify with apksigner for v1.1.
- `versionCode 1 / versionName 1.0.0` → bump to `2 / 1.1.0`.

## 8. Prioritized remediation plan (v1.1)

1. P0-1 ChatSession structured concurrency (ActionDispatcher + lifecycle).
2. P0-2 FileMutation transaction model, before-read-before-write everywhere,
   RENAME kind, honest PARTIAL status.
3. P0-3 Room migration infrastructure + tests, remove destructive fallback.
4. P0-4 artifact ID respect + continuation.
5. B-1..B-8 functional bugs.
6. CommandCapability + ProjectRuntime surfaces (§8/§9/§40).
7. Runtime evaluation doc + offline React (bundle Babel/React/ReactDOM assets,
   localhost import map) (§10/§11).
8. SSE hardening + error model completion (§16/§17).
9. WebView/server hardening (bridge origin validation, Host check, JSON building)
   (§12/§13).
10. Test expansion (≥ 100), docs, changelog, builds, APK verification.
