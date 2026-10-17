# Anvex Runtime Evaluation (v1.1 §10)

Date: 2026-09-08 · Method: options were evaluated against the ACTUAL v1.1
architecture with measured numbers from this build environment (no
hand-waving). Chosen: **Option F — hybrid WebView-V8 + bundled offline
transpiler/runtime**, which is what ships in 1.1.

## Measured facts (this session)

| Artifact | Size (bytes) | License |
|---|---|---|
| `assets/runtime/babel.min.js` (@babel/standalone 7.26.4) | 2,983,904 | MIT |
| `assets/runtime/react.umd.js` (React 18.3.1 prod UMD) | 10,751 | MIT |
| `assets/runtime/react-dom.umd.js` (ReactDOM 18.3.1 prod UMD) | 131,835 | MIT |
| ESM wrappers (react, react-dom/client, jsx-runtime) | ~2,300 | MIT (ours) |
| **Historical release APK measurement (v1.1 evidence; not a current release build claim)** | **2,557,110 (~2.56 MB)** | — |
| **Debug APK total** | 19,425,271 (~19.4 MB) | — |

The 3.1 MB runtime bundle compresses to ~0.7 MB inside the APK (release grew
1.85 → 2.56 MB from v1.0 to v1.1).

## Option analysis

### Option A — Embedded QuickJS (quickjs-ng)
- **License**: MIT ✔ · **Engine .so**: ~600–800 KB per ABI (≈2–3 MB across 4 ABIs)
- **Startup**: ~5–15 ms engine init · **RAM**: ~1–5 MB per context
- **JS compatibility**: excellent (ES2023 in quickjs-ng)
- **ES modules**: yes · **TypeScript/JSX**: NO (needs a transpiler anyway)
- **npm**: NO — no node_modules resolution, no registry access
- **Vite**: NO
- **Verdict**: works for headless JS execution, but Anvex's execution
  surface is *preview rendering* — which QuickJS cannot do (no DOM). It would
  duplicate the WebView V8 without adding preview capability. Rejected as the
  primary runtime; remains the leading candidate for a future headless
  `ProjectRuntime.execute()` implementation (§40 seam already exists).

### Option B — Hermes
- **License**: MIT ✔ · **libhermes.so**: ~2.6 MB/ABI without Intl (≈10 MB+ across ABIs; ~14 MB with Intl)
- **Startup**: fastest cold start of the JIT-less engines; bytecode precompile
- **JS compatibility**: ES6 + partial newer ES (historically lags V8/QuickJS)
- **TypeScript/JSX**: NO · **npm**: NO · **Vite**: NO
- **Verdict**: adds 10+ MB APK weight for a language runtime with *no* DOM and
  no bundler. Its cold-start advantage is irrelevant here (our JS runs in an
  already-alive WebView). Rejected.

### Option C — JavaScriptCore (system, outside WebView)
- **License**: LGPL-2.1 (Webkit) — acceptable for dynamic linking but awkward
  for a clean open-source distribution; on Android it's practically only
  reachable via the system WebView anyway.
- **Verdict**: no advantage over Option F. Rejected.

### Option D — Android-native / WASM runtime (wasm3 / WAMR + esbuild-wasm)
- **License**: MIT (wasm3, WAMR, esbuild) ✔
- **esbuild-wasm payload**: ~10 MB before compression — 3× our entire current
  runtime bundle — to gain transpile speed we don't need (Babel handles
  JSX/TS-strip of typical AI-generated projects in well under a second in the
  preview browser).
- **Verdict**: size-unjustified for v1.1. Noted as the upgrade path if project
  sizes grow (compile times, not compatibility, would drive that).

### Option E — Node.js on Android (nodejs-mobile)
- **License**: MIT-ish (Node is MIT; nodejs-mobile adds complexity) ✔
- **Payload**: ~25–80 MB; native module/toolchain fragility across ABIs;
  background-execution constraints on modern Android.
