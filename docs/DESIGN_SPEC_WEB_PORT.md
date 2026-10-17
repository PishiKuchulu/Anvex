# Design Specification — untitled.zip Web Prototype → Native Compose Port (v4.4)

This document records the UI/UX extraction from the React/Vite prototype
(`untitled.zip`) and how it was implemented natively in the original Anvex
Android source (Jetpack Compose), per the uiux.txt contract. The web code was
used **strictly as a visual and interaction reference**; no web code ships in
the app, no WebView wraps the UI, and no simulated state was imported as real.

## 1. Design tokens (extracted from the prototype, mapped to Compose)

| Token (web) | Web value | Native mapping (Theme.kt `AURORA`) |
|---|---|---|
| app-root bg | `#070b14` | `background 0xFF070B14` |
| surface-1 | `#0b101e` | `surface 0xFF0B101E` |
| panel | `#0f172a` | `surfaceVariant 0xFF0F172A` |
| deepest console | `#050811` | `AuroraExtrasDark.canvasBottom 0xFF050811` |
| divider | `slate-800 #1e293b` | `outline 0xFF1E293B` |
| primary | `cyan-400 #22d3ee` | `primary 0xFF22D3EE` |
| gradient partner | `indigo-400 #818cf8` | `secondary 0xFF818CF8` |
| success | `emerald-400 #34d399` | `tertiary 0xFF34D399` |
| danger | `rose-400 #fb7185` | `error 0xFFFB7185` |
| signature gradient | `from-cyan-500 to-indigo-600` | `accentBrush 0xFF06B6D4→0xFF4F46E5` |
| dark ladder | `#070b14→#050811` | `canvasBrush` vertical |
| mono metrics | JetBrains Mono | `AnvexMono` (bundled) — unchanged |
| card radius | `rounded-xl 12 / 2xl 16` | `AuroraShapes` medium 12, large 16 |
| badge recipe | `*-950 bg + *-400 text + *-700/60 border, 10px mono` | `AnvexStatusBadge` accents via color scheme |
| bubble corners | asymmetric `rounded-2xl rounded-br-xs` | MessageBubble `RoundedCornerShape(22, 22, 22/6, 6)` (pre-existing) — pair kept |

Theme selection: **AURORA** is a new entry in `UiThemeStyle` (selectable in
Settings → Appearance). NEBULA/PROFESSIONAL/GLASS remain intact — nothing was
force-replaced; the user chooses.

## 2. Component mapping table (prototype → native)

| Prototype component | Visual/UX purpose | Native implementation | State/data source |
|---|---|---|---|
| CategorizedSettingsView | 6 accent-coded settings categories | `SettingsScreen` category chips (`FilterChip` LazyRow) + per-category sections | REAL `AppSettings` DataStore only — no mock toggles (the web "SSE/circuit-breaker/crash-logs" toggles were dropped: they had no backing feature) |
| ZaiAccountCard | Account connection hero | `ZaiAccountCard` in `ProvidersScreen` | REAL `ZaiAccountCoordinator` (ZCode polling flow); states Idle/Running(stage+URL+attempt)/Failed(upstream reason)/Connected(existing row). The web card's Math.random flow ids, auto-success timers and fake quotas were NOT ported |
| OpenCodeFreeCard | Free/Zen tabs, honest 403 | Provider-edit form: kind `OPENCODE_FREE` (no-key field + verbatim-403 note) / `OPENCODE_ZEN` (key field) | REAL `validateConnection()` chat-probe; upstream body surfaced verbatim |
| ThinkingCard | Collapsible reasoning, live dot, "Thought for Xs" | `ui/components/ThinkingCard.kt` | REAL `StreamEvent.Reasoning` traces (persisted per message, Room v13). The web's fake token estimate was not ported |
| Chat quick-config pills | Thinking on/off, model chip | Composer `ThinkingMenu` + active-model label (pre-existing, restyled accents) | REAL ThinkingLevel + provider config |
| EditorView action chips | Review / Optimize / Fix / Explain | `CanvasShortcutRow` in `EditorScreen` | REAL share-intake delivery of the open file + instruction |
| Editor diff banner (SEARCH/REPLACE) | Proposed granular diff | `PatchEngine` + `ActionDispatcher` (runtime applies model patches); banner-style diff UI lives in the existing DiffSheet | REAL journal before/after |
| GitView timeline + staged rows | Commit spine, stage/unstage tints | Existing Git tab in Harness (theme-neutral accents); not re-ported as a separate screen (scope rule: no unrelated new screens) | REAL GitLite |
| ArtifactView version switcher (v1/v2/v3) + Mermaid/SVG | Versioned artifacts, diagrams | `VersionChipRow`/`VersionSheet` on `ArtifactCard` + Mermaid/SVG preview pages | REAL mutation journal (bounded 200/project) |
| BuildTerminalView / LiveWorkflowLogConsole | Build console, steps, filters | `BuildScreen` log console + `CloudBuildRepository` tail | REAL AndroidBuildEngine / GitHub Actions API only |
| BuildToastNotification | Build done toast | `FloatingAgentStatus` pattern (pre-existing); build completion surfaced in the artifacts list + log lines | REAL build results |
| MemoryManagerView | Purple identity, vault stats, MD import | Existing MemoryScreen (accents via theme); MD import parser was already real in v4.2 | REAL MemoryRepository |
| SecurityScanView | Severity badges, auto-patch | Not ported as a screen (no real scanner backend exists beyond BuildScriptRiskScanner; per uiux.txt no unrelated features). Severity badge recipe lives in AnvexStatusBadge | n/a |
| SshKeyConfiguration / HarnessLabView / SourceCodeInspector / AndroidPhoneFrame / App.tsx chrome | Web-only simulator chrome | Not applicable — Anvex is the real device app; these were prototype scaffolding | n/a |

## 3. Explicitly NOT ported (simulation code, identified in the prototype)

`Math.random()` flow ids; `setTimeout` auto-success (Z.ai card); hardcoded quotas
(5000/620/4380, 10000/1600 credits); `probe 403` canned messages; `Math.random` git
hashes; fake durations in HarnessLab; `motion` (unused dep); broken CSS classes
(`animate-slideDown`, `dir-rtl`, `no-scrollbar`); the fake user
`farzad.sholeh@gmail.com`. None of these run in the app.

## 4. Persian/RTL

The prototype's `font-[Vazirmatn]` swap and `dir-rtl` (a no-op class) became a real
fix: the platform locale is applied on language change (see KNOWN_LIMITATIONS), the
Compose layout direction follows the locale, and `tr()` resolves 385 Persian entries.
