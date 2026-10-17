# Anvex Phase 0 — Re-baseline & Architecture Freeze

## Baseline
- Source baseline: Anvex v2.1.4
- Target: DSHA capability/architecture clone under PFP governance
- Modules: app, core:model, core:engine, core:ai, core:android
- Kotlin source files: 87
- Source-counted JVM test methods/annotations: 147

## Audit findings

### P0 fixed during Phase 0
1. **Room schema version mismatch** — `AnvexDatabase` declared version 6 while the production migration chain already contained `MIGRATION_6_7` and provider `extraHeadersEnc` required schema v7. The database version is now 7.
2. **Release metadata mismatch** — app source declares `versionCode 26`, while README documented 24. README is now aligned to 25.

### Confirmed architectural facts
- Explicit Room migration chain v1→v2→v3→v4→v5→v6→v7 exists.
- Destructive migration fallback is absent.
- Provider extra headers have a v7 encrypted-at-rest path.
- AI shell/start actions are not arbitrary OS process execution.
- Android build execution remains an explicit user-triggered subsystem.
- ActionSafetyPolicy currently classifies risk but is not yet the final execution-enforcement boundary.
- Skills/plugins are currently manifest-oriented foundations, not arbitrary executable plugin runtimes.
- Full Linux/Node runtime parity is not yet implemented.
- PFP currently remains `NEEDS MORE WORK`; no build/runtime verification is claimed from the source archive alone.

## Architecture freeze decision
The project will evolve in this order:

1. Reliability + durable Task Engine
2. Agent Orchestrator
3. Security/Approval enforcement
4. Workspace Runtime
5. Controlled Shell Runtime
6. Node Runtime
7. Skills Runtime
8. Plugin Runtime
9. Self-Healing
10. Full Backup/Restore
11. Git Runtime
12. Android IDE completion
13. Linux userspace feasibility and implementation only if justified
14. Full integration and device verification

Linux/userspace work must not bypass the security, permission, recovery, and task infrastructure.

## Verification status
- Static source/PFP scan: STATIC_VERIFIED
- Full Gradle build: pending configured/networked environment
- Device/runtime verification: pending

## Phase 0 gate
`STATIC_VERIFIED — PHASE 0 BASELINE ACCEPTED`

This gate does **not** mean production readiness.
