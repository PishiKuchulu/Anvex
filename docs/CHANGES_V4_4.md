# CHANGES — Anvex 4.4.0 (round 3)

This file lists every changed/added/removed file versus the delivered round-2 ZIP
(`Anvex-review-r2.zip`), with the reason for each significant change. All build
and test claims in this file were executed in THIS environment (commands below).

## Verification commands and observed results

Environment: JDK 17.0.20 (Temurin), Android SDK (platforms;android-35,
build-tools;35.0.0), Gradle 8.10.2, on a 4 GB / 2-core box.
`gradle.properties` was retuned for that profile (smaller heaps, parallel off).

```
./gradlew :core:model:compileKotlin :core:engine:compileKotlin :core:ai:compileKotlin
./gradlew :core:engine:test :core:ai:test :core:model:test
./gradlew :core:android:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin
./gradlew :app:assembleDebug
./gradlew :app:lint
```

Observed (2026-10-15, this session):
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (2m) — `app-debug.apk` produced (28.4 MB).
- `:app:compileReleaseKotlin` → BUILD SUCCESSFUL.
- `:app:lint` → **BUILD SUCCESSFUL** (0 errors, 57 warnings) — after the three
  documented false-positive suppressions (see below).
- Unit tests — **all passing**:
  - `:core:ai:test` 52 tests (incl. 17 new Z.ai coordinator/protocol tests)
  - `:core:engine:test` 189 tests (incl. 13 new DiagramPreviewTest + 7 new
    PatchEngineTest)
  - `:core:model:test` unchanged, passing
  - `:core:android:testDebugUnitTest` 60 tests (incl. new CloudBuildRepositoryTest
    and RuntimeSecurityTest — round-2 had never executed them)
  - `:app:testDebugUnitTest` passing
- NOT performed here (no device): on-device smoke test, live Z.ai account login,
  live OpenCode authenticated chat (the endpoints were probed unauthenticated:
  models list 200; free chat 403 RegionError — surfaced verbatim in the UI).

## Restored (round-2 ZIP could not compile)

| File | Why |
|---|---|
| `core/android/.../build/ToolchainManager.kt` (new) | Missing package referenced by AppGraph/BuildViewModel/HarnessBridge/AgentIdeBridge/ReliabilityRepository; rebuilt from docs/BUILD.md contract. Also exposed `ToolchainStatus.installed` alias used by pristine call sites. |
| `core/android/.../build/AndroidBuildEngine.kt` (new) | Real Gradle process runner (app-managed toolchain or acknowledged project wrapper); honest result reporting. |
| `core/android/.../build/SafeDirUnzip.kt` (new) | Zip-slip/zip-bomb-safe extraction with hard caps. |
| `app/.../screens/build/BuildScreen.kt` (new) | Missing route target `build/{projectId}`; toolchain import + variant + live log + artifacts. |
| 25 × `tr()` imports across app screens/components | Round-2 dropped every `com.anvex.app.i18n.tr` import; re-inserted by script. |
| `core/engine/.../ErrorMessages.kt` (new) | ChatSession imported `toUserMessage()` which never existed. |
| `ProviderFeatures.kt` | Missing `put`/`add` kotlinx imports (pre-existing defect). |
| `AnvexApp.kt` | Removed the duplicated `Routes.terminal` (conflicting overloads). |
| `TaskRunItem` / `ReliabilityRepository` | DeveloperCenterScreen referenced `attempt/maxAttempts/nextRunAt/resumable` — fields added to the model and mapped from real columns. |
| `core/.../session/ChatSessionTest.kt` | Round-2 corruption: `storedessage.id]` → `stored[message.id]`; missing `projectIdsMatching` override. |
| `app/.../Typography.kt` | `androidx.compose.ui.text.platform.Font` → `androidx.compose.ui.text.font.Font` (API moved). |

## Removed

| Path | Why |
|---|---|
| `app/src/main/assets/source/` | Stale 4.2 MB in-APK source mirror (architect review item: duplicates every source edit). Already git-ignored; regenerated at build time by `tools/build_source_mirror.py`, which stays. |

## Fixed (user-reported)

