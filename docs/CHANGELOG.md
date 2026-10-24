> **Historical file — frozen at 3.3.8.** The current changelog is the
> root [`CHANGELOG.md`](../CHANGELOG.md). This file is kept for history
> only (second external review #14: two changelogs that disagree are
> worse than one).

## 3.3.8 — Deep Audit Hardening
- Deep-audit pass fixed LAN web-server wiring, command-translator safety classification, chat artifact execution wiring, build-script confirmation flow, secure legacy provider-header fallback, atomic artifact publishing, and documentation/test-count drift.
- Version 3.3.8 / versionCode 58.

## 3.3.7 — Audit Findings Remediation

- Added a streaming `ProjectFileSystem.writeFileStream` boundary and Android filesystem implementations so ZIP import can avoid materializing large entries on real workspaces.
- Reworked generated PDF rendering around Android `StaticLayout`/bidi-aware text layout for mixed Persian/Arabic/Latin text; the contract remains plain-text PDF rather than a rich Markdown renderer.
- Added centralized build-script risk scanning for imported projects and surfaced the warning in the build integration boundary.
- Added runtime capability matrix presentation to Agent Workspace so platform capability and runtime capability are visible separately.
- Corrected release documentation: source-counted tests are not reported as executed tests; historical APK size is labeled as historical/unverified; cleartext wording reflects application-level endpoint validation rather than a global platform restriction.
- Updated security documentation with the accepted LAN/ZIP/build-script risks and verification prerequisites.
- Version 3.3.7 / versionCode 57.

## 3.3.1 — Artifact / Packaging hardening
- Hardened the generic Artifact/Packaging subsystem for ZIP, PDF, TXT, Markdown and JSON artifacts.
- Fixed source-archive hygiene so legitimate source packages/directories named `build` under `src/` are not discarded.
- Added duplicate archive-path rejection and project-id/path containment checks.
- Added Agent Workspace artifact inventory and user-driven sharing through FileProvider.
- Restored the app-owned Android build/toolchain source boundary required by the existing navigation and DI graph.

## v3.3.2 — External AI-layer audit remediation

- Restored the missing Android build/toolchain/build UI source boundary in the packaged source tree.
- Hardened provider runtime stringification, attachment routing, Gemini URL/safety handling, local auth headers, SSE empty events and fallback model errors.
- Added regression coverage for the externally reported provider-layer risks.
- Added a validated app-owned Android build toolchain boundary and cancellable Gradle runner.

## 3.0.0 — DSHA capability expansion

- Plugin marketplace metadata catalog and HTTPS Git URL installer boundary
- Plugin dependency graph, cycle detection and dependency-aware enable/disable
- Executable declarative Skill plan runtime with capability enforcement
- Foreground Agent Service with durable task drain and watchdog
- Authenticated internal LAN web UI surface and configurable port settings
- Device capability detection matrix
- Advanced encrypted backup migration pipeline
- Unified command approval facade over durable grants

## 2.9.1 — Deep Audit P0/P1/P2 Hardening

- Restored packaged Android build/toolchain/build UI sources.
- Fixed durable retry requeue/claim for `RETRYING` tasks.
- Hardened build workspace cleanup and verification boundary.
- Added migration/schema verification notes and security hardening.

# Changelog

## 2.9.0 — Deep Audit P0/P1/P2 Hardening

- Restored Android build/toolchain/build UI sources lost from the packaged baseline.
- Made Android build tasks restart-safe and retryable.
- Removed the unused duplicate in-memory approval authority from the production graph.
- Hardened durable Agent approval capability mapping.
- Added staged project restore with rollback.
- Expanded encrypted recovery snapshots with bounded task/approval metadata (no payloads/checkpoints).
- Moved automatic backup scheduling to process-independent AlarmManager.
- Hardened LAN authentication with constant-time token comparison and connection bounds.
- Added migration 9→10 regression coverage and updated migration chain assertions.
- Hardened ZIP directory-entry validation.

## 2.8.0 — 18-item capability completion

- Added pre-restore inspection with explicit restore commit step.
- Added automatic backup freshness policy plus bounded process-local interval and newest-three rotation.
- Added Installation / Task Manager UI over durable Room tasks.
- Added Dangerous Action Approval UI backed by durable project/session-scoped grants.
- Added process-bound authenticated LAN status endpoint and Remote/LAN status page.
- Added in-app Floating Agent Status UI without background overlay permissions.
- Added Repair Center diagnose → durable one-tap fix flow.
- Added Runtime/Toolchain diagnostic and repair routing.
- Hardened durable retry scheduling with `nextRunAt` and non-blocking backoff.
- Added Room migration 9→10 for retry scheduling.
- Updated PFP evidence and audit documentation.

## Verification
- PFP production-compliance static scan: PASS.
- Source artifact hygiene: no APK/AAB/keystore added.
- BUILD_VERIFIED/RUNTIME_VERIFIED remain unclaimed until a real Gradle distribution and Android device environment are available.

## 3.2.4 — Final UI/UX Deep Audit remediation
- Reworked Chat thinking control into compact horizontally-scrollable chips to reduce composer-area dominance on phones.
- Removed the misleading static "AI Agent" label from the Chat composer; the label now reads "Chat mode" because the composer itself does not toggle Agent execution.
- Reduced chat transcript top padding to reclaim vertical space on small screens.
- Restored the missing `ToolchainManager`, `AndroidBuildEngine`, and `BuildScreen` source boundary referenced by the navigation/DI graph.
- Build UI exposes only verified APK artifacts persisted outside disposable build workspaces.
- Final audit records compile verification as pending because the environment cannot download Gradle 8.10.2.
