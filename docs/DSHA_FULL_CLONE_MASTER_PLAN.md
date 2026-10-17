# Anvex — DSHA Full-Clone / Android Agent Runtime Master Plan

**Baseline:** Anvex v2.3.0 (Batch 1 working tree)  
**Status:** MASTER PLAN — ready for implementation  
**Governance:** PFP Production Compliance + Security Gates

## Mission

Evolve Anvex from an Android IDE with AI integration into a secure, resilient, extensible Android Agent Platform with DSHA-level capabilities. This is a capability/architecture clone, not a blind copy of DSHA source code.

### Completion invariant

Never claim a feature is complete merely because UI or data models exist. A feature is complete only when:

`UI/Input → State → Persistence → Domain/Logic → Actual Subsystem → Runtime Behavior → Verification`

## Current baseline

- Native Kotlin/Compose Android app.
- Android IDE foundation: editor, project/workspace, search/replace, build/toolchain, preview.
- AI provider abstraction and streaming.
- Reliability Center foundation: diagnostics, tasks, backups, quarantine, plugin/skill foundations.
- Dangerous-action classification foundation.
- PFP static compliance tooling.
- Latest audited version: **2.3.0 / versionCode 28** (Batch 1 static baseline).
- Current source-counted test annotations: **148**.
- Clean source package contains no APK/AAB/keystore artifacts.
- Full Gradle verification previously remained blocked by unavailable Gradle distribution/network; therefore never claim BUILD_VERIFIED/RUNTIME_VERIFIED without new evidence.

## Target architecture

```text
Anvex
├── Presentation
│   ├── Home / Projects / Editor
│   ├── Chat / Agent
│   ├── Build / Preview
│   ├── Developer Center
│   ├── Tasks / Activity
│   ├── Skills / Plugins
│   └── Settings
├── Application
│   ├── AgentOrchestrator
│   ├── TaskEngine
│   ├── WorkflowEngine
│   ├── ApprovalEngine
│   ├── RecoveryEngine
│   └── DiagnosticsEngine
├── Domain
│   ├── Project / Workspace / Agent / Task
│   ├── Tool / Skill / Plugin
│   ├── Permission / Backup / Runtime
│   └── Capability model
├── Execution
│   ├── ActionDispatcher
│   ├── ActionSafetyPolicy
│   ├── ToolRegistry
│   ├── ShellGateway
│   ├── BuildGateway
│   ├── PreviewGateway
│   └── RuntimeGateway
├── Runtime
│   ├── Android Native
│   ├── Workspace Runtime
│   ├── Shell Runtime
│   ├── Node Runtime
│   └── Linux Userspace (future/conditional)
├── Persistence
│   ├── Room / encrypted preferences
│   ├── Android Keystore
│   ├── Backup store
│   └── Artifact/cache store
└── Governance
    ├── PFP Evidence
    ├── Security Gates
    ├── Build/Runtime Gates
    └── Artifact Hygiene
```

# Master roadmap

## PHASE 0 — Re-baseline & Architecture Freeze
- [x] Verify v2.1.3 source completeness and references; Phase 0 re-baseline completed in v2.1.4.
- [ ] Remove stale/duplicate source variants.
- [ ] Map execution and persistence paths.
- [ ] Map secret-bearing paths.
- [ ] Define module ownership/API boundaries.
- [ ] Create ADRs for major architecture decisions.
- [ ] Establish PFP evidence baseline.

**Gate:** STATIC_VERIFIED.

## PHASE 1 — Reliability Center FULL
- [~] Replace hardcoded health results with real subsystem checks (remaining).
- [ ] Structured activity log, correlation IDs, task/session IDs, filtering, retention, sanitized export.
- [x] Full durable task state machine foundation: QUEUED/RUNNING/WAITING_APPROVAL/PAUSED/FAILED/INTERRUPTED/COMPLETED/CANCELLED/RETRYING.
- [x] Durable checkpoints and process-death recovery foundation.
- [ ] Recovery journal, snapshots, rollback.
- [ ] Backup integrity and recovery preview.

