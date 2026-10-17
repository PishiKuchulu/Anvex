# Anvex v3.1.1 — External Security Audit Fix Report

## Scope
A targeted independent review identified shell newline injection, preview script-string escaping, Keystore/OS-backup mismatch, and silent credential-loss handling. The source tree was patched and re-audited.

## Findings and fixes

### 1. Shell command newline injection — FIXED
- `ActionSafetyPolicy.assess()` now rejects CR, LF, and backslash shell escaping before pattern classification.
- `AndroidShellRuntime.isAiSafeCommand()` applies the same deny boundary.
- Regression tests cover `ls\nrm -rf ...` and CRLF variants.

### 2. Preview `entryPath` script injection — FIXED
- `PreviewHarness` now emits an HTML-script-safe JavaScript string literal.
- Backslash, quotes, line separators, and `<`, `>`, `&` are escaped; escaping `<` prevents `</script>` termination.
- Regression test covers a hostile entry path containing a nested script tag.

### 3. Keystore vs Android OS backup — FIXED
- `android:allowBackup` changed to `false`.
- This prevents system backup from restoring encrypted Room ciphertext that cannot be decrypted after uninstall/device migration because the installation-bound Keystore key is unavailable.

### 4. Silent credential decryption failure — FIXED
- `ProviderRepository.runtimeConfig()` no longer converts a Keystore decryption failure into an empty API key.
- Provider UI exposes a clear re-entry warning when stored ciphertext cannot be decrypted.
- Portable backup export now fails explicitly if a stored ciphertext cannot be decrypted instead of exporting a silently blank credential.

### 5. StaticPreviewServer Host comment — CORRECTED
- Documentation now explicitly states that Host validation is destination hardening, not an Origin/CSRF boundary.

### 6. SAF performance note
- Retained as a performance optimization opportunity, not a correctness/security blocker.

## Verification
- Source file count: 208 total / 130 Kotlin.
- Security regression tests added.
- PFP static scan must be run in a build-capable environment.
- BUILD_VERIFIED and RUNTIME_VERIFIED are intentionally not claimed without a real Gradle build and device run.
