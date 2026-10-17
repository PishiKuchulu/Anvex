# Anvex Security Audit v1.1 (§32)

Date: 2026-09-08 · Scope: full re-audit after the v1.1 hardening pass.
Severity scale: CRITICAL / HIGH / MEDIUM / LOW. **No CRITICAL or HIGH issues
remain open.** (v1.0 audit findings that were fixed in this pass are listed
with their resolution.)

## 1. API key protection — VERIFIED

| Check | Status | Evidence |
|---|---|---|
| Encrypted at rest (AES-256-GCM, AndroidKeyStore hardware-backed key) | PASS | `KeystoreCrypto` + `CryptoEnvelope` |
| Crypto envelope correctness (round-trip, IV uniqueness, tamper detection, wrong-key rejection, truncation) | PASS | `CryptoEnvelopeTest` (9 JVM tests) |
| Random IV per encryption (randomizedEncryptionRequired) | PASS | `KeystoreCrypto.getOrCreateKey` + test `every encryption uses a fresh random IV` |
| Never logged | PASS | repo-wide grep: zero `Log.` / `println` / `printStackTrace` in main sources |
| Never exported | PASS | `ZipCodec.export` walks project fs only (project dirs are separate from the DB); provider keys live in Room, not project files |
| Masked in UI | PASS | `maskApiKey()` used by provider UI |
| Decryption only on demand, in memory | PASS | `ProviderRepository.runtimeConfig` |

Fixed in this pass: the crypto envelope was previously untestable on JVM; the
`CryptoEnvelope` extraction closes that gap. Residual (device-only): key
availability inside Android Keystore is covered by the manual device checklist
(test connection flow exercises the full encrypt→decrypt path).

## 2. WebView & JS bridge — HARDENED (was MEDIUM)

v1.0 exposure: `addJavascriptInterface` bridge visible to ANY page loaded in
the preview WebView; JSON built via string interpolation (injection into the
bridge parser); `allowUniversalAccessFromFileURLs` unset explicitly.

v1.1 fixes applied:
- `allowFileAccess=false`, `allowContentAccess=false`,
  `allowUniversalAccessFromFileURLs=false`, `allowFileAccessFromFileURLs=false`
  (explicit defaults, `PreviewScreen`).
- Bridge JSON built with `kotlinx.serialization` builders — no string
  interpolation (`bridgeJson`), so console/error text can no longer forge
  bridge frames (B-3 fixed).
- The preview server validates the `Host` header (loopback only) —
  `isLocalLoopback()` — closing cross-origin references from other apps'
  WebViews (403 otherwise).
- Network security config denies cleartext globally; loopback only for the
  preview server.
- Mixed content: WebView default (API 21+) = `MIXED_CONTENT_NEVER_ALLOW`.
- Bridge surface: a single `post(json)` method; messages are parsed
  defensively; unknown message types are dropped; no paths or keys are exposed
  to the page — the bridge only RECEIVES console/error events.
- v1.1.1 (final pass): main-frame navigation pinned to the loopback origin
  (`shouldOverrideUrlLoading`) — a link click inside a preview cannot move the
  WebView to a foreign page where the bridge object would remain reachable;
  external http(s) links are handed to the browser app, non-http schemes never
  load. The `/__runtime__/*` route now enforces a strict filename whitelist
  (defense in depth on top of AssetManager traversal rejection).

Residual, accepted (LOW): a project's own page JS can call `__anvex.post`
— by design, that is how console messages flow; the payload is display-only
and never interpreted as commands. The WebView only ever loads the local
preview server origin.

## 3. Local preview server — HARDENED

- Binds `127.0.0.1` only (never `0.0.0.0`); random port; GET/HEAD only.
- Host header validation (v1.1): non-loopback Host → 403.
- All paths pass `PathSanitizer` (traversal/absolute/drive/null/segment
  length) + canonical containment in `RealProjectFileSystem`.
- `/__runtime__/*` assets served from a fixed APK bundle via an injected
  loader with a strict name (no path parameters).
- `X-Content-Type-Options: nosniff` on all responses; runtime assets
  `Cache-Control: immutable`; project files `no-store`.
- Server thread pool bounded (2 daemon threads), 15 s socket timeouts.

## 4. ZIP import/export — VERIFIED (see also audit gaps)

