# Anvex v2.9.0 — Deep Audit Hardening Report

## Scope

This pass targets the findings from the v2.8.0 deep audit: packaging regressions, durable retry/restart behavior, approval authority duplication, staged restore, automatic backup reliability, migration governance, LAN hardening, ZIP safety, and build/runtime boundaries.

## Corrective actions

- Restored `ToolchainManager`, `AndroidBuildEngine`, and `BuildScreen` into the source package.
- Added the missing Room `approvalGrantDao()` accessor.
- Added `nextRunAt` to the Room task index and retained durable retry scheduling.
- Cancellation now covers `RETRYING` tasks.
- Android build tasks are resumable/restart-safe and retry-bounded.
- Removed the unused in-memory `ApprovalEngine` duplicate from the production source graph.
- Agent approval capability mapping now uses the actual action safety assessment.
- Project backup restore is staged and replays a pre-restore archive on commit failure.
- Automatic recovery snapshots now include bounded task and approval metadata, never task payloads/checkpoints.
- Automatic backup triggering moved from a process-bound coroutine loop to a process-independent `AlarmManager` receiver.
- LAN authentication uses constant-time token comparison and limits concurrent clients.
- Unsafe ZIP directory entries are rejected instead of silently ignored.
- Migration tests cover the complete 1→10 chain and the 9→10 retry column.
- Developer/Build UI reflects retry state and cancellation.

## Verification

- PFP production-compliance static scan: PASS.
- JSON evidence validation: PASS.
- Source artifact hygiene: PASS; no APK/AAB/keystore artifacts in source tree.
- Gradle/JVM test execution: BLOCKED by environment because Gradle 8.10.2 is not cached and `services.gradle.org` cannot be resolved. No BUILD_VERIFIED claim is made.
- Device runtime verification: pending a successful build and real Android device run. No RUNTIME_VERIFIED claim is made.

## Remaining release gates

The source is not yet `PRODUCTION READY` until BUILD_VERIFIED and RUNTIME_VERIFIED are obtained. Runtime capabilities that are intentionally foundation-only (Linux/proot, bundled Node/npm/pnpm, executable plugin sandbox, full PTY, and full Git runtime) remain documented product limitations rather than false completion claims.
