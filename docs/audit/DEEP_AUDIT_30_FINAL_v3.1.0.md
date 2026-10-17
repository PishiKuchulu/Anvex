# Anvex v3.1.0 — 30-Capability Completion Audit

## Scope
Full static deep audit and hardening of capabilities 1–30, with emphasis on the DSHA-inspired 19–30 expansion.

## Capability status
1. Health Center / Self-Diagnostic — implemented and wired.
2. Activity Log — durable Room-backed log with retention.
3. Pre-Restore Inspection — decrypt/validate/review before commit.
4. Backup Rotation — newest automatic snapshots retained.
5. Automatic Backup — process-independent AlarmManager schedule + boot recovery.
6. Corrupt Session Quarantine — deterministic quarantine path.
7. Resume long operations — durable task/checkpoint state.
8. Installation/Task Manager — durable task UI plus toolchain/device status.
9. Dangerous Action Approval UI — unified durable approval facade with exact-action fingerprint support.
10. Device/Environment Status — runtime capability matrix.
11. Plugin/Skill Manager — persistent manager.
12. Plugin Import/Export — bounded metadata exchange.
13. Built-in Plugin Protection — built-ins cannot be removed/disabled.
14. LAN Authentication Architecture — random in-memory bearer token + constant-time comparison.
15. Remote/LAN Status Page — authenticated read-only web status.
16. Floating Agent Status UI — in-app Agent Center; no overlay permission.
17. Repair Center / One-Tap Fix — bounded repairs through durable tasks.
18. Runtime/Toolchain Diagnostics + Fix boundary — deterministic diagnostics and bounded repair/task path.
19. Plugin Marketplace — trusted-origin catalog + package installer.
20. Git URL Plugin Install — HTTPS GitHub/GitLab archive download + checksum + safe extraction.
21. Plugin Dependency Management — graph, cycle detection, version constraints, central enable/disable enforcement.
22. Real Skill System — executable declarative plan compiler with capability gates; no arbitrary code.
23. Foreground Agent Service — Android foreground lifecycle and durable task integration.
24. Watchdog — heartbeat, manual-stop gate and reboot recovery.
25. Full LAN Agent UI — authenticated read-only status/tasks/logs endpoints; no mutation surface by design.
26. Internal Web Server — bounded authenticated HTTP server.
27. Configurable Web Port — persisted 1024–65535 port with automatic lifecycle activation.
28. Advanced Backup Migration — integrated versioned automatic-snapshot migration.
29. Device Capability Detection — capability vs configured/installed state separated.
30. Better Command Approval — risk + capability + project/session + exact action fingerprint + TTL.

## Security boundaries
- Plugin packages are stored app-private and are never loaded as executable code.
- Skill execution is declarative and emits tool intents; arbitrary code execution is intentionally prohibited.
- LAN exposes read-only data only.
- Approval records never persist command text or credentials.
- Source artifacts contain no APK/AAB/keystore.

## Verification
- PFP static compliance: PASS.
- Custom 30-capability static audit: PASS (22/22 checks).
- Gradle BUILD_VERIFIED: pending because this audit environment cannot download Gradle 8.10.2 from services.gradle.org.
- Room schema 12 snapshot: pending a successful KSP/Room build; it is intentionally not fabricated.
- RUNTIME_VERIFIED: pending real Android device execution.

## Final gate
Static source implementation is complete for the defined 30-capability scope. Production readiness remains gated by real build, migration generation, APK installation, and device runtime verification.
