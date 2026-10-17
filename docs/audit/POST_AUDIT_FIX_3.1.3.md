# Anvex 3.1.3 — Post-Audit Integration Fix

## Scope
This release fixes the highest-impact gaps found in the v3.1.2 external audit.

## Implemented
- Durable Agent tasks now use `AgentActionExecutor` as an engine-level execution boundary.
- Android shell/start actions are routed through `AndroidRuntimeGateway` with `ShellOrigin.AI`.
- Agent approvals use `CommandApprovalService` and exact action fingerprints.
- Waiting approvals persist only non-secret metadata (step index, fingerprint, capability, session and project).
- `approveWaitingTask()` grants the recorded exact fingerprint and `resumeAfterApproval()` resumes the durable task.
- Agent dispatcher mutations are persisted to `MutationRepository` after completed steps.
- Agent Center now exposes goal input, project selection, task monitoring, approval and resume.
- Agent planner receives an explicit Agent-runtime system prompt distinct from the preview/chat limitations.
- Provider fallback ordering starts with the configured default provider.
- Backup restore now includes LAN enabled state and web port.
- New Project dialog is scrollable on small displays.

## Important limitation
The Android runtime is still intentionally fail-closed for SAF/external workspaces because they do not expose a safe raw filesystem root. Node availability is not assumed.

## Verification
Static source inspection was performed after the changes. Full Gradle test/build verification was attempted but the environment could not download Gradle 8.10.2 (`services.gradle.org` unavailable), so this release is **not** marked BUILD_VERIFIED or RUNTIME_VERIFIED here.
