# ARCHITECTURE_AUDIT.md — bolt.diy Source Audit (Phase 1)

Audited repository: `stackblitz-labs/bolt.diy` (MIT License, Copyright (c) 2024 StackBlitz, Inc. and bolt.diy contributors), shallow clone at audit time of this port.
Audit method: direct source reading of the cloned tree (no assumptions).

## 1. Overall architecture

bolt.diy is a **Remix (React Router v7-style) + Vite SPA** with a **server-side Remix action layer** designed to run on Node, Cloudflare Pages/Workers, Docker, or Electron:

| Layer | Technology | Files (verified) |
|---|---|---|
| UI | React 18, Remix, UnoCSS, nanostores, SCSS | `app/components/**` |
| State | nanostores atoms/maps (`workbench`, `chat`, `editor`, `files`, `previews`, `terminal`, `settings`) | `app/lib/stores/**` |
| Editor | CodeMirror 6 (`@codemirror/*`) with custom theme, language support, env masking | `app/components/editor/codemirror/*` |
| Terminal | xterm.js (`@xterm/xterm`) + fit addon, BoltShell wrapper | `app/components/workbench/terminal/*`, `app/utils/shell.ts` |
| Runtime | **StackBlitz WebContainer** (`@webcontainer/api`) — in-browser Node.js | `app/lib/webcontainer/index.ts` |
| Engine (client) | `StreamingMessageParser` (boltArtifact/boltAction XML stream parsing) + `ActionRunner` | `app/lib/runtime/message-parser.ts`, `app/lib/runtime/action-runner.ts` |
| Engine (server) | `streamText` (Vercel AI SDK) with `SwitchableStream`, segment switching, stream recovery, context selection, summarization | `app/routes/api.chat.ts`, `app/lib/.server/llm/**` |
| AI providers | `LLMManager` singleton + `BaseProvider` abstraction + ~25 provider classes on Vercel AI SDK `LanguageModelV1` | `app/lib/modules/llm/**` |
| Persistence | IndexedDB (chat history, workbench snapshots) + localStorage (settings, provider keys client-side) | `app/lib/persistence/**` |
| Deployments | GitHub/Vercel/Netlify/GitLab dialogs, Supabase integration, MCP tools | `app/components/deploy/**`, `app/components/@settings/**` |

## 2. The engine — what actually makes bolt.diy "bolt.diy"

The product's core is a **streaming artifact protocol** between the LLM and the client:

1. The system prompt (`app/lib/common/prompts/prompts.ts`, 714 lines) instructs the model to wrap project operations in XML-ish tags:
   - `<boltArtifact id="..." title="...">` — one per project/response iteration
   - `<boltAction type="file" filePath="...">full file content</boltAction>`
   - `<boltAction type="shell">npm install</boltAction>`
   - `<boltAction type="start">npm run dev</boltAction>`
   - plus `supabase` migration/query actions and `<bolt-quick-actions>` UI shortcuts
2. `StreamingMessageParser.parse(messageId, input)` is an **incremental, chunk-tolerant state machine** (position index per message, tail buffering, markdown code-fence cleanup, `&lt;/&gt;` unescaping for file actions). It emits callbacks: `onArtifactOpen/Close`, `onActionOpen/Stream/Close`.
3. `ActionRunner` executes actions in order: **file** actions write to the WebContainer FS (`mkdir -p` + `writeFile`), **shell/start/build** actions spawn processes in WebContainer (`npm install`, dev server), surfacing status per action (`pending|running|complete|aborted|failed`), with command pre-validation (`rm` missing files, `cd` to missing dirs) and enhanced shell error translation.
4. Preview: WebContainer boots with `coep: 'credentialless'`, workdir `/home/project`, and forwards `preview-message` events (uncaught exceptions / unhandled rejections) back into the app as alerts.
5. Server side (`api.chat.ts`): receives message history + FileMap, selects bounded context (`select-context.ts`, MAX_TOKENS / MAX_RESPONSE_SEGMENTS), streams via Vercel AI SDK, supports continuation segments and recovery.

**Key finding:** the artifact protocol, parser, action semantics, prompts, and provider abstraction are all **client/library-side logic that is fully portable**. The only non-portable piece is the WebContainer execution backend (and the optional Supabase/deploy/MCP integrations).

## 3. Server-side functionality inventory

- `/api/chat` — LLM streaming proxy (keeps API keys server-side).
- `/api/models`, `/api/models.$provider` — model listing.
- `/api/configured-providers`, `/api/check-env-key`, `/api/export-api-keys` — provider configuration.
- Provider health / connection testing (`useConnectionTest`, `useLocalModelHealth`).
- Supabase, GitHub, GitLab, Vercel, Netlify, MCP, web-search, bug-report, diagnostics routes.

On Android, all of these have direct in-app equivalents **except** the third-party service integrations (Supabase/deploy targets), which are out of scope for the mobile port's first release (documented as limitations). API keys move from server env → Android Keystore-encrypted on-device storage, and streaming happens directly from device to provider over HTTPS.

## 4. Filesystem requirements

- WebContainer virtual FS mounted at `/home/project` — files arrive via `boltAction type="file"` writes and user edits in CodeMirror; the terminal can also mutate them.
- Chat history + snapshots in IndexedDB; settings in localStorage.
- Starter templates are cloned from GitHub repos (network required).

Android mapping: per-project directory under app-private storage (no permissions needed), ZIP import/export via Storage Access Framework, Room for structured metadata.

## 5. Dependencies that cannot run on Android as-is

| Dependency | Why | Android replacement |
|---|---|---|
| WebContainer (`@webcontainer/api`) | Proprietary StackBlitz tech; runs Node.js **inside a browser tab**; requires COOP/COEP cross-origin isolation; not distributable inside an APK | In-app HTTP file server + System WebView as the JS runtime (V8); React preview via in-browser Babel + CDN import maps; npm-dependent workflows are honestly tiered, not faked |
| Vercel AI SDK + ~25 `@ai-sdk/*` adapters | Node-oriented (Node streams, env vars, server runtime) | Kotlin provider adapters (OkHttp + kotlinx-serialization) implementing the OpenAI / Anthropic / Gemini wire protocols directly |
| Remix server (Cloudflare/wrangler, Express) | Server process | Removed; all logic client-side in Kotlin |
| CodeMirror 6 | DOM-based, works in WebView but is not a native editor | Custom Kotlin/Compose editor with syntax highlighting, line numbers, undo/redo, search/replace |
| xterm.js + WebContainer shell | Browser terminal bound to WebContainer | `CommandExecutor` translating whitelisted shell commands to filesystem operations; no arbitrary process execution (security requirement) |
| pnpm/npm (user projects) | No Node runtime on device | Static preview for HTML/CSS/JS; React/JSX via transpile-in-preview (CDN, online); Vite/npm builds reported as Not Supported with clear messaging |

## 6. What is preserved 1:1 in the port

- The full `<boltArtifact>/<boltAction>` streaming protocol and prompt contract (adapted only in the `<system_constraints>` section to describe the Android runtime honestly).
- `StreamingMessageParser` semantics: incremental parsing, chunk-boundary tolerance, markdown-fence cleanup, escaped-tag cleanup, action ordering, per-action status lifecycle.
- Provider abstraction shape (provider registry, static + dynamic models, base URL + key + custom settings per provider, connection testing).
- The chat → artifact → files → preview workflow and UX concepts (workbench, files changed with diff, streaming output).
