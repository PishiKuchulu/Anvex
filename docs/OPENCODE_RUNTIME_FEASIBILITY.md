# OpenCode Free — On-Device Runtime Integration: Feasibility Report & GO/NO-GO Decision

- **Date:** 2026-10-16 (user timezone, UTC+8). All command timestamps in this report are UTC (2026-10-15 late evening).
- **Subject:** Whether the *genuine, official OpenCode runtime* can be bundled and executed inside the Anvex Android app (Android 13 / ARM64, app sandbox, **no PC, no Termux, no separately installed OpenCode app, no remote server, no third-party relay**) so that OpenCode **Free** works on-device.
- **Anvex tree examined:** v4.4.1 (versionCode 66), 200 Kotlin files, modules `:app`, `:core:model`, `:core:engine`, `:core:ai`, `:core:android`.
- **Method:** every factual claim below traces to a command executed in this session (raw logs in `download/evidence/`) or to a primary source fetched live (URLs listed in §Sources). Nothing is assumed from memory.

---

## 1. Executive summary

The official OpenCode runtime **works and legitimately reaches the free tier** — we proved that end-to-end on a reference Linux host: a headless `opencode serve` instance answered a real chat request on the free model `space-bunny-free` with `PONG` in ~700 ms, **without any login**, at zero cost. The free tier is gated purely on *being the official client*.

But the runtime **cannot run on Android**:

