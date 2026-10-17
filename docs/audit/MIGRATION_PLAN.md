# MIGRATION_PLAN.md — bolt.diy → Anvex (Phase 1 deliverable)

## Goal

A standalone Android application (installable APK) that reproduces the bolt.diy workflow —
chat with an AI, generate a project as a streaming artifact, review diffs, edit code, preview, export —
with **zero external infrastructure** required on the user side: no PC or backend is required; Agent/Terminal and optional local Node are app-managed on-device capabilities.

## Phase map (from the master brief, with concrete engineering steps)

### Phase 2 — Android foundation
- Gradle Kotlin DSL multi-module project: `:app`, `:core:model`, `:core:engine`, `:core:ai`, `:core:android`.
  - Pure-Kotlin JVM modules (`model`, `engine`, `ai`) keep the bolt protocol logic platform-free and unit-testable on the JVM.
  - `:core:android` holds Room, DataStore, Keystore crypto, FS, preview server. `:app` holds Compose UI.
- Manual DI (`AppGraph`) — no Hilt/KAPT to keep the build robust and fast.
- Navigation Compose routes: home / project chat / editor / preview / settings / providers / debug.

### Phase 3 — AI layer (port of `app/lib/modules/llm/**`)
- `AIProvider` interface ≈ `BaseProvider` + Vercel-AI-SDK role: `streamChat`, `listModels`, `validateConnection`.
- Adapters implemented against the raw wire protocols (no Node SDK):
  - `OpenAICompatibleClient` → OpenAI, OpenRouter, DeepSeek, Groq-style, Ollama (`/v1`), LM Studio, **custom user-defined OpenAI-compatible endpoints** (name/URL/key/model/headers/temperature/max-tokens).
  - `AnthropicClient` → Anthropic + **custom Anthropic-compatible** endpoints (`/v1/messages`, `anthropic-version` header, SSE `content_block_delta`).
  - `GeminiClient` → `:streamGenerateContent?alt=sse`.
- Hand-written SSE line parser over OkHttp (bounded buffers, cancellation-aware).

### Phase 4 — Project engine (port of `app/lib/runtime/**`)
- `BoltStreamParser` — faithful Kotlin port of `StreamingMessageParser` (chunk-tolerant state machine, same callbacks).
- `ActionApplier` — port of file-action semantics from `ActionRunner#runFileAction` (mkdir -p, full-content writes, fence cleanup, escaped-tag cleanup, trailing newline).
- `CommandExecutor` — whitelist translation of shell commands; npm-family commands produce honest notices; nothing is spawned.
- `ProjectRepository` + Room (projects, messages, providers) + per-project FS + SAF import/export (ZIP with zip-slip/zip-bomb guards).

### Phase 5 — Editor
- Custom Compose `CodeEditor`: BasicTextField + `VisualTransformation` syntax highlighting (JS/TS/JSX/HTML/CSS/JSON/MD), line-number gutter synced by shared scroll state, no-wrap horizontal scroll, undo/redo (bounded stack), find/replace, multi-file tabs, autosave.

### Phase 6 — Bolt.diy engine core (highest priority per the brief)
- `SystemPromptFactory` ports the original 714-line system prompt, with `<system_constraints>` rewritten to describe the Android runtime truthfully (static-first, React via CDN transpile, no npm) and the same `<artifact_info>` contract.
- `WorkbenchEngine` = provider stream → parser → action pipeline → pending change set → apply/reject with diff (LCS line diff), exactly mirroring bolt.diy's chat → artifact → workbench flow.

### Phase 7 — Preview
- `StaticPreviewServer` (raw `ServerSocket`, localhost-only, MIME-mapped, path-sanitized) + `PreviewScreen` (WebView + console bridge + refresh + open-in-browser + console/error panel).
- React harness: generated bootstrap page using `@babel/standalone` + import-map to esm.sh, with an in-page module registry resolving relative project imports.

### Phase 8 — AI coding workflow
- Chat screen: streaming messages, artifact card, actions with statuses, "Files changed" (+/~/-), per-file diff review, apply/reject, open-in-editor handoff.

### Phase 9 — Mobile UX
- Bottom navigation, bottom sheets for explorer/new-file/diff, dark/light/system themes, dynamic color, RTL-safe layouts, tablet two-pane chat+preview.

### Phase 10 — Security & performance
- Keystore AES-GCM encryption for API keys (never plaintext, never logged, masked in UI), path-traversal guards, ZIP guards, WebView hardening, localhost-only server, network security config (HTTPS default, cleartext only for 127.0.0.1).
- Memory: bounded logs (ring buffers), lazy file loading, capped highlighting for huge files.

### Phase 11 — Testing
- JVM unit tests: parser (incl. split-chunk cases), command translation, diff, path safety, ZIP guards, SSE decoding, request JSON shape (MockWebServer).
- Android instrumentation smoke tests where feasible in this environment.

### Phase 12 — Release
- Debug APK + unsigned release APK + AAB, plus a self-signed release APK for direct sideload; GitHub Actions workflow for reproducible CI builds; signing documented.

## Non-goals (documented, not silently dropped)
- Supabase / Vercel / Netlify / GitLab deploy integrations, MCP tools, web search, prompt-enhancer — out of scope for v1 of the port; the provider/chat/engine core is the product.
- Running `npm`, `vite`, or any bundler on-device — architecturally replaced per RUNTIME_ANALYSIS.md.
- Multi-user auth — bolt.diy has none either (API-key based).
