# Anvex Manual Device Test Checklist (v1.1)

This environment has no attached Android device, so per the honesty rule the
physical pass is NOT claimed. Everything automatable was verified (build,
APK structure/signature/manifest, 120 JVM tests incl. real-SQLite migration
tests and wire-protocol tests). Run this checklist on a real device to
complete verification:

## Install & first run
- [ ] `adb install -r anvex-v1.1.0-release-signed.apk` succeeds (or side-load).
- [ ] App launches to Home; no crash within 60 s idle (watch `adb logcat` for ANRs).
- [ ] Airplane mode ON → app still opens, Home/Projects/Editor usable (offline-first).

## Provider configuration
- [ ] Providers → Add: create an OpenAI-compatible entry with a real key; Test
      Connection returns a REAL result (model count or chat-endpoint ok), not a
      URL-syntax pass.
- [ ] Repeat for an Anthropic and a Gemini provider (protocol adapters differ).
- [ ] Wrong key → Test Connection shows "Invalid API key or access denied"
      (verify the key is masked everywhere; never visible in cleartext).

## Project creation & AI chat
- [ ] New Project → prompt: "Build me a modern expense tracker with a
      dashboard, charts, categories and dark mode" → AI streams, artifact card
      shows actions with honest statuses (COMPLETE / PARTIAL for npm install /
      FAILED for impossible commands).
- [ ] Files appear in the Explorer as they are created.
- [ ] Rotate the device mid-stream → stream continues (state preserved).
- [ ] Background the app mid-stream, return → state intact.
- [ ] Press Stop mid-stream → generation halts; message persisted with
      "Generation stopped…" marker; applied changes listed and revertible.
- [ ] Airplane mode ON, send a message → clean network error, no crash.

## Diff / review
- [ ] Tap a changed file in the assistant message → Diff sheet shows
      before/after; Revert restores previous content; verify file on disk.
- [ ] A `mv`-driven change shows "renamed" with destination.

## Editor
- [ ] Open a file → syntax highlighting, line numbers, search/replace,
      undo/redo, autosave after ~0.7 s.
- [ ] External keyboard: Ctrl+Z / arrow navigation work.
- [ ] Open a >1 MB file → read-only banner, no jank scrolling.

## Preview
- [ ] Static HTML project → Preview renders with airplane mode ON.
- [ ] React project → Preview renders with airplane mode ON (bundled
      runtime); console shows "Preview ready".
- [ ] Add a CDN dependency (e.g. a chart lib) → works online, clear error
      offline.
- [ ] Console tab shows forwarded logs/errors; Refresh reloads.

## Persistence & lifecycle
- [ ] Kill the app from Recents → reopen → project, conversation, providers,
      and the change history (Debug → journal) all restored.
- [ ] `adb shell am kill com.anvex.app` (process death) → same.
- [ ] Upgrade test: install v1.0.0 (build from the v1.0 tag or the old APK),
      create data, then install v1.1.0 → data preserved (P0-3 regression:
      migration path is additive; destructive fallback removed).

## Import / export
- [ ] Export ZIP → file appears via SAF; unzip on desktop → files intact.
- [ ] Import a normal project ZIP → project created, tree correct.
- [ ] Import a malicious ZIP (`../` entries / huge bomb) → "Unsafe archive
      rejected", no partial files left.

## UI matrix
- [ ] Dark / light mode toggle.
- [ ] Force RTL (Developer options) → layouts mirror correctly.
- [ ] Phone + tablet/foldable: bottom nav / adaptive scaffold.
- [ ] Duplicate project from the project menu → "(copy)" appears with files.

## Security spot checks
- [ ] `adb shell dumpsys package com.anvex.app` → permissions only
      INTERNET + ACCESS_NETWORK_STATE.
- [ ] Preview URL is `http://127.0.0.1:<random>/` — not reachable from
      another device on the LAN.
- [ ] API key never appears in `adb logcat` during a streaming session.

## v4.0 harness — device checklist

- [ ] Open a project → Harness tab loads (dark workspace, provider chip reflects Settings)
- [ ] Agent: send a prompt; deltas stream; artifact card lists file actions; Revert works
- [ ] Files: tree loads; edits save; diagnostics badge appears on bad JSON/JS
- [ ] Git: Stage all + Commit; `git status` on a pulled workspace is clean
- [ ] Terminal: Start session; `ls` lists project files; Stop ends the process
- [ ] Skills: install a public GitHub skill repo; enable/disable; delete
- [ ] MCP: add an HTTPS MCP server; Tools lists; Invoke returns content
- [ ] Build: import toolchain bundle; debug build streams Gradle logs; APK appears in artifacts with Share/Install
- [ ] GOAL 6: tap "⬇ Download ZIP v4" → file appears in system Downloads; Snackbar offers Share; "Share" button opens the share sheet
- [ ] Preview: static/React projects still render; console pane captures logs