| Area | Change |
|---|---|
| Persian language | `MainActivity.kt`: the once-per-instance apply guard consumed the null initial emission, so a persisted فارسی choice never reached `LocaleManager`/legacy config — RTL flipped but text stayed English. Now tracks `lastAppliedLanguage` and applies on every real change. |
| Persian coverage | `i18n/Strings.kt`: FA map 258 → 385 entries; 0 untranslated `tr()` keys. |
| Settings | `SettingsScreen.kt` rewritten: six accent-coded categories, sorted rows, real settings only; `SettingsViewModel.projects` added (fixes the unresolved `projects` binding). |
| Developer credit | About block: "developer farzad sholeh" (settings, per user request). |
| Terminal | `TerminalScreen.kt`: `node`/`npm`/`npx` dispatch by backend setting; `dispatchNodeCommand()` with IN_APP compat execution and Termux forwarding. |
| Node.js | New `NodeCompatRuntime` (WebView-engine Node-style layer + bundled `runtime/node_compat_prelude.js`) and `TermuxBridge` (RUN_COMMAND); `NodeBackend`/`termuxBridgeEnabled` settings; manifest queries + permission. |
| Android 13+ notifications | `MainActivity` requests POST_NOTIFICATIONS at first launch (architect item 12). |
| GitLite | Added `rmCached()` (`git rm --cached` semantics) alongside `rm` (architect item 8 note). |
| LAN exposure | Honest HTTP warning text in Settings → Data & security (TLS out of scope, documented). |

## Added (architect-review features)

1. **Extended thinking**: `StreamEvent.Reasoning` in `AiTypes.kt`; parsing in
   `OpenAiCompatibleClient` (reasoning_content), `AnthropicClient`
   (thinking_delta), `GeminiClient` (thought parts); `ChatSession` live
   `streamReasoning` + persisted `ChatMessage.reasony` (Room v13 +
   `MIGRATION_12_13` + schema 13.json); `FallbackProviderClient` passthrough;
   `ThinkingCard.kt` UI (streaming + persisted states).
2. **Targeted edits**: `PatchEngine.kt` + `ActionDispatcher` SEARCH/REPLACE
   integration (fail-safe: file untouched on mismatch) + `SystemPromptFactory`
   contract; `EditorScreen` Canvas chips (Review/Fix/Add logs/Optimize/Port to
   language) via the share intake.
3. **Artifacts**: offline Mermaid v12 bundle + dark theme; sandboxed SVG pages
   (`PreviewHarness`, `StaticPreviewServer`); journal-based version history with
   restore (`ArtifactCard`, `ChatViewModel.versionsFor/restoreVersion`).
4. **Real builds**: on-device toolchain runner; GitHub Actions cloud builds —
   `CloudBuildRepository.kt` (dispatch/poll/log-tail/artifact APK) + BuildScreen
   cloud section (workflow template shipped in code).
5. **Auto-fix**: build failures hand off to the assistant through the share
   intake (BuildScreen) — user approves what is sent.
6. **Vision**: PreviewScreen "send screenshot to chat" (capture → PNG → share
   intake as a vision attachment; honest failure states).
7. **Branching**: `ChatViewModel.branchFrom()` — edit an earlier user message;
   journaled mutations after that message are rolled back (newest first),
   then the edited text is sent as a new turn; nothing is deleted. Edit
   affordance on user bubbles + note chip in ChatScreen.
8. **Prompt caching**: `AnthropicClient` `cache_control: ephemeral` on the
   system block (genuine Anthropic kind only).

## Added (providers)

- `ZAI_ACCOUNT` / `OPENCODE_FREE` / `OPENCODE_ZEN` kinds in `Provider.kt`
  (base URLs, protocol flags, `requiresAccountLogin`/`isCredentialFree`).
- `core/ai/zai/ZaiProtocol.kt` + `ZaiAccountCoordinator.kt` — the ZCode
  CLI-compatible polling flow verified against 9Router ce4460e (init →
  authorize_url → poll → business token → coding-plan key), single-flight,
  bounded, secret-free progress; 17 unit tests.
- `ProvidersViewModel.startZaiLogin/cancelZaiLogin` + `ZaiAccountCard` in
  ProvidersScreen (honest states; no fabricated quotas).
- OpenCode: live model discovery, `-free` filtering, chat-probe validation that
  surfaces upstream 403 bodies verbatim; per-kind credential UI.

## UI/UX (untitled.zip port)

- `UiThemeStyle.AURORA` + full palette/shapes/extras in `Theme.kt` (web-prototype
  design language; the three existing themes untouched). Token mapping
  documented in `docs/DESIGN_SPEC_WEB_PORT.md`.
- Categorized settings, Z.ai hero card, ThinkingCard, Canvas chips, version
  chips — all native Compose, no WebView reuse, no mock states.

## Lint suppressions (documented false positives)

- `JavascriptInterface` at PreviewScreen/HarnessScreen — bridges verified
  annotated; the detector cannot resolve types through `remember{}`/object
  literals.
- `ProduceStateDoesNotAssignValue` at ChatScreen.GeneratedImage — the producer
  assigns `value` directly; the detector false-positives on the delegated form.

## Known limitations

See `docs/KNOWN_LIMITATIONS.md` (v4.4 section) — the honest list, including what
was deferred (GitLite pack files, HMR, real TS checker, LAN TLS, full branch
switcher UI) and why.
