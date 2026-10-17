# DEPENDENCY_AUDIT.md — Port dependency decisions & licenses (Phase 1)

## 1. Original bolt.diy dependency surface (verified from `package.json` / `pnpm-lock.yaml`)

- Runtime: `@webcontainer/api` (proprietary StackBlitz), Remix, React 18, Vite, UnoCSS, nanostores
- AI: Vercel AI SDK (`ai`, `@ai-sdk/react`, `@ai-sdk/ui-utils`) + ~25 provider packages (`@ai-sdk/openai`, `@ai-sdk/anthropic`, `@ai-sdk/google`, `@ai-sdk/deepseek`, `@ai-sdk/mistral`, `@ai-sdk/cohere`, `@ai-sdk/amazon-bedrock`, ...)
- Editor: `@codemirror/*` family; Terminal: `@xterm/xterm`; Diff: `diff`
- Persistence: `idb-keyval` (IndexedDB); misc: `isbot`, `zod`, `rehype-raw` + markdown stack, `hosted-git-info`, `shiki`, `screenshot-io`, etc.

None of these ship inside the Android app. The port re-implements the needed behavior natively in Kotlin; the bolt.diy MIT license and attribution are preserved in `THIRD_PARTY_NOTICES.md` and the About screen.

## 2. Android dependency graph (chosen for reliability + license clarity)

| Dependency | Version | License | Role | Notes |
|---|---|---|---|---|
| Kotlin | 2.0.21 | Apache-2.0 | language | Compose compiler bundled via `kotlin-plugin-compose` |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | build | with Gradle 8.10.2 |
| Jetpack Compose BOM | 2024.12.01 | Apache-2.0 | UI | Material 3 |
| androidx.core / activity-compose / lifecycle | 1.15.0 / 1.9.3 / 2.8.7 | Apache-2.0 | UI runtime | |
| Navigation Compose | 2.8.5 | Apache-2.0 | navigation | |
| Room | 2.6.1 | Apache-2.0 | structured persistence | KSP 2.0.21-1.0.28 |
| DataStore Preferences | 1.1.1 | Apache-2.0 | settings | |
| OkHttp | 4.12.0 | Apache-2.0 | AI provider HTTP + SSE | also MockWebServer for tests |
| kotlinx-serialization-json | 1.7.3 | MIT | provider/DTO JSON | |
| kotlinx-coroutines | 1.9.0 | Apache-2.0 | async/streams | |
| JUnit | 4.13.2 | EPL-1.0 | tests | test-scope only |

Notable **deliberate omissions** (each removes a failure mode and/or a license obligation):
- **No NanoHTTPD** — preview server is a hand-written ~200-line `ServerSocket` implementation (BSD-free, fully controlled, no transitive deps).
- **No sora-editor / CodeMirror-in-WebView** — custom Compose editor (no LGPL surface, native UX).
- **No Retrofit/Hilt/Coil** — unnecessary surface for this scope.
- **No QuickJS/Hermes** — decision rationale in RUNTIME_ANALYSIS.md §4.

## 3. License compliance summary

- bolt.diy: **MIT** (verified in repo `LICENSE`; Copyright (c) 2024 StackBlitz, Inc. and bolt.diy contributors) — preserved in `THIRD_PARTY_NOTICES.md`, About screen, and this repository's LICENSE file.
- All Android runtime dependencies above are Apache-2.0/MIT (test-scope JUnit is EPL-1.0, not distributed in the APK).
- The system prompt text ported from bolt.diy retains upstream attribution in source comments.
- Full machine-verified license table is regenerated into `THIRD_PARTY_NOTICES.md` at build time from the resolved Gradle dependency graph (see `scripts/license_audit.sh`).