**Current gate:** STATIC_VERIFIED — PHASE 1 FOUNDATION ACCEPTED.
**Phase gate:** BUILD_VERIFIED + targeted RUNTIME_VERIFIED.

## PHASE 2 — Agent Orchestration Engine
- [x] Planner → bounded AgentPlan → step result pipeline.
- [x] Durable task bridge with compact step checkpoints.
- [x] Cancellation-aware execution and resumable step boundary.
- [x] Provider-independent orchestration contract.
- [ ] Persistent agent sessions/lifecycle.
- [ ] Context manager and compaction.
- [ ] Tool schema/result normalization.
- [ ] Provider fallback and token/cost accounting.
- [ ] Long-running agent sessions.

## PHASE 3 — Tool & Action Security
- [x] Wire ActionSafetyPolicy into ActionDispatcher.
- [x] Capability declarations and risk levels.
- [x] READ_ONLY / WORKSPACE_WRITE / BUILD / NETWORK / DEVICE / SYSTEM / DESTRUCTIVE classes.
- [x] Approval callback gate and session-scoped expiring grants.
- [x] Deny-by-default for unknown actions.
- [x] Normalize paths before checks and bound mutation sizes.
- [ ] Persistent approval UI and audit every approved action.
- [ ] Project/session permission persistence.
- [ ] Full command-injection/policy-bypass fuzzing.

**Invariant:** AI never becomes an unrestricted shell.

## PHASE 4 — Full Workspace Runtime
- [x] Virtual workspace abstraction.
- [x] Bounded read/list/write/delete/move/copy through the mutation chokepoint.
- [x] Path and file-size safety budgets.
- [ ] Atomic multi-file transactions and rollback.
- [ ] Lock/concurrency protection.
- [ ] File watchers where supported.
- [ ] Ignore rules and indexing.
- [ ] Search/symbol metadata.
- [ ] Workspace snapshots and rollback.

## PHASE 5 — Terminal / Shell Runtime
- [x] Real Android `/system/bin/sh` process launcher.
- [x] Working directory, bounded environment, exit code and timeout.
- [x] Persistent interactive shell session foundation.
- [x] Bounded output capture.
- [x] AI-origin command allowlist + approval gate.
- [ ] True streaming stdout/stderr channels.
- [ ] Process tree cancellation and resource quotas.
- [ ] Permission audit log.
- [ ] Terminal UI and command history.

## PHASE 6 — Node.js Runtime
- [ ] Decide bundled vs downloadable runtime.
- [ ] ABI/device compatibility matrix.
- [ ] Node runtime/process launcher.
- [ ] npm/pnpm support.
- [ ] Package cache and project dependency strategy.
- [ ] Environment/resource isolation.
- [ ] Diagnostics and cleanup.

**Gate:** real Node project build/test on supported devices.

## PHASE 7 — Linux Userspace Runtime
First perform feasibility research:
- [ ] proot feasibility.
- [ ] Android/SELinux/process/storage/network constraints.
- [ ] arm64 baseline and Android 8+ compatibility.
- [ ] lifecycle, battery, security and package installation.

Possible target:

```text
Anvex → Runtime Manager → Linux Userspace → shell / Node / Git / package manager / tools
```

Do not mark complete without real device evidence.

## PHASE 8 — Skills Runtime
- [ ] Skill manifest/package format.
- [ ] Versioning, dependencies, compatibility.
- [ ] Permissions/capabilities.
- [ ] Install/enable/disable/update/rollback.
- [ ] Integrity/signature verification.
- [ ] Sandboxed execution and resource limits.
- [ ] Lifecycle/logging.
- [ ] Built-in Android build, Kotlin, Compose, Git, search, refactor, test, preview, diagnostics and recovery skills.

