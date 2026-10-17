# SECURITY.md

Anvex executes AI-generated project actions on a personal Android device. The
security model treats model output, provider responses, imported projects, and
project build scripts as untrusted input.

## 1. API key protection

- AES-256-GCM with an AndroidKeyStore key; ciphertext only at rest.
- Plaintext provider keys exist only in memory while constructing an in-flight request.
- Keys are masked in UI, excluded from exports/logs, and never persisted as plaintext. Runtime config stringification exposes only a masked key and header names, never decrypted header values.
- Legacy plaintext provider headers are migrated to encrypted storage when possible.

## 2. Execution boundaries

Anvex **does execute processes in explicit runtime workflows**. This is intentional
and must not be confused with the preview runtime:

- `AndroidShellRuntime` executes `/system/bin/sh -c` for the Terminal/Agent runtime.
- `AndroidTerminalSession` provides the interactive user Terminal.
- `NodeRuntime` can execute only a Node binary from supported system/toolchain locations;
  a project-local `node` executable is never trusted as a search candidate.
- `AndroidBuildEngine` executes the trusted app-managed Gradle binary with a bounded,
  cancellable process lifecycle. It deliberately does not execute a project `gradlew` wrapper.
- Android project build scripts are still executable code by the Gradle build system.
  Build is therefore a user-controlled privileged workflow, not a sandbox. Imported/untrusted
  projects should only be built when the user accepts that risk.

AI-originated shell/start actions pass the exact-action durable approval boundary. Large
AI file mutations also use the same approval decision; the dispatcher cannot silently
waive its size safety rule.

## 3. Filesystem safety

- Project paths pass `PathSanitizer` and canonical containment checks.
- ZIP import rejects traversal and applies entry/count/size limits.
- Projects are app-private; exports use the Storage Access Framework.

## 4. Network security

- Preview HTTP is loopback-only.
- The optional LAN status endpoint is loopback by default. Explicit LAN exposure binds
  `0.0.0.0`, requires a random bearer token, is read-only, and exposes only status/tasks/logs.
  It is HTTP, not TLS; use it only on a trusted network. The token can be explicitly copied
  by the user when needed and is otherwise kept hidden.
- v4.0 is cloud-provider-only: the platform network security config fails closed
  (HTTPS everywhere) with a loopback-only cleartext exception for the in-app preview
  and harness servers and `adb reverse`-style local development. Arbitrary remote
  cleartext is never permitted.

## 5. WebView hardening

- JavaScript is enabled only for the preview; file/content access is disabled.
- The `__anvex` bridge exposes only the bounded console `post(String)` method and is kept
  for release builds because WebView discovers it reflectively.
- Main-frame navigation remains pinned to loopback; external links open in the system browser.
- Preview runtime assets are served only from the exact bundled asset allowlist.
- Compose disposes preview WebViews with `stopLoading`, bridge removal, and `destroy()`.

## 6. Agent/Android lifecycle

The Agent uses an explicit Android foreground service (`START_STICKY`) when the user starts
it. The previous repeating AlarmManager watchdog was removed because Android 12+ restricts
background foreground-service starts from ordinary alarms. Boot only restores backup scheduling;
it does not silently start an Agent foreground service.

## 7. Limitations

- WebView is not a security boundary against a compromised OS.
- Gradle builds execute project build logic and are not a full sandbox.
- Custom/provider HTTP security depends on endpoint trust; HTTPS is preferred.


## Audit remediation notes — v3.3.7

- Generated TXT/MD/JSON/PDF artifacts use the same centralized generated-content secret policy as source packaging.
- Artifact ZIP output is streamed and atomically published; ZIP import now has a streaming filesystem boundary for real workspaces, with the existing entry/total caps.
- Local HTTP provider compatibility remains intentionally enabled at the Android network-security layer. The security boundary is application-level URL validation in `ProviderRepository`; this is not described as a platform-wide localhost-only cleartext rule.
- Imported Gradle projects are untrusted input. `BuildScriptRiskScanner` flags common command/network execution patterns. A warning must be shown before executing an imported build; the scan is advisory and is not a sandbox.
- PDF output is a plain-text document renderer using Android `StaticLayout` with first-strong bidi handling. It is not a rich Markdown renderer and does not claim font embedding.
- Runtime capability display separates device/platform capabilities from the actual project runtime matrix.

## 8. Harness bridge (v4.0)

- The harness web app is served by a loopback-only server (Host-header validated,
  GET/HEAD only, strict asset whitelist) and runs in the same hardened WebView
  configuration as previews (file/content access disabled; non-loopback main-frame
  navigations handed to the external browser).
- Every JS bridge call is a typed method returning `{"ok":…}` JSON; paths pass
  `PathSanitizer`; bridge writes are size-capped and journaled (revertible); shell
  stays behind the exact-action durable approval boundary; ZIP payloads are
  validated (PK header + size budget) before touching MediaStore.
- MCP server URLs must be HTTPS (loopback HTTP allowed for local testing); MCP
  bearer keys are stored Android-Keystore-encrypted.
- Skill installs from GitHub tarballs are bounded, path-checked, manifest-validated,
  and never register executable entrypoints.
