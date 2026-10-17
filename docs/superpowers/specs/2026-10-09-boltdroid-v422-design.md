# BoltDroid v4.2.2 Hardening Design — 2026-10-09

## Goal

Turn the v4.2.1 source tree (never compiled — see CHANGELOG "Not verified")
into a verified, shippable **v4.2.2**: full Gradle compile + JVM tests green +
debug APK builds + release gates green on an honest scope. No new features.

## Context (established 2026-10-09)

- Source: `BoltDroid-v4_2_1.zip`, 830 files, extracted to `worktree-v4`, git baseline `2c58e1e`.
- `final_release_audit.py` reports NOT_RELEASE_VERIFIED: `room_schema_gate` PASS,
  `deep_audit` CRASHES on Windows (cp1252 decode of `assets/harness/bridge.js`),
  `build_environment_gate` BLOCKED on Windows (`gradlew` WinError 193).
- Host has JDK 17 + Android SDK (`platforms`, `build-tools`, `platform-tools`),
  no Gradle binary, no device. `scheduler.py` suite (23 tests) is green and untouched.

## Scope IN

1. P0 — Repair `tools/deep_audit_30.py` (UTF-8 reads) and
   `tools/verify_build_environment.py` (Windows: `gradlew.bat`, no POSIX exec).
2. P1 — First real compile of all v4.2 code; fix kotlinc errors with
   behavior-preserving changes only. Full `./gradlew test` green (engine/AI/Android/app).
   Room schema stays v12; snapshots regenerate only via real KSP build.
3. P2 — `:app:assembleDebug` succeeds; offline harness assets (jszip, fonts),
   source mirror, and MediaStore/FileProvider paths packaged correctly.
4. P3 — Static review limited to the v4.0→v4.2.1 delta
   (memory, agent tools, Nebula, secret-redaction ZIP fix, providers screen, …).
5. P4 — Fix bugs the build/tests/delta review prove; correct false doc claims.
6. P5 — Bump to 4.2.1→4.2.2 / versionCode 63→64, CHANGELOG entry, regenerate
   source mirror + `FINAL_RELEASE_AUDIT.json`, repackage release ZIP.

## Scope OUT

New features, UI redesign, physical-device smoke test (stays a manual checklist
item — no device on this host), Play signing, any network verification beyond
Gradle dependency downloads.

## Risks

- Gradle distribution + dependency downloads need network (~hundreds of MB).
- v4.2 Kotlin may carry many compile errors; fix per-file, no refactors, no scope creep.
- KSP/Room regen may surface schema drift → resolve by evidence, never fabricate snapshots.

## Success criteria

- `gradlew test`: all suites green.
- `gradlew :app:assembleDebug`: APK produced.
- `final_release_audit.py`: green except honestly-flagged device-only items.
- Release ZIP + audit JSON regenerated; everything committed on `main`.

## Follow-up (separate spec, after v4.2.2)

scheduler.py completion: update/delete ops, list filters, atomic file
persistence, minimal CLI, extended tests — stdlib-only, same validation style.