## PHASE 9 — Plugin Runtime
- [ ] Plugin package/manifest format.
- [ ] Versioning/dependency resolution.
- [ ] Permission/capability declarations.
- [ ] Integrity/signature verification.
- [ ] Install/uninstall/enable/disable/upgrade/downgrade/rollback.
- [ ] Built-in protection and import/export.
- [ ] Marketplace metadata.
- [ ] Sandboxing, resource limits, crash isolation and logs.

**Security:** untrusted plugins never receive undeclared capabilities.

## PHASE 10 — Self-Healing Engine
- [ ] Cache, build workspace, database, task, session, provider, toolchain, plugin, skill and runtime repair classes.
- [ ] Detect → Diagnose → Risk classify → Snapshot → Approval → Repair → Verify → Rollback → Audit.

## PHASE 11 — Full Backup / Restore
- [ ] Project/workspace backup.
- [ ] Agent session/task state backup.
- [ ] Settings/provider configuration backup.
- [ ] Encrypted secret payload.
- [ ] Versioned format + integrity hash.
- [ ] Partial/preview restore.
- [ ] Rollback and automatic rotation.
- [ ] Compatibility migrations.

**Security:** secrets exist outside the encrypted payload only when explicitly justified and documented.

## PHASE 12 — Developer Center 2.0
Sections:
- [ ] Health
- [ ] Tasks
- [ ] Activity
- [ ] Recovery
- [ ] Backups
- [ ] Skills
- [ ] Plugins
- [ ] Providers
- [ ] Toolchains
- [ ] Runtime
- [ ] Security
- [ ] Diagnostics/Logs
- [ ] PFP Evidence

## PHASE 13 — Git / GitHub Runtime
- [ ] Local Git status/diff/commit/branch/checkout/merge/stash.
- [ ] Remote management, clone/pull/push.
- [ ] GitHub authentication and PR workflows.
- [ ] Agent-safe Git operations.
- [ ] Approval for destructive operations.

## PHASE 14 — Android IDE FULL
- [ ] Mature multi-file editor/tabs/file tree.
- [ ] Search/replace/go-to-file/go-to-symbol.
- [ ] Diagnostics/error navigation.
- [ ] Build variants/Gradle tasks.
- [ ] Logcat where feasible.
- [ ] APK install/run and debug workflow where feasible.
- [ ] Preview/device selection.
- [ ] Tablet split layout and keyboard-first workflows.

## PHASE 15 — Agent + IDE Deep Integration
Agent must be able to safely:
- [ ] inspect project;
- [ ] plan;
- [ ] request approval when required;
- [ ] edit files;
- [ ] run tests/build/preview;
- [ ] inspect errors;
- [ ] repair issues;
- [ ] verify result;
- [ ] report exact outcome;
- [ ] leave an audit trail;
- [ ] resume after interruption.

## PHASE 16 — UI/UX & Accessibility
- [ ] Existing DesignSystem consistency.
- [ ] Light/dark/OLED.
- [ ] RTL + Persian/Arabic/English localization architecture.
- [ ] Dynamic text direction.
- [ ] Accessibility labels/touch targets.
- [ ] Tablet layout.
- [ ] Error/loading/empty/offline states.
- [ ] Approval/recovery UX.

## PHASE 17 — Security Hardening
- [ ] Keystore-backed secrets.
- [ ] No plaintext API keys/secrets in logs/crash reports.
- [ ] Secure backups/migrations.
- [ ] Path traversal and zip-slip protection.
- [ ] Plugin/skill package validation.
- [ ] Command injection/shell escape tests.
- [ ] Approval bypass tests.
- [ ] Network/device permission boundaries.
- [ ] Sensitive file protection.
- [ ] WebView/localhost/IPC/exported-component review.
- [ ] Android permission minimization.

## PHASE 18 — PFP Production Compliance
For every major feature:

