# Artifact / Packaging Subsystem — v3.3.1 Audit

## Implemented
- Generic Agent artifact action: ZIP, PDF, TXT, Markdown and JSON.
- ZIP packaging is deterministic: normalized paths, sorted entries, zero timestamps.
- Duplicate archive paths and traversal/absolute paths are rejected.
- Packaging limits: 20,000 files, 200 MB total, 20 MB per entry/document.
- Secret-name and likely-secret-content hygiene checks are applied to source ZIPs.
- Generated artifacts are isolated under app-private `filesDir/build-artifacts/<projectId>/`.
- Project IDs and artifact file paths are canonicalized/contained.
- Agent Workspace lists generated artifacts and exposes explicit user-driven FileProvider sharing.
- Sharing is separate from artifact creation; the model cannot silently export files.

## Source-archive hygiene correction
The previous hygiene rule excluded every path component named `build`. That could remove legitimate Kotlin/Java source packages such as `src/main/kotlin/.../build/...` from a generated source ZIP. v3.3.1 excludes root/module build outputs while preserving source paths below `src/`.

## Build boundary integrity
The source package now contains the app-owned build/toolchain boundary required by `AppGraph`, `AgentIdeBridge`, and `AnvexApp`: `ToolchainManager`, `AndroidBuildEngine`, and `BuildScreen`.

## Verification status
- Static deep audit: PASS (22/22).
- Required source-boundary presence: PASS.
- Full Gradle compile: NOT VERIFIED in this environment because Gradle 8.10.2 is not locally available and the wrapper cannot reach services.gradle.org.
- Device/runtime smoke test: NOT VERIFIED.
- No BUILD_VERIFIED or RUNTIME_VERIFIED claim is made from static inspection.