- **The decisive fact**: even WITH Node embedded, `npm install` still needs
  the network and a toolchain — it does not deliver the offline fantasy, and
  the design brief forbids faking it.
- **Verdict**: rejected for v1.1. The `ProjectRuntime` seam (§40) is designed
  so a future "node runtime" module could slot in WITHOUT UI/engine changes.

### Option F — Hybrid WebView-V8 + bundled offline transpiler/runtime (CHOSEN)
- **Execution engine**: the System WebView's V8 — full modern ES, zero added
  APK weight, DOM + rendering included, hardened via §13 controls.
- **Transpiler**: bundled @babel/standalone (MIT, ~0.7 MB compressed in-APK)
  serves JSX/TSX/TS type-stripping **offline** (v1.0 required a CDN).
- **React runtime**: bundled React 18.3.1 + ReactDOM UMD with ESM wrappers,
  served from `/__runtime__/` by the in-app localhost server — **React
  preview now works with airplane mode on** (only third-party libs beyond the
  bundle still resolve from esm.sh, and the preview surface says so honestly).
- **Commands**: `CommandTranslator` whitelist with SUPPORTED/PARTIAL/UNSUPPORTED
  tiers — no processes are ever spawned.
- **Capability disclosure**: the system prompt embeds
  `RuntimeCapabilityReport.describe()` generated from the SAME code that
  enforces capabilities — prompt and reality cannot drift (§28).

## Comparison matrix

| Criterion | A QuickJS | B Hermes | C JSC | D WASM | E Node | **F Hybrid (chosen)** |
|---|---|---|---|---|---|---|
| Startup cost | 5–15 ms | fastest | n/a | ~50 ms | 100+ ms | 0 (WebView exists) |
| RAM overhead | 1–5 MB | ~2–4 MB | n/a | 10+ MB | 30+ MB | 0 incremental |
| APK size delta | +2–3 MB | +10 MB | ~0 | +10 MB | +25–80 MB | **+0.7 MB** |
| License | MIT | MIT | LGPL | MIT | MIT | MIT all |
| JS compat | ES2023 | ES6+ | ES2023 | host | full | **ES2023+ (V8)** |
| ES modules | ✔ | ✔ | ✔ | host | ✔ | ✔ (import-map + CJS shim) |
| TypeScript/JSX | ✘ | ✘ | ✘ | esbuild ✔ | tsc ✔ | **Babel ✔ (offline)** |
| npm compat | ✘ | ✘ | ✘ | ✘ | ✔* | recorded+CDN (honest PARTIAL) |
| Vite compat | ✘ | ✘ | ✘ | ✘ | partial | ✘ (declared UNSUPPORTED) |
| DOM/preview | ✘ | ✘ | via WebView | ✘ | ✘ | **✔** |
| Security | sandboxed C | sandboxed | WebView | wasm sandbox | processes! | WebView hardening §13 |

*Node's npm still needs network — it does not solve offline installs either.

## What would change this decision

1. A future need for **headless** project execution (test-running, SSR) →
   slot a QuickJS-backed `ProjectRuntime` implementation into the §40 seam
   (`execute()/build()` currently return honest UNSUPPORTED).
2. Project complexity outgrowing in-browser transpilation → evaluate
   esbuild-wasm behind the same seam.
3. A maintained, size-reasonable Node-on-Android distribution → enables
   real `installDependencies()`; UI and engine need zero changes.

## Verification of the offline claim (v1.1)

- Preview harness loads Babel/React from `/__runtime__/*` (localhost server
  routes, immutable cache) — see `PreviewHarness`, `StaticPreviewServer`.
- Import map pins `react`, `react-dom`, `react-dom/client`, `react/jsx-runtime`
  to local wrappers; everything else maps to esm.sh and is labeled online.
- The offline banner in the harness only appears if the LOCAL bundle fails
  (unexpected), not for React projects as in v1.0.
- Static HTML/CSS/JS projects: fully offline since v1.0 (unchanged).
