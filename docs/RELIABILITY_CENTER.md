# Anvex Reliability & Developer Center — 2.2.0

This phase adds the 18 low-risk DSHA-inspired capabilities selected for Anvex, without importing an unrestricted Linux runtime.

## Implemented now
1. Developer Center
2. deterministic Health Center
3. durable Activity Log
4. durable task records
5. interrupted-task detection
6. safe task resume for built-in repairs
7. encrypted device-local automatic recovery snapshots
8. newest-3 automatic backup rotation
9. backup integrity/decryption inspection
10. corrupt chat-session quarantine
11. bounded preview-cache repair
12. stale build-workspace repair
13. manifest-only Skills/Plugin manager
14. plugin import/export
15. built-in plugin protection
16. Android device/toolchain diagnostics
17. side-effect-free dangerous-command classification
18. explicit recovery/security boundaries and PFP evidence

## Security boundary
- Automatic snapshots deliberately exclude provider API keys.
- Provider secrets continue to use the existing Keystore-backed path.
- Imported skill/plugin manifests are metadata only and are never executed.
- Repair actions are allowlisted and operate only on Anvex-owned cache/state.
- Dangerous-command classification does not execute or approve a command by itself. It is an approval policy primitive for the future runtime executor.

## Verification boundary
Only `STATIC_VERIFIED` is claimed in this release. Full Gradle build, instrumentation, installability, and device runtime verification remain subject to the existing documented environment limitation. A static implementation claim is not a production-readiness claim under the current project PFP governance contract.
