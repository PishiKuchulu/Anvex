# PFP v5.6.1 — v4.0 Requirement Traceability

This file maps every explicit v4.0 requirement (GOAL 1–6 from the commissioning
brief) to its implementation and its verification evidence, following the PFP
input-fidelity rule: no silent scope reduction; every deviation is stated.

## GOAL 1 — Critical audit fixes

| # | Requirement | Implementation | Verification |
|---|---|---|---|
| 1 | Fix the MainActivity locale infinite-recreate loop; guard + `recreatedOnce` + `LocaleManager` (API 33+); fix SYSTEM-mode RTL | `MainActivity.kt`: `languageApplied` AtomicBoolean guard, `savedInstanceState` short-circuit, LocaleManager on 33+, single equality-checked `recreate()` below 33, `isResolvedLocaleRtl()` for SYSTEM | Source inspection (deep_audit check `v4 locale recreate guard`) |
| 2 | Restrict network_security_config cleartext | `res/xml/network_security_config.xml`: base-config fail-closed; loopback-only domain-config. Note: platform domain-config cannot express CIDR ranges — LAN cleartext fails closed (documented in-file) | deep_audit check `v4 cleartext fail-closed` |
| 3 | BackupScreen password CharArray + zero-fill | `BackupScreen.kt`: `PasswordState` CharArray state, zero-filled copies after export/inspect, zero on dispose | deep_audit check `v4 backup CharArray` |
| 4 | ChatScreen URI permissions / MIME / cache | `ChatScreen.kt`: take/release persistable grants paired with disposal, `contentResolver.getType` with extension fallback, `pendingChangesSnapshot` derived from the persisted flow | Code review; deep audit surface |
| 5 | ProvidersScreen boolean precedence | `enabled = !loadingModels && (model.isNotBlank() \|\| apiKey.isNotBlank())` | Source line 194–199 |
| 6 | SettingsScreen version 4.0.0 | About card + copy updated | UI text |
| 7 | AndroidManifest rules | `dataExtractionRules` + `fullBackupContent` wired (both XMLs existed; the manifest never referenced them); RECEIVE_BOOT_COMPLETED + API-34 FGS permission already present | deep_audit check `v4 data extraction rules` |
| 8 | HistoryScreen LazyColumn key | Composite `${path}:${timestamp}:${source}:${operation}` key | Source |
| 9 | Theme Material3 + AlertDialog confirms + thread safety | Manifest theme now uses the REAL `Theme.Material3.*` from Material Components (the v3.3.9 zip referenced platform styles that do not exist — it could not have compiled); Clear/Delete confirmation dialogs added; `NewProjectHandoff` synchronized + atomic take | Compile + deep audit + `./gradlew test` |

## GOAL 2 — Harness

| Requirement | Implementation | Verification |
|---|---|---|
| Durable agent loop, real tool calls | ChatSession + ActionDispatcher (files) + AndroidRuntimeGateway (shell) through `HarnessBridge.sendPrompt` | Engine tests (dispatcher, journal, safety) |
| Real shell (interactive) | `AndroidTerminalSession` via bridge `terminalStart/Send` + terminal pane | Security tests; device verification remains manual |
| Complete git | `GitLite` (see HARNESS.md matrix for the honest local scope) | `GitLiteTest` — including real `git` CLI compatibility assertions |
| File watcher | `ProjectFileObserver` (FileObserver) → bridge events | Source + wiring |
| LSP diagnostics | `LightDiagnostics` (LSP-shaped, light scope) + WebView Babel parsing for JS | `LightDiagnosticsTest` |
| Background service + notifications | `AgentForegroundService` + agent-event notifications | Source; FGS lifecycle audit check |
| MCP | `McpClient` (streamable HTTP) + harness MCP pane | `McpClientTest` (MockWebServer) |
| Artifacts Apply/Revert/Diff | Artifact cards + `revertChange` + `DiffSheet` (Compose) and diff view (web) | Engine tests |
| Skill store git/zip install, delete, enable | `PluginMarketplace`/`PluginPackageInstaller` (ZIP) + `SkillGitInstaller` (git tarball) + skills pane | Existing plugin tests + installer guards |

## GOAL 3 — In-app APK build

