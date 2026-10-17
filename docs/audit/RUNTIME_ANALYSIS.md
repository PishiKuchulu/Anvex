# RUNTIME_ANALYSIS.md — Execution Runtime Strategy (Phase 1)

## 1. What the original runtime actually does (verified)

bolt.diy's project runtime is **StackBlitz WebContainer**: a proprietary Node.js runtime that executes *inside the browser page*, booted via `WebContainer.boot({ coep: 'credentialless', workdirName: 'project', forwardPreviewErrors: true })`. It provides:

- a virtual filesystem at `/home/project`
- process spawning (`npm install`, `npm run dev`, `node script.js`)
- an emulated zsh-like shell
- an internal HTTP layer that forwards dev-server ports to iframes used for preview
- error forwarding (`preview-message` events: uncaught exceptions, unhandled rejections)

## 2. Why WebContainer cannot be embedded in the APK (hard constraints)

1. **Legal/licensing**: WebContainer is a proprietary StackBlitz product distributed as a JS bundle licensed for use in browser pages; it is not offered as an embeddable library for native apps, and its terms do not permit redistribution inside an APK.
2. **Technical**: it requires cross-origin isolation (COOP/COEP headers + `SharedArrayBuffer`) on the exact origin serving it, and its boot process pulls remote resources. A WebView served from `http://127.0.0.1` in-app can technically satisfy COEP in some WebView versions, but relying on that is fragile across OEM WebViews and is explicitly not a supported embedding scenario.
3. **Functional**: even in the browser it cannot run native binaries; npm-based toolchains (Vite, rollup, esbuild binaries) are the very thing we would be trying to preserve.

**Decision (per the migration brief: replace with Android-compatible equivalents, never fake):** the Android port replaces the WebContainer with a purpose-built runtime stack:

## 3. The Android runtime stack (Anvex Runtime)

| Original capability | Android replacement | Status |
|---|---|---|
| Virtual FS at `/home/project` | Real per-project directory in app-private storage (`filesDir/projects/<id>`), path-sanitized | Full |
| `<boltAction type="file">` writes | `ActionApplier` — same semantics (mkdir -p parents, write full content, trailing newline, markdown-fence cleanup) | Full |
| `<boltAction type="shell">` | `CommandExecutor` — whitelist translation (mkdir, touch, echo>, rm, cp, mv, cat-ish listing) to FS ops; `npm install` recorded as dependency metadata; everything else → explicit Partial/Unsupported result | Partial by design |
| `<boltAction type="start">` (dev server) | Preview activation: embedded HTTP server (localhost-only) + WebView runtime | Equivalent outcome for previewable project types |
| WebContainer process spawning (npm/node/python) | NOT executed. AI shell/start actions are not spawned. The separate Android Build screen may explicitly launch a validated Gradle build using an Android-compatible toolchain. | Intentionally omitted for web runtime; Gradle is an explicit IDE build capability |
| Preview iframes on forwarded ports | Single localhost origin `http://127.0.0.1:<port>` served by the in-app `StaticPreviewServer` | Full for static; React via in-preview transpiler |
| `preview-message` error forwarding | WebView JS bridge: `window.onerror`, `unhandledrejection`, console interception → app Console panel | Full |

## 4. The JavaScript runtime question (QuickJS / Hermes / WebView)

The brief asked to evaluate embedded JS runtimes (QuickJS, Hermes) rather than assume one. Findings:

- **QuickJS**: real, embeddable, MIT-licensed. But to make it useful for *project preview* we would have to reimplement browser APIs (DOM, CSS layout, events, fetch) — effectively writing a browser engine. Not viable for preview; overkill for command translation (which is a tiny, well-understood whitelist).
- **Hermes**: optimized for React Native app code, not for executing untrusted user project code; integrates a full RN runtime for zero benefit here.
- **Android System WebView (Chromium/V8)**: ships on every device, is a genuine sandboxed embedded JS engine (separate process, no Android file access, site-isolated from app memory), and comes with the entire web platform (DOM/CSS/JS/canvas/fetch). Using WebView as the *embedded JavaScript runtime* is strictly better engineering for this use case than embedding QuickJS and reimplementing the web.

**Chosen architecture:** hybrid — pure-Kotlin engine for the artifact/action protocol (deterministic, testable), System WebView as the sandboxed JS execution environment for previews, served by an in-app localhost-only HTTP server. This satisfies "no Termux / no Node / no manually started server": all components initialize inside `AnvexApp.onCreate()` and the preview server lifecycle is bound to the preview screen + a foreground-aware lifecycle.

## 5. Preview capability tiers (honest, no fake success)

| Tier | Project shape | Mechanism | Offline? |
|---|---|---|---|
| **Supported** | Static HTML/CSS/JS (multi-file, relative links/scripts) | `StaticPreviewServer` serves files directly; entry `index.html` | Yes, fully offline |
| **Supported** | React (JS/JSX/TSX) single- or multi-file, package-style imports | Preview bootstrap loads `@babel/standalone` (cached asset) to transpile JSX/TS, and an import-map runtime resolving bare specifiers via esm.sh CDN | Online (CDN) — clearly labeled |
| **Partially supported** | Projects requiring `npm install` for bundlers (Vite dev server, Next.js, build steps) | Files still created/edited/exported correctly; preview and build report "Not executable on Android" with actionable message | — |
| **Not supported** | Native binaries, Python toolchains, Expo/RN device builds | Explicit error cards | — |

## 6. Startup & lifecycle requirements (from the brief)

- All runtime components initialize in the `Application` class (DI graph, DB, DataStore). No user-started processes.
- The preview server binds `127.0.0.1` only, started/stopped with preview lifecycle; `WorkManager` is not used for the server (it is UI-lifecycle bound, which is the correct Android pattern).
- App must cold-start offline: providers list, projects, editor, chat history all available without network. Network is required only for AI calls and CDN-backed React preview.
