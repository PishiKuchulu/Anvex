# Anvex 3.1.4 — Runtime, Build & Workspace Integration

## Scope
This increment continues the post-external-audit remediation after v3.1.3. It focuses on previously engine-only capabilities that lacked usable UI or complete product flow.

## Implemented
- Real Terminal screen wired to `AndroidRuntimeGateway.terminal()`.
- Home project action menu now exposes Editor, Terminal, Build APK and GitHub Sync.
- Home exposes ZIP Import through `OpenDocument`.
- Toolchain ZIP import added with bounded streaming extraction, traversal protection, Android manifest/ABI validation and atomic staging/activation.
- Build screen now supports Debug and Release task creation, durable cancellation, APK artifact discovery and user-selected APK export.
- GitHub Pull connected to the existing REST repository layer with explicit confirmation before overwriting the workspace.
- Editor create/delete/rename/duplicate file operations now enter the persistent mutation journal.
- History revert understands rename mutations and records the resulting revert mutation.
- Gradle process cancellation now destroys the underlying process when the worker coroutine is interrupted.

## Security boundaries
- Raw Terminal remains unavailable for SAF/external document workspaces.
- Toolchain import never activates an unvalidated archive.
- Toolchain archive limits are enforced during streaming extraction.
- GitHub Pull requires an explicitly confirmed overwrite.
- No credentials are written to task payloads/checkpoints.

## Verification
- `tools/deep_audit_30.py`: PASS (22/22).
- Artifact hygiene checked: no APK/AAB/keystore files in source tree.
- Full Gradle compile/test could not be executed because the environment cannot resolve `services.gradle.org` to obtain Gradle 8.10.2.

## Remaining work
- Complete UI/UX redesign across all screens.
- Add richer build logs and task/project correlation.
- Add local Git runtime and richer GitHub pull/push conflict handling.
- Add full Skill execution UI and Marketplace browse/update UX.
