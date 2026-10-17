# Batch 4 — Hardening / Production Foundations Audit

## Scope
Batch 4 prepares the architecture for the remaining DSHA-inspired runtime and release work. It does **not** claim a real Linux userspace or production runtime verification without a device.

## Implemented in this batch
- Durable session/project-scoped approval grants persisted in Room.
- Approval grants have mandatory TTL, capability scoping, and expired-grant cleanup.
- Agent task bridge can consult durable approvals before granting high-impact agent steps.
- AI shell and persistent terminal denylist sensitive project credential/config paths.
- Room schema advanced from v8 to v9 with explicit additive migration; no destructive fallback.
- Release version advanced to 2.6.0 / versionCode 33.
- Regression coverage added for approval scope and expiry.
- PFP evidence and documentation remain conservative: static verification only.

## Explicit limitations
- Linux/proot userspace is feasibility-only until real-device validation.
- Plugin and skill arbitrary code execution remain disabled until a sandbox is available.
- Full backup of all agent/session/task state is not yet complete.
- Terminal streaming/process-tree quotas and full UI approval center remain roadmap items.
- Gradle build/runtime verification is blocked in this environment because the wrapper cannot resolve services.gradle.org.

## Gate
`STATIC_VERIFIED — BATCH 4 FOUNDATIONS ACCEPTED`

This is not a production-ready verdict.