1. OpenCode publishes **no Android target at all** (verified across v1.18.0 / v1.19.0 / v1.19.1 release assets, the official install script matrix, and the npm package platform list: linux/darwin/windows × x64/arm64 only).
2. The official Linux ARM64 builds are **dynamically linked ELF binaries requiring glibc or musl loaders** (`/lib/ld-linux-aarch64.so.1` / `/lib/ld-musl-aarch64.so.1` + `libstdc++`/`libgcc_s`). Android provides **bionic** only — these binaries cannot load, regardless of how they are packaged into the APK. This is the same hard blocker Anvex already documents for Node.js ("Node targets glibc; Android runs bionic" — `docs/KNOWN_LIMITATIONS.md`).
3. Even setting the loader problem aside, the runtime is a **177–186 MB uncompressed** single binary (vs Anvex's entire 28.25 MB APK), expects XDG directories + a pty service + (for VCS features) a system `git`, and self-manages a binary cache — none of which exist or are permitted in the app sandbox within the mission's packaging constraints.
4. On-device verification is **impossible in this environment** (no Android device; x86_64 host without `/dev/kvm`; no Android emulator possible; no qemu-user). The mission explicitly forbids claiming success without on-device proof — so no such claim is made.

**Decision: NO-GO** for bundling/executing the genuine OpenCode runtime inside Anvex on Android 13/ARM64. The closest legitimate architecture — already shipped in Anvex v4.4.1 and re-verified live today — is the in-app HTTP provider that discovers free models publicly, sends requests with Anvex's own honest identity, and surfaces the upstream `403 FreeTierError` verbatim instead of impersonating the official client. That architecture is fully on-device (no external processes or relays) and satisfies every boundary the mission set.

---

## 2. Decision

**NO-GO — the proposed architecture (bundle and run the genuine OpenCode runtime in the Anvex app sandbox on Android 13/ARM64) is not technically possible today, and the free tier cannot be reached by any other legitimate means from a third-party Android client.**

| # | Blocker | Class | Evidence (this session) |
|---|---------|-------|--------------------------|
| B1 | No official Android build of the runtime exists | **Hard — upstream** | Release assets of v1.18.0/v1.19.0/v1.19.1 enumerated; install script accepts only `linux-x64|linux-arm64|darwin-x64|darwin-arm64|windows-x64`; npm `opencode-ai@1.19.1` declares `os: darwin/linux/win32` |
| B2 | Official ARM64 artifacts are glibc/musl dynamic ELFs; Android has bionic only | **Hard — binary incompatibility** | `file` + `readelf` on the three downloaded artifacts (§4.2) |
| B3 | Android app-sandbox exec policy: apps targeting API 29+ cannot `exec()` files from their writable home directory (W^X) | **Hard — platform policy** (Anvex targets SDK 35) | Official docs: "Removed execute permission for app home directory … a W^X violation" (developer.android.com, fetched live) |
| B4 | Runtime mass and environment: 177–186 MB binary, XDG state dirs, pty endpoints, `git` for VCS features, self-managed `~/.cache/opencode/bin` | **Architecture / packaging constraint** (mission §4 forbids shipping a development toolchain) | PoC filesystem footprint + `--help` surface (§4.3); APK would grow ~6.3× from this single binary |
| B5 | Free tier admits only the official client identity; forging it is forbidden | **Policy — upstream** (and compliance rule) | Two-sided live test: Anvex UA → `403 FreeTierError`; genuine runtime → `200` + free completion (§4.4) |
| B6 | No Android device / emulator in this environment to run the mandated on-device PoC | **Environment-blocked** (honesty rule) | No `/dev/kvm`, x86_64 host, no qemu-user (§4.8) |
| — | Licensing of the runtime | **Not a blocker** | MIT (fetched from the official repo) — redistribution would be permitted if B1–B4 were solved |

**What is NOT claimed:** that the runtime "cannot ever" run on Android — only that no legitimate, reproducible path exists *today* with the official artifacts, and that this environment could not attempt an on-device proof.

---

## 3. Methodology and environment disclosure

- Reference host for all live tests: x86_64 Debian Linux sandbox, 2 cores, 3.9 GB RAM, **egress IP 47.57.232.232** (Alibaba Cloud, Hong Kong). Region matters for §4.5 — this is *not* an Iranian IP.
- Official runtime obtained directly from the project's own releases (GitHub, `anomalyco/opencode`), SHA-256 recorded before any execution.
- All HTTP probes use **Anvex's honest identity** (`User-Agent: Anvex/4.4 (Android; probe)`) via the shipped `tools/opencode_probe.sh`; no official-client headers were ever forged by Anvex code or probes.
- The genuine runtime was executed as-is (it *is* the official client — no impersonation involved).
- No OpenCode account was created or used; no credentials existed in the environment.
- Raw evidence: `download/evidence/` (probe output, PoC logs, build logs, SHA-256 manifest).

---

## 4. Findings

### 4.1 The official runtime: identity, license, distribution

- Repository: **`github.com/anomalyco/opencode`** (the old `github.com/sst/opencode` URL now 301-redirects there). Latest release at test time: **v1.19.1** (also `npm opencode-ai@1.19.1`). A "v2" product line exists (`opencode.ai/v2/docs/…`); it has **no Android distribution either** (no android/termux/apk mentions anywhere in v1 or v2 docs).
- **License: MIT** (`LICENSE` fetched from the repo). Bundling and redistributing the binary would be license-permitted. Licensing is therefore explicitly *not* the blocker.
- Release assets (v1.19.1, and identically in v1.19.0/v1.18.0): `opencode-{darwin-arm64, darwin-x64(-baseline), linux-arm64(-musl), linux-x64(-baseline)(-musl), windows-arm64, windows-x64(-baseline)}` plus Electron **desktop** builds (`.AppImage/.deb/.rpm/.dmg/.exe` + blockmaps). **No `android-*`, no `.apk`, no `.aab` in any release inspected.**
- Official install script (`https://opencode.ai/install`): supported matrix is exactly `linux-x64|linux-arm64|darwin-x64|darwin-arm64|windows-x64` — everything else prints `Unsupported OS/Arch`.
- Docs describe the server mode this mission proposed to use: "`opencode serve` runs a headless HTTP server that exposes an OpenAPI endpoint" (default hostname **127.0.0.1**, port 4096, optional HTTP basic auth via `OPENCODE_SERVER_PASSWORD`).

### 4.2 Binary analysis of the official ARM64 artifacts (the ones a phone would need)

Downloaded from `releases/download/v1.19.1/…` (compressed sizes and SHA-256 in the evidence manifest):

| Artifact | Uncompressed | ELF interpreter | NEEDED libs | Verdict for Android |
|---|---|---|---|---|
| `opencode-linux-arm64.tar.gz` (60,526,393 B) | 177 MB | `/lib/ld-linux-aarch64.so.1` | `libc.so.6`, `ld-linux-aarch64.so.1`, `libpthread.so.0`, `libdl.so.2`, `libm.so.6` | **Cannot load** — Android has no glibc |
| `opencode-linux-arm64-musl.tar.gz` (62,644,315 B) | 186 MB | `/lib/ld-musl-aarch64.so.1` | `libstdc++.so.6`, `libc.musl-aarch64.so.1`, `libgcc_s.so.1` | **Cannot load** — no musl loader / libstdc++ on Android |
| `opencode-linux-x64.tar.gz` (60,707,458 B) | 178 MB | `/lib64/ld-linux-x86-64.so.2` | glibc set | Reference-host build (ran here) |

Both ARM64 artifacts are *dynamically linked*; there is no static Android-compatible build. Android's C runtime is **bionic**; a glibc/musl-linked ELF will not start on Android regardless of packaging (jniLibs `nativeLibraryDir` trick included — the kernel would fail to find the interpreter before the app could do anything). Anvex's own `KNOWN_LIMITATIONS.md` records the identical precedent for Node.js.

### 4.3 Live PoC on the reference host — the genuine runtime works headlessly

Executed with the genuine v1.19.1 linux-x64 binary, isolated `$HOME` (no login, `auth.json` never created):

1. **Launch/health:** `opencode serve --port 4311` → listening on `127.0.0.1` in ~2 s; `GET /api/health` → `{"healthy":true}`; OpenAPI 3.1 spec served at `/doc` (479,038 B). Runtime warns: *"OPENCODE_SERVER_PASSWORD is not set; server is unsecured"* — an in-app integration would always set a per-launch token (or use loopback + in-process only).
2. **Real request through the supported interface:** `POST /api/session` with model `opencode/space-bunny-free` → session `ses_ebe8c1e45ffe…`; `POST /api/session/{id}/prompt` `{"prompt":{"text":"Reply with exactly: PONG"}}` → HTTP 200, `admittedSeq:1`.
3. **Actual response received and parsed:** assistant message completed in ~0.7 s — content `"PONG"`, `finish:"stop"`, `cost:0`, tokens `input 1 / output 4 / cache-read 3197`. Full SSE lifecycle captured (`session.next.prompt.admitted → prompted → step.started → text.started → text.ended("PONG") → step.ended`).
4. **Model list through the runtime:** 41 entries, 15 `-free` ids — matches the public discovery endpoint Anvex already uses.
5. **Shutdown/restart:** SIGINT was ignored for 20 s; **SIGTERM terminated the process (exit 143)**; a fresh `serve` start afterwards behaved identically (repeatable).
6. **Filesystem footprint:** XDG layout — `~/.config/opencode/`, `~/.local/state/opencode/` (locks), `~/.local/share/opencode/` (SQLite `opencode.db` + WAL/SHM, `repos/`, `log/`), `~/.cache/opencode/` (`bin/`, `models.json`). Watcher backend: `inotify`.
7. **CLI (`opencode run`) needs a controlling terminal:** without a pty it hangs indefinitely (>150 s, twice); under a pty (`script -qec`) the same command completes in ~5 s and prints `PONG`. Anvex's `KNOWN_LIMITATIONS.md` already documents that Android apps cannot provide termios ptys without root.

**Conclusion of the PoC:** the runtime + free tier are healthy *on desktop Linux*. This is exactly the architecture the mission proposed — and it is the one that cannot be moved onto Android with official artifacts (§4.2, §4.6).

### 4.4 The free-tier gate — two-sided evidence (re-verified today)

| Client | Same free model `space-bunny-free`, `POST /inference/openai/v1/chat/completions` | Result |
|---|---|---|
| Anvex with its **honest** UA (`Anvex/4.4 (Android; probe)`), no credential | probe P3/P5 | **HTTP 403** `{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode. Download it at https://opencode.ai and keep building."}` |
| **Genuine official runtime** (serve PoC, no login) | §4.3 | **HTTP 200-equivalent — completion "PONG", cost 0** |

Supporting probes (all with Anvex UA, today): public discovery `GET /inference/v1/models` → **200** (7,308 B; catalog drifted since 2026-10-15 — now includes `big-pickle`, `exo-free`, `fledge-alpha-free`, `ling-3.0-tiny-free`, … which is why Anvex discovers models live instead of hardcoding); paid model without key → **401 AuthError "Missing API key."**; invalid bearer on models → **401 "Invalid credential"**; nonexistent model → **400 "Model is unavailable."**; per-family models path → **404**.

The gate is therefore *client-identity-based*, not account-based: the official client passes **without login**; every third-party client is refused. The only ways a third-party app could pass are (a) run the genuine runtime (blocked on Android, §4.2/§4.6) or (b) reproduce the official client's fingerprint — which is client impersonation to evade a restriction, explicitly forbidden by the mission and by Anvex's security policy (v4.4.1 already documents why the `opencode-free-9router` approach is out of bounds).

### 4.5 Free-tier policy details relevant to this user

From the live OpenCode Console docs (fetched today; `opencode.ai/docs/zen/` now redirects to `opencode.ai/v2/docs/console/models/`):

- Free models are **"free on OpenCode for a limited time"** (stealth/promo models: Space Bunny, Cat Jam, Big Pickle, Step 5 Preview, MiMo/Nemotron/Ling/DeepSeek `-free` variants, …). Availability and limits can change without notice — Anvex's UI already says exactly this.
- **Region restriction:** *"Cat Jam Free … is not available in Angola, Belarus, Cambodia, China (including Hong Kong and Macau), Cuba, the Democratic Republic of the Congo, Equatorial Guinea, **Iran**, Iraq, Laos, Libya, Nigeria, North Korea, Russia, Syria, Turkmenistan, Uzbekistan, Venezuela, or the Crimea, Donetsk, and Luhansk regions of Ukraine."* The user's locale (Iran) is on that list for at least this model. Space Bunny Free completed fine from this sandbox's **Hong Kong** egress IP; its availability from Iranian networks was **not tested** and is **not claimed either way**. Anvex must (and does) surface provider errors verbatim rather than promising availability.
- Data-use notes worth knowing: Space Bunny Free's provider follows a zero-retention, no-training policy; Cat Jam Free retains data 30 days "for safety reasons"; **Muse Spark 1.3 Contributor Free** grants heavily discounted pricing *in exchange for permission to use prompts/completions for training* — worth surfacing in the model description before a user opts into it.

### 4.6 Why the runtime cannot run inside the Anvex app sandbox

1. **C runtime (B2):** glibc/musl dynamic ELVs cannot load on bionic. No official static/Android build exists (B1).
2. **Exec policy (B3):** Anvex targets SDK 35. Since Android 10, apps targeting API 29+ cannot `exec()` binaries from their writable app storage ("Removed execute permission for app home directory … a W^X violation" — official Android docs). Shipping the binary as a `jniLibs` entry makes it executable from `nativeLibraryDir`, but the binary still fails at load (point 1) — and would still be a 177 MB non-NDK blob.
3. **Size (B4):** 177–186 MB uncompressed (60–63 MB compressed) for the runtime alone vs Anvex's complete 28.25 MB APK — a ~6.3× APK growth before any tooling, plus per-project copy/snapshot storage the runtime creates. Mission §4 explicitly forbids packaging an entire development toolchain.
4. **Environment (B4):** the runtime expects XDG config/state/share dirs, writes a SQLite DB, exposes pty endpoints (`/api/pty` — no termios ptys on Android without root), uses `inotify` (kernel-present on Android, OK), runs a `__workspace_agent` shell wrapper via the system shell, and its VCS features require a system `git` (none on stock Android; basic chat itself did work in a non-git directory during the PoC — that narrower claim is verified).
5. **Verification honesty (B6):** even if one attempted all of the above blind, this environment has **no Android device, no `/dev/kvm`, no ARM host, no qemu-user**, so the mandated on-device PoC (launch on Android 13/ARM64, health, real request, clean restart) could not be executed. The mission forbids declaring success without it — so this report does not.

### 4.7 Licensing and distribution

MIT (runtime) — redistribution inside an APK would be permitted **if** a compatible binary existed. The Electron desktop builds bundle additional dependencies under their own licenses and are irrelevant here (desktop-only). No license-based blocker exists; the blockers are technical and policy-based (B1–B6).

### 4.8 Not verifiable in this environment (explicit list)

- Any execution on a real Android 13/ARM64 device (no device, no emulator possible: no KVM; x86_64 host).
- Free-tier behavior from Iranian networks (sandbox egress is Hong Kong).
- OpenCode Console (API-key) live chat — no key exists in this environment; covered by MockWebServer tests only (unchanged from v4.4.1).

---

## 5. The closest legitimate on-device architecture (already in Anvex v4.4.1, re-verified today)

Anvex v4.4.1's OpenCode provider is the nearest architecture that satisfies the mission's primary requirement — *everything runs on the Android device itself*:

- **Pure in-app HTTP** (Kotlin/OkHttp, no child processes, no Termux, no relay, no bundled runtime): discover models live from the public endpoint (`200`, re-verified), send chat with Anvex's **own** identity, and surface the upstream verdict verbatim — today `HTTP 403 FreeTierError`, mapped to the dedicated `AiError.Kind.ACCESS_RESTRICTED` (distinct from 401 auth failures) with the two-step connection test reporting "discovery OK, chat restricted upstream" instead of a fabricated success.
- **Security boundaries kept:** no official-client impersonation (asserted by test), keys never logged (asserted by redaction tests), 403 passed through unmodified.
- **Console (API-key) tier** remains the documented, permitted authenticated path (already shipped as a separate provider kind; out of this mission's scope but preserved).
- **If upstream policy ever changes** (official Android runtime, or a permitted third-party path), `validateConnection()` flips to reporting success automatically — no code change needed, because it probes reality instead of hardcoding a verdict.

A hypothetical future ":core:opencode-runtime" module remains the right shape *if and when* OpenCode ships an Android-compatible runtime (bionic/NDK build or official mobile distribution). Building that scaffolding today would wrap a binary that cannot execute — fake utility — so it is deliberately not added.

---

## 6. Fresh verification of the Anvex tree (commands actually run, 2026-10-15 UTC)

Environment: Temurin JDK 17.0.20.1+1 (system Java was a JRE — JDK downloaded this session), Gradle 8.10.2 wrapper, Android SDK 35 / build-tools 35.0.0, `--max-workers=1` (4 GB-box profile).

| Command | Outcome |
|---|---|
| `./gradlew cleanTest test -Dorg.gradle.caching=false --max-workers=1 --continue` | **BUILD SUCCESSFUL** — **417 test executions = 336 unique tests** (Android modules run debug+release variants), **0 failures, 0 errors, 0 skipped** (app 42, core:ai 79, core:android 120, core:engine 176; XML timestamps confirm this session's execution) |
| `./gradlew :app:assembleDebug --max-workers=1` | **BUILD SUCCESSFUL** (all tasks up-to-date: outputs already matched inputs) |
| `rm app-debug.apk && ./gradlew :app:packageDebug --max-workers=1` | **Task executed fresh** → APK **byte-identical** to the delivered v4.4.1 APK: 28,250,395 B, SHA-256 `82b685c0f6aeeb33c79d977b536d78cdb62663d7316c6e1468ce8810a80d2baf` (reproducible build confirmed) |
| `./gradlew :app:lint --max-workers=1` | **0 errors, 57 warnings** (= the established baseline; `lint-results-debug.xml`) |
| `aapt dump badging` on the APK | `versionCode 66`, `versionName 4.4.1-debug`, minSdk 26, targetSdk 35, ABIs arm64-v8a/armeabi-v7a/x86/x86_64 |

Not run (environment-blocked): any on-device instrumentation test — requires a physical device.

## 7. Files changed by this audit

**Code: none.** The v4.4.1 codebase already implements the closest legitimate architecture, and today's evidence contradicted nothing in it — so no source files were modified (per the mission's rule against claiming modifications that did not happen). Documentation-only additions:

| File | Change |
|---|---|
| `docs/OPENCODE_RUNTIME_FEASIBILITY.md` | **New** — this report |
| `CHANGELOG.md` (project root) | Entry for the feasibility audit (docs-only) |
| `docs/KNOWN_LIMITATIONS.md` | Pointer to this report + region-restriction and PoC-outcome facts |

## 8. Reproducible procedures

**Build the app** (from the source ZIP root `Anvex/`):
```bash
export JAVA_HOME=<JDK 17>          # e.g. Temurin 17.0.20.1+1
export ANDROID_HOME=<Android SDK with platform 35 + build-tools 35>
./gradlew --no-daemon :app:assembleDebug --max-workers=1
# → app/build/outputs/apk/debug/app-debug.apk (28,250,395 B)
```

**Re-run the upstream probes** (safe, non-destructive, honest UA):
```bash
bash tools/opencode_probe.sh          # P1–P8 against https://opencode.ai
```

**Re-run the runtime PoC** (any linux-x64/amd64 host; script shipped in `download/evidence/poc_opencode_serve.sh`):
```bash
curl -fsSL https://opencode.ai/install | bash   # or download the release tarball
opencode serve --port 4311                      # headless, loopback
curl -s localhost:4311/api/health               # {"healthy":true}
# then POST /api/session {"model":{"id":"space-bunny-free","providerID":"opencode"}}
# and  POST /api/session/<id>/prompt {"prompt":{"text":"Reply with exactly: PONG"}}
```

## 9. Explicit non-claims

- No claim that OpenCode Free chat works in Anvex — it does not (upstream 403; surfaced honestly).
- No claim that the official runtime was executed on Android — it was not (impossible here; and impossible with official artifacts per §4.2).
- No claim about free-tier availability from Iranian networks (untested from this environment).
- No claim of a live Console-key chat (no key in this environment).
- No client fingerprint, attestation header, or official-client identity was reproduced anywhere.

## 10. Evidence index

`download/evidence/` contains: `anvex_probe_2026-10-16.txt` (P1–P8), `poc_serve_run3.log` (full serve PoC incl. SSE), `pty_test.out` (177-byte pty transcript with `PONG`), `opencode_artifact_sha256.txt` (runtime artifact hashes), `opencode_release_assets_v1.19.1.txt` (full asset list), `v2_console_models.html` (provenance of the policy/region quotes), `test_full_executed.log`, `packageDebug_fresh.log`, `lint.log`, `poc_opencode_serve.sh`, and `openapi.json` (the runtime's served OpenAPI 3.1 spec).

## Sources (all fetched live 2026-10-15/16)

- OpenCode repository & releases: `https://github.com/anomalyco/opencode` (redirect target of `github.com/sst/opencode`); releases v1.18.0–v1.19.1 asset lists; `LICENSE` (MIT); `README`.
- Install script & platform matrix: `https://opencode.ai/install`.
- npm package metadata: `https://registry.npmjs.org/opencode-ai` (v1.19.1; `os: darwin/linux/win32`).
- Server docs: `https://opencode.ai/docs/server/` (serve, port 4096 default, loopback, `OPENCODE_SERVER_PASSWORD`).
- Console/models & free-tier policy: `https://opencode.ai/docs/zen/` → `https://opencode.ai/v2/docs/console/models/` (limited-time free models; region exclusions incl. Iran; data-use notes).
- Android exec policy: `https://developer.android.com/about/versions/10/behavior-changes-10` ("Removed execute permission for app home directory"; W^X).
- Android NDK/bionic: `https://developer.android.com/ndk` (Android's libc is bionic; native code must be built with the NDK).
- Anvex internal precedent: `docs/KNOWN_LIMITATIONS.md` (v4.4 Node.js on Android: "Node targets glibc; Android runs bionic").
