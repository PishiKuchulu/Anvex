# Anvex — 18-item capability completion

## Scope
This release completes the remaining UI/foundation gaps in the 18-item DSHA-inspired capability list.

### Completed in this release
- Pre-restore inspection with a separate commit step.
- Automatic backup policy: startup freshness check plus bounded six-hour process-local interval, with newest-three rotation.
- Installation/Task Manager UI over the durable Room-backed task engine.
- Dangerous Action Approval UI backed by the durable approval repository; execution remains gated by runtime safety policy.
- LAN authentication architecture and read-only status endpoint using a process-generated bearer token; credentials/files/commands are never exposed.
- Remote/LAN status page.
- In-app floating Agent status UI (intentionally not a system overlay and does not keep the app alive in background).
- Repair Center: diagnose then enqueue bounded repairs through the durable task engine.
- Runtime/toolchain diagnostics are surfaced in Health and the Repair Center; fixes remain bounded to supported app-owned repairs.
- Durable retry scheduling uses `nextRunAt` and does not block the worker during backoff.

## Explicit boundaries
- LAN is status-only; it is not a remote shell or remote agent execution channel.
- Floating status is in-app only; `SYSTEM_ALERT_WINDOW` is intentionally not requested.
- Plugin/Skill imported manifests remain non-executable until a real sandboxed runtime exists.
- BUILD_VERIFIED/RUNTIME_VERIFIED require a real Gradle/toolchain/device environment and are never inferred from static analysis.
