# Anvex v3.1.2 — Final External-Audit Hardening

## Scope
This release incorporates the external security findings from the v3.1.1 review and performs a second static/deep audit.

## Fixed
- AI interactive terminal rejects CR/LF and shell escaping before writing to `/system/bin/sh`.
- Provider-backed durable Agent planning is wired through `AppGraph -> AgentTaskBridge -> ProviderAgentPlanner`.
- Durable Agent shell/start actions use `AndroidRuntimeGateway`; SAF workspaces fail closed instead of exposing a raw filesystem root.
- Real app-private project roots are explicitly exposed only through `RealProjectFileSystem.rootDirectory()`.
- LAN `Status` no longer carries the full bearer token; full token access remains an explicit in-memory operation for the local settings UI only.
- External-audit preview escaping and `allowBackup=false` hardening are retained from v3.1.1.
- Version metadata is consistent at 3.1.2 / versionCode 42.

## Static verification
- PFP production compliance: PASS.
- 30-capability static audit: PASS 22/22.
- Source archive contains no APK/AAB/keystore.
- Required Build sources are present.

## Build limitation
Gradle wrapper execution was attempted with `--offline`, but Gradle 8.10.2 is not cached in this audit container and `services.gradle.org` is unreachable (`UnknownHostException`). Therefore BUILD_VERIFIED and RUNTIME_VERIFIED are intentionally not claimed.

## Remaining truth boundary
Static verification does not prove Android runtime behavior. A networked Android build environment and a real device/emulator run are required before PFP can move from STATIC_VERIFIED to BUILD_VERIFIED/RUNTIME_VERIFIED.