- Entry count cap 10,000; per-entry 64 MB; total 256 MB; bounded buffer.
- Zip-slip and absolute-path entries rejected (`ZipCodecTest`).
- Import runs into a FRESH project id (never merges into an existing one).
- Known residual (LOW): entries are buffered in RAM up to the per-entry cap;
  mobile-appropriate but documented; duplicate entries: last-write-wins.
- Export writes only user project files through SAF user-chosen URIs.

## 5. Filesystem — VERIFIED

- `PathSanitizer`: rejects `..` escapes, absolute paths, Windows drives, null
  bytes, >255-char segments; normalizes `.` and inner `..`.
- `RealProjectFileSystem.resolve`: canonical-path containment re-check
  (defeats symlink escape); project roots under app-private `filesDir`.
- ZIP-sourced paths pass the same sanitizer (tested).

## 6. Exported components / intents

- Manifest: single exported activity (launcher `MainActivity`); no exported
  services/receivers/providers. INTERNET + ACCESS_NETWORK_STATE only
  (aapt-verified on the built APK).
- `allowBackup=true` (v1.0) — keys are Keystore-encrypted ciphertext in the
  backup (not plaintext); the Keystore key itself is NOT backed up, so
  restored ciphertext is undecryptable on another device — acceptable. LOW.
- Debug WebView is enabled only in the debug build type by default tooling.

## 7. Serialization / deserialization

- All JSON parsing is `kotlinx.serialization` with `ignoreUnknownKeys` and
  defensive casts; malformed provider/stream JSON fails closed.
- Room schema v2 with explicit migration; NO destructive fallback (P0-3
  fixed — was the highest-risk v1.0 finding). Migration covered by 8 JVM
  tests incl. data preservation + integrity + restart simulation.

## 8. Logging / crash handling

- No sensitive logging (grep-verified). No crash-reporting SDK (nothing to
  leak). Engine errors are mapped through `toUserMessage()` before display —
  no raw stack traces in UI (§17).

## 9. AI layer

- SSE reader: cancellation-aware, HTTP-error-mapped, spec-correct field
  parsing (v1.1 §16 hardening + 15 protocol tests).
- Keys are sent only to the user-configured provider endpoint over HTTPS
  (cleartext blocked globally by network security config).
- Prompt-injection surface: model output is parsed by a strict state machine
  (never `eval`-ed); file actions are path-sanitized before any write; shell
  actions are whitelisted translations (no process execution exists in the
  app at all).

## Issue summary

| ID | Finding | Severity | Status |
|---|---|---|---|
| S-1 | Room destructive migration could wipe user data | HIGH | **FIXED v1.1** (explicit migrations + tests) |
| S-2 | ChatSession race could persist incomplete artifacts / partial writes | HIGH | **FIXED v1.1** (structured concurrency + tests) |
| S-3 | before/after mutation corruption destroyed diffs/reverts | HIGH | **FIXED v1.1** (mutation chokepoint + tests) |
| S-4 | Bridge JSON interpolation + missing explicit WebView flags | MEDIUM | **FIXED v1.1** |
| S-5 | Preview server lacked Host validation | MEDIUM | **FIXED v1.1** |
| S-6 | SSE data-line `.trim()` spec deviation | LOW | **FIXED v1.1** |
| S-7 | Parser artifact-tag boundary bug (skipped artifacts) | HIGH (correctness) | **FIXED v1.1** (found by new tests) |
| S-8 | Keystore envelope untestable | MEDIUM | **FIXED v1.1** (CryptoEnvelope + 9 tests) |
| S-9 | `runBlocking` file read on main thread (ANR) | MEDIUM | **FIXED v1.1** (suspend read) |
| S-10 | Editor had no large-file guard | MEDIUM | **FIXED v1.1** (read-only + highlight cutoff) |
| S-11 | Backup contains undecryptable ciphertext | LOW | ACCEPTED (documented) |
| S-12 | ZIP import buffers entries in RAM (bounded) | LOW | ACCEPTED (documented) |
| S-13 | Project JS can call the console bridge | LOW | ACCEPTED (display-only by design) |

**Open HIGH/CRITICAL: none.**


## v3.1.0 external audit hardening
- AI shell and ActionSafetyPolicy reject CR/LF and shell backslash escaping to prevent multi-command injection.
- Preview bootstrap JSON-derived entry path is encoded as an HTML-script-safe JavaScript string literal.
- Android OS backup is disabled because Keystore-protected API-key ciphertext is not portable across uninstall/device migration.
- Provider runtime no longer silently converts Keystore decryption failure into an empty API key; the UI surfaces a re-entry warning.
