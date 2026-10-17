# ANDROID_RUNTIME.md

## Why WebContainer is not embedded (and cannot be)

bolt.diy executes user projects in **StackBlitz WebContainer**, a proprietary
Node.js runtime that runs *inside a browser page* (booted with
`WebContainer.boot({ coep: 'credentialless' })`). It is:

1. **Not distributable inside an APK** — proprietary, browser-licensed technology,
   fetched and booted from StackBlitz infrastructure under cross-origin isolation
   (COOP/COEP + SharedArrayBuffer). It is not offered as an embeddable library and
   its terms do not permit redistribution in native apps.
2. **Not reproducible in a WebView** — it requires the hosting origin to serve
   COOP/COEP isolation headers; an app-owned `127.0.0.1` origin in a WebView is
   not a supported deployment scenario and would be fragile across OEM WebViews.

Per the migration brief (replace with Android-compatible equivalents, never fake),
Anvex replaces the execution backend while **preserving the engine** (parser,
prompts, action semantics) — see docs/audit/RUNTIME_ANALYSIS.md.

## The chosen runtime stack

| Capability | Implementation |
|---|---|
| JavaScript engine | Android System WebView (Chromium/V8) — a real, sandboxed, process-isolated JS runtime that ships on every device; the app never executes project JS with Android privileges |
| File serving | `StaticPreviewServer`: hand-written HTTP server on `ServerSocket`, bound to `127.0.0.1` only, GET/HEAD only, MIME-mapped, path-sanitized, no directory listing |
| Static previews | Files served as-is; `index.html` is the entry |
| React/TSX previews | `/__preview__` harness page: in-browser `@babel/standalone` transpile (JSX + TS), import map from `package.json` → esm.sh, CommonJS-shim module graph executed in dependency order, CSS imports injected as `<style>` |
| Console & errors | Bridge (`addJavascriptInterface`) + `window.onerror`/`unhandledrejection` interception → app console panel (bounded ring buffer) |
| Command translation | `CommandTranslator` whitelist → FS effects (mkdir/touch/echo>/rm/mv/cp/ls/cat); `npm install` recorded; dev-server/build commands explicitly reported unsupported. **Nothing is ever spawned.** |

## Why not QuickJS / Hermes?

- **QuickJS**: genuinely embeddable (MIT), but previewing web projects requires the
  DOM, CSS layout, and the web platform — i.e. a browser engine. Embedding QuickJS
  would mean reimplementing a browser; using it for command translation would add
  a JS runtime for a problem already solved by ~200 lines of deterministic Kotlin.
- **Hermes**: purpose-built for React Native application code, brings an entire RN
  runtime integration for zero benefit here.
- **WebView/V8**: sandboxed (separate process, no Android file access, isolated from
  app memory), always present, and already IS the web platform. It satisfies the
  "embedded JavaScript runtime" requirement with strictly better engineering
  economics. The trade-off — React previews need CDN access (esm.sh, Babel) — is
  surfaced honestly in the UI rather than hidden.

## Capability tiers (no fake success)

| Tier | Project shape | Behavior |
|---|---|---|
| Supported (offline) | HTML/CSS/JS, multi-file, relative links | Full preview + editor + export |
| Supported (online) | React JSX/TSX, ESM imports of package.json deps | Preview via CDN transpile; clearly labeled when offline |
| Partial | Vite/npm-build projects, TypeScript tooling | All files created/edited/exported; preview and build report "not executable on Android" with an actionable message |
| Unsupported | Python/native binaries/Expo | Explicit error cards |

`CommandTranslator` maps `npm run dev`, `vite`, `npx …`, `node …`, `git …`, `tsc …`
to UNSUPPORTED with an explanation, and `npm install`/`yarn`/`pnpm install` to
PARTIALLY (recorded; dependencies resolve at preview time). The debug panel shows
the translation outcomes. A project is never reported as "built" when it wasn't.

## Startup lifecycle (brief §4)

All components initialize in `AnvexApplication.onCreate()` via `AppGraph` — database,
DataStore, repositories. The preview server starts/stops with the preview screen's
lifecycle (`PreviewViewModel.start/stop`), never requires user action, and never
survives the app process. The app cold-starts fully offline.
