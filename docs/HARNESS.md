# Anvex Harness (v4.0)

The harness is the OPENCODE-style agent workspace introduced in v4.0. It is a
static web app (offline React + in-browser Babel — no dev server, no bundler)
served from app assets by a loopback-only HTTP server and driven by a typed
JavaScript bridge into the real Android runtime.

## Architecture

```
HarnessScreen (Compose, :app)
  └── AndroidView WebView
        │  loads http://127.0.0.1:<port>/harness/   (HarnessWebServer, whitelist-only)
        │  window.Android = HarnessBridge.jsInterface  (addJavascriptInterface)
        ▼
   app.jsx / bridge.js / styles.css      (assets/harness/*)
        │  BoltHarness.call('sendPrompt', text) …      JSON in / JSON out
        │  window.__harnessEvent('{"type":"delta",…}')  Kotlin → JS events
        ▼
   HarnessBridge (:core:android)
        ├── ChatSession          durable agent turns → ActionDispatcher (real file/shell tools)
        ├── AndroidTerminalSession  interactive /system/bin/sh session
        ├── GitLite              local repository (real git object model)
        ├── ProjectFileObserver  FileObserver-based watcher → diagnostics events
        ├── LightDiagnostics     LSP-shaped static checks
        ├── PluginMarketplace / SkillGitInstaller  skill store
        ├── McpClient            JSON-RPC 2.0 over streamable HTTP
        ├── AndroidBuildEngine   real Gradle process with streamed logs
        └── decodeAndStoreZip    GOAL 6: MediaStore.Downloads (API 29+) / FileProvider (26-28)
```

## Bridge contract

- **Requests**: JS calls `Android.<method>(…)`; every method returns a JSON string
  `{"ok":true,…}` or `{"ok":false,"error":"…"}`. Quick lookups answer synchronously
  (WebView binder thread); long work (builds, skill installs, ZIP saves) is accepted
  immediately and reported via events.
- **Events**: Kotlin pushes `window.__harnessEvent('<json>')` (main thread):
  `turn_start`, `delta`, `artifact`, `turn_end`, `files_changed`, `file_event`,
  `diagnostics`, `terminal_output`, `build_log`, `build_end`, `zip_saved`,
  `zip_error`, `skill_installed`, `skill_install_error`.
- **Surface**: `state`, `hasProvider`, `listFiles`, `readFile`, `writeFile`,
  `deleteFile`, `analyzeFile`, `messages`, `sendPrompt`, `cancelStream`,
  `revertChange`, `git`, `terminalStart/Send/Stop`, `skills`, `setSkillEnabled`,
  `removeSkill`, `installSkillFromGit`, `mcpServers/Add/Remove/Tools/Call`,
  `buildStatus/Start/Cancel`, `saveZip`, `shareZip`, `sourceManifest`,
  `readSourceFile`, `version`.
- **Security**: paths are `PathSanitizer`-checked; writes are size-capped and
  journaled through the mutation repository; shell stays behind the approval
  boundary; MCP URLs must be HTTPS (loopback HTTP allowed); MCP keys are
  Keystore-encrypted at rest.

## ZIP export (GOAL 6) — the exact flow

1. JS loads JSZip from bundled `./jszip.min.js`.
2. `Android.sourceManifest()` returns the bundled v4 source manifest (path + size).
3. JS reads each file in bounded chunks via `Android.readSourceFile(path, offset, length)`
   (base64, ≤512 KB per chunk).
4. JSZip assembles the archive client-side and encodes it as base64.
5. `Android.saveZip(base64, "Anvex-v4.0-Harness-FIXED.zip")` — Kotlin decodes,
   validates the `PK` header and size budget, writes to **MediaStore.Downloads**
   (API 29+) or the app's external files dir shared through **FileProvider**
   (API 26-28), then emits `zip_saved` to JS and a Snackbar callback to Compose
   with **Open/Share** actions.
6. The secondary **Share ZIP** button routes the same payload through
   `Android.shareZip` → `ACTION_SEND` share sheet.
7. Plain-browser contexts (no bridge) fall back to a
   `data:application/zip;base64,…` download. `URL.createObjectURL`/`blob:` are
   never used — that is exactly the preview-WebView bug v4.0 fixes.

## Honest capability matrix

| Capability | Status | Notes |
|---|---|---|
| Durable agent loop | REAL | ChatSession → ActionDispatcher → real file writes; journal + revert; approval boundary for shell |
| Agent tool calls | REAL | The bolt action protocol drives file/shell/start actions; v4.2 AgentToolExecutor (`core/android/src/main/kotlin/com/anvex/core/android/tools/AgentToolExecutor.kt`) adds `read`/`list`/`fetch`/`search` tools in a bounded 4-round loop |
| Terminal | REAL | Process-based `/system/bin/sh` interactive session; no pty emulation (termios) on non-root Android |
| Git | REAL (local) | GitLite: init/status/add/rm/commit/log/branch/checkout/tag/diff/fileAtHead; loose objects; readable by desktop git. No clone/fetch/push/merge — remote sync stays on the GitHub REST repository |
| File watcher | REAL | android.os.FileObserver (recursive growth); API 29+ is natively recursive, 26-28 re-registers |
| LSP diagnostics | LIGHT | Static checks (JSON/HTML/CSS/JS brackets, TODOs) in LSP-shaped payloads; not a language server; no cross-file type analysis |
| Background service | REAL | AgentForegroundService (dataSync) + terminal-state notifications |
| Skills | REAL | ZIP (Agent Center) + public GitHub tarball install; no executable entrypoints ever |
| MCP | REAL (remote) | Streamable-HTTP JSON-RPC 2.0; stdio servers unsupported on Android |
| In-app APK build | REAL | Requires an imported Android-compatible toolchain bundle (docs/BUILD.md); bounded, cancellable Gradle process with live logs |
| ZIP source export | REAL | Bridge + MediaStore/FileProvider; browser fallback via data: URL |

## Files

- `app/src/main/assets/harness/` — index.html, styles.css, bridge.js, app.jsx
- `app/src/main/assets/source/` — the bundled v4 source mirror + `source-manifest.json`
  (regenerate with `tools/build_source_mirror.py`; the mirror excludes itself, so
  the exported ZIP contains everything except the mirror directory)
- `core/android/.../harness/HarnessBridge.kt` — the bridge
- `core/android/.../server/HarnessWebServer.kt` — the loopback static server
- `app/.../screens/harness/HarnessScreen.kt` — the Compose host