`Requirement → Implementation → Evidence → Static Verification → Build Verification → Runtime Verification → Security Review → Artifact Hygiene → Production Gate`

Evidence must cover feature, security, build, runtime, tests, migrations, artifacts and limitations.

Final statuses are only:
- `PRODUCTION READY`
- `PRODUCTION READY WITH LIMITATIONS`
- `NEEDS MORE WORK`
- `BLOCKED`

## PHASE 19 — Testing Strategy
### Unit
- [ ] Domain, security, parsers, policies, state machines, persistence, migrations.
### Integration
- [ ] Agent→Tool→Policy→Approval→Execution→Audit.
- [ ] Task→Recovery.
- [ ] Backup→Restore.
- [ ] Plugin/Skill→Sandbox→Runtime.
- [ ] Build→APK.
### Instrumentation/device
- [ ] Navigation, RTL, approvals, recovery, backup, project creation, build, preview.
- [ ] Device matrix: Android 8+, modern Android, arm64, low-RAM and high-RAM classes.

## PHASE 20 — Performance & Resource Management
- [ ] Memory/CPU/disk budgets.
- [ ] Build/preview/log/context limits.
- [ ] Plugin/runtime resource limits.
- [ ] Background execution and battery policy.
- [ ] Large-project performance tests.

## PHASE 21 — Release Engineering
- [ ] Reproducible clean source package.
- [ ] No build/cache/generated junk.
- [ ] No secrets.
- [ ] Version/migration/README/CHANGELOG consistency.
- [ ] PFP evidence, test/security report and known limitations.
- [ ] Release checksum and artifact manifest.

# DSHA capability matrix

| Capability | Current | Full target |
|---|---|---|
| Developer Center | Partial | Full |
| Health | Partial | Real diagnostics |
| Activity Log | Implemented | Full |
| Tasks | Foundation | Full task engine |
| Resume | Limited | Checkpointed resume |
| Recovery | Foundation | Full |
| Backup | Foundation | Full encrypted backup |
| Self-healing | Limited | Deterministic repair engine |
| Skills | Manifest | Executable sandboxed skills |
| Plugins | Manifest | Sandboxed plugin runtime |
| Dangerous actions | Classifier | Approval + enforcement |
| Shell | Limited/build-specific | Controlled terminal runtime |
| Node | Not full | Real runtime |
| Linux userspace | Not implemented | Feasibility → runtime |
| Git | Partial | First-class runtime |
| Agent | Provider/chat | Persistent orchestrator |
| IDE | Foundation | Full Android IDE |
| Preview | Foundation | Full runtime |
| Diagnostics | Foundation | Full |
| Security | Strong foundation | Runtime-enforced |
| PFP | Strong | Continuous production gate |

# Critical architectural rules

1. **No fake completion:** UI ≠ implementation.
2. **No unrestricted AI shell.**
3. **Deny by default** for unknown capabilities/actions.
4. **Long-running operations are durable** where technically possible.
5. **Destructive operations are recoverable** where possible.
6. **Secrets never enter ordinary logs.**
7. **Plugins/skills are untrusted.**
8. **Runtime claims require device evidence.**
9. **PFP evidence is living documentation.**
10. **Android-native UX remains first-class.**

# Recommended implementation order

`0 Re-baseline → 1 Reliability → 2 Task/Agent → 3 Security/Approval → 4 Workspace → 5 Shell → 6 Node → 8 Skills → 9 Plugins → 10 Self-Healing → 11 Backup → 13 Git → 14 IDE → 15 Integration → 7 Linux feasibility/implementation → 16 UX → 17 Security final → 18 PFP → 19–21 release`

Do not start with Linux. The runtime must first have durable tasks, permissions, recovery and security boundaries.

# Phase completion contract