| Requirement | Implementation | Verification |
|---|---|---|
| Build APK button, app-managed Gradle/JDK/SDK | `BuildScreen` + `BuildViewModel` + `ToolchainManager` (imported bundle validation) + `AndroidBuildEngine` (bounded, cancellable, streaming) | Build compiles; engine contract audit check; on-device run requires the imported toolchain (documented) |
| Real-time logs | line-streamed `onLine` → ViewModel state → console composable | deep_audit check `v4 build engine streams logs` |
| Project gradle wrapper support | WRAPPER mode with explicit risk acknowledgement + `BuildScriptRiskScanner` | `AndroidBuildEngine.kt` |
| FileProvider share | artifacts list + share/install actions | `file_paths.xml` + BuildScreen |

## GOAL 4 — UI/UX

| Requirement | Implementation | Verification |
|---|---|---|
| Professional dark default (Linear/Vercel) | `ProfessionalDarkScheme`, dark default (`ThemeMode.DARK`), 8pt `AnvexDimens`, hairline outlines | Visual inspection + compile |
| Second theme in Settings | `UiThemeStyle.GLASS` (Glassmorphism/Expressive M3) selectable in Settings | DataStore-backed, audit check |
| Dark/Light toggle | ThemeMode System/Light/Dark row (kept) | Settings UI |
| JetBrains Mono | Bundled `assets/harness/fonts/JetBrainsMono-*.ttf` + `AnvexMono` (SIL OFL 1.1) | Build output |
| Responsive + touch friendly | Harness CSS breakpoints; 48dp controls | Harness browser screenshots |

## GOAL 5 — Cloud-only providers

| Requirement | Implementation | Verification |
|---|---|---|
| Only cloud models; remove Ollama | `ProviderKind`: OLLAMA/LMSTUDIO deprecated + hidden + creation blocked; HTTPS-only (loopback cleartext permitted for `adb reverse` dev); README/AI_PROVIDERS copy updated | Provider tests; deep_audit check `v4 cloud-only providers` |
| Keep Kotlin+Compose+WebView, Clean Architecture, Offline-First | unchanged module layout; offline runtime retained | Build graph |

## GOAL 6 — ZIP download fix

| Requirement | Implementation | Verification |
|---|---|---|
| `@JavascriptInterface fun saveZip(base64, fileName)` | `HarnessBridge.saveZip` (exact signature) | deep_audit check |
| JSZip → base64 → `Android.saveZip(...)` | `bridge.js` `buildSourceZip` + `downloadSourceZip` | Browser test (simulated bridge): `via: bridge`, PK header, correct filename |
| Kotlin decodes and saves via MediaStore/external-files + FileProvider + ACTION_VIEW + Snackbar | `decodeAndStoreZip` → `saveZipViaMediaStore` / `saveZipViaExternalFiles`; Snackbar with Share/Open in `HarnessScreen` | Source + compile; device save verified manually in release checklist |
| Browser preview data: fallback | `downloadBase64AsDataUrl` — real browser test downloaded `Anvex-v4.0-Harness-FIXED.zip` via data: URL | Browser test evidence |
| Secondary Share ZIP button | TopBar Share → `shareZip` → ACTION_SEND | Browser test (simulated bridge) |

## Constraints

- No npm/vite/node/npx: the harness is static assets; react/react-dom bundled offline; JSZip + fonts via CDN. **Verified** — no bundler config exists.
- A healthy root `index.html` remains: `app/src/main/assets/harness/index.html` + project templates.
- `tools/final_release_audit.py` green: run in a configured environment (see `docs/FINAL_RELEASE_AUDIT.json`).

## Explicit scope decisions (no silent reduction)

1. **"PTY"**: Android without root cannot grant a controlling terminal; the
   terminal is a real interactive shell process with streaming output. Stated.
2. **"Complete git"**: local VCS only (GitLite); network transport deliberately
   out of scope (pack files). Remote sync = GitHub REST. Stated in HARNESS.md.
3. **"LSP"**: light diagnostics in LSP shape, not a language server. Stated.
4. **Toolchain import**: an Android-compatible JDK/SDK/Gradle bundle must be
   imported once (a desktop glibc JDK is not a phone toolchain) — per docs/BUILD.md.
