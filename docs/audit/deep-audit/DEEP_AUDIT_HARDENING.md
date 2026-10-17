# Anvex Deep Audit Hardening — v2.9.0

## Scope

Full-source hardening pass over the v2.6.0 Batch 4 baseline, focused on P0/P1 findings from the deep audit.

## Fixed

1. **Build packaging regression**
   - Restored `ToolchainManager.kt`.
   - Restored `AndroidBuildEngine.kt`.
   - Restored `BuildScreen.kt`.
   - These sources are now part of the release tree and satisfy existing imports/references.

2. **Startup durable-task race**
   - `AgentIdeBridge.registerBuildHandler(projects)` now executes before the first `taskEngine.drain()`.
   - Persisted `android-build` tasks can no longer be failed at startup solely because their handler has not been registered yet.

3. **Build success contract**
   - A build is successful only when the Gradle process exits with code 0 and an APK is found.
   - The engine uses the validated bundled Gradle launcher when available and falls back to the project wrapper.
   - APK artifacts are copied to an app-private artifact directory before the temporary build staging directory is removed.

4. **Toolchain validation boundary**
   - Requires `toolchain.json` with `platform: android` and a validated ABI.
   - Requires Android JDK, Gradle, SDK platforms and SDK build-tools directories.
   - Oversized or malformed manifests are rejected.

5. **Release metadata**
   - Version advanced to `2.6.1`, versionCode `36`.

## Verification

- PFP production compliance static scan: **PASS**.
- Release artifact scan: no APK/AAB/keystore artifacts in source tree.
- Full Gradle compilation could not be executed in this environment because the Gradle wrapper attempted to download `gradle-8.10.2` from `services.gradle.org` and DNS/network access was unavailable. This is recorded as an environmental limitation, not a false BUILD_VERIFIED claim.

## Remaining P1/P2 work

- Unify in-memory and durable approval authorities.
- Make retry backoff non-blocking while preserving process-death recovery semantics.
- Make build tasks explicitly restart-safe rather than resumable.
- Add transactional/staged project restore.
- Extend backup coverage to agent/session/task state.
- Complete runtime/process quotas and robust streaming.
- Generate and commit complete Room schema snapshots from a configured Gradle environment.
- Run real device/instrumentation verification.

## Gate

`STATIC_VERIFIED` — P0 build packaging and startup-handler findings addressed.
`BUILD_VERIFIED` — pending environment/toolchain availability.
`RUNTIME_VERIFIED` — pending real Android device verification.


## v2.9.0 follow-up hardening

- Restored the Android build/toolchain/build UI sources in the package.
- Added restart-safe Android build tasks (`resumable=true`, bounded retry).
- Retry scheduling is persisted with `nextRunAt` and cancellation now covers `RETRYING`.
- Removed the unused duplicate in-memory approval authority.
- Agent approval capability mapping now considers the actual safety assessment.
- Project restore is staged and rolls back to a pre-restore archive when commit fails.
- Recovery snapshots include bounded task and approval metadata but never task payloads/checkpoints.
- Automatic backup triggering is process-independent via Android AlarmManager.
- LAN token checks use constant-time comparison and concurrent-client bounds.
- ZIP directory entries are rejected when unsafe rather than silently ignored.
- Migration chain tests now cover 1→10 and the retry column.

BUILD_VERIFIED and RUNTIME_VERIFIED remain pending until a Gradle-capable environment and real Android device run are available.