A phase is complete only when:
- [ ] code is implemented;
- [ ] tests are added and passing where environment permits;
- [ ] migrations are reviewed;
- [ ] security is reviewed;
- [ ] UI connects to real logic;
- [ ] persistence connects where required;
- [ ] actual subsystem is connected;
- [ ] documentation and PFP evidence are updated;
- [ ] artifact hygiene passes;
- [ ] build/runtime verification is performed when possible;
- [ ] known limitations are documented.

# Recovery instructions for future sessions

If context is lost, provide this file first, then the latest Anvex source ZIP, PFP evidence, latest audit/report and CHANGELOG.

Use this instruction:

> Continue Anvex from the MASTER PLAN. Do not restart architecture from scratch. First inspect the current source and identify the exact completed phase and gate status. Preserve all existing PFP, security, migration and compatibility decisions. Implement only the next justified phase after an audit. Never claim BUILD_VERIFIED or RUNTIME_VERIFIED without actual evidence.

# Known limitations to remember

- Full Gradle verification may require a networked/configured environment.
- Room schema identity hashes must never be hand-edited.
- Linux userspace is not implemented until its feasibility/implementation phases are completed.
- Node runtime is not yet a DSHA-equivalent full runtime.
- Plugin and Skill systems are foundations until runtime/sandbox work is complete.
- Dangerous-command classification must become an actual enforcement boundary.
- Recovery snapshots are not equivalent to full project backups.
- Some health checks require real subsystem validation.
- Instrumentation/device verification remains necessary.
- Persian/RTL quality must be maintained across every new screen.

# Definition of Done — FULL CLONE / PRODUCTION READY

### Agent
- [ ] Persistent sessions
- [ ] Durable tasks
- [ ] Resume/checkpoints
- [ ] Tool orchestration
- [ ] Provider fallback
- [ ] Context management

### Runtime
- [ ] Controlled shell
- [ ] Node runtime
- [ ] Supported Linux userspace capability if included in final scope
- [ ] Git
- [ ] Build
- [ ] Preview

### Extensibility
- [ ] Skills runtime
- [ ] Plugin runtime
- [ ] Marketplace/import/export
- [ ] Sandboxing

### Reliability
- [ ] Diagnostics
- [ ] Activity
- [ ] Recovery
- [ ] Backup
- [ ] Self-healing

### Security
- [ ] Approval engine
- [ ] Capability enforcement
- [ ] Secret protection
- [ ] Package verification
- [ ] Path/command security
- [ ] Audit trail

### IDE
- [ ] Editor
- [ ] Project tree
- [ ] Search
- [ ] Build
- [ ] Preview
- [ ] Git
- [ ] Device workflow

### Governance
- [ ] PFP evidence complete
- [ ] Static verification
- [ ] Build verification
- [ ] Runtime verification
- [ ] Security gate
- [ ] Artifact hygiene
- [ ] Final production gate

# Final principle

**Anvex should not become “DSHA inside an Android app”.**

Target: **a secure Android-native AI development operating environment.**

DSHA = capability reference.  
PFP = governance layer.  
Android IDE = native foundation.  
Agent Runtime = intelligence layer.  
Runtime/Sandbox = execution layer.  
Recovery/Security = trust layer.

**Next action: PHASE 0 — Project Re-baseline & Architecture Freeze.**


## Batch 2 Status

- Phase 6: Node.js runtime foundation implemented; runtime is external/optional and bounded.
- Phase 8: declarative Skills runtime implemented; executable skills remain gated.
- Phase 9: plugin runtime security boundary implemented; arbitrary plugin code remains disabled.
- Phase 10: deterministic self-healing foundation implemented; repairs are bounded and non-shell.
- Overall status: `STATIC_VERIFIED — BATCH 2 FOUNDATION ACCEPTED`; build/runtime verification pending.


## Batch 3 status
Batch 3 implemented the Android build boundary, durable Agent↔IDE build task integration, hardened GitHub Contents push/pull, and password-encrypted project archive service. The package still requires device/toolchain verification before any production-ready claim.
