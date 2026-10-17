# External AI Audit Review & Remediation — Anvex v3.2.7

## Scope
Reviewed the external 20-item audit against the actual Anvex v3.2.6 source tree before applying changes. The review distinguished confirmed defects from conditional risks and incorrect claims.

## Confirmed/remediated
- `ProviderRuntimeConfig` now has secret-safe `toString()`; decrypted API keys and extra-header values are not rendered.
- `ChatAttachment` and `ChatRequest` use bounded metadata-only `toString()` implementations.
- OpenAI-compatible requests omit `Authorization` when the API key is blank.
- Attachment routing in OpenAI, Anthropic and Gemini uses the stable last-user index rather than reference identity.
- Gemini base URL handling avoids duplicating `/v1beta`.
- Gemini prompt/response safety blocks are surfaced as protocol failures.
- Gemini thinking configuration is capability-gated for Gemini 2.5/3 naming families; older models do not receive the thinking config field.
- SSE preserves valid empty `data:` events.
- Non-JSON HTTP error bodies are not copied wholesale into user-facing error messages.
- Fallback model discovery surfaces the last provider exception when no provider succeeds.
- The packaged source boundary now contains `BuildScreen`, `ToolchainManager`, and `AndroidBuildEngine` referenced by the app/DI graph.
- Android build execution is restricted to a validated app-owned toolchain and does not execute a project-supplied `gradlew`.
- Gradle process output is consumed independently, with a real timeout and coroutine-cancellation path that terminates the process.
- APK success is evidence-gated: a fresh non-empty APK must be found in the expected variant output directory.
- Version metadata is aligned to `3.2.7` / `versionCode 51`.
- README/security/build documentation was synchronized with the actual process-runtime and Room v12 architecture.

## Already correct in the supplied v3.2.6 source
- Agent file mutations were already routed through `ActionSafetyPolicy`; there was no unconditional `approvalGranted = true` in the supplied bridge.
- Chat provider-session recreation was already implemented with provider observation.
- AlarmManager-to-FGS watchdog behavior had already been removed.
- Project-local Node executables were already excluded.
- `supportsBaseUrlOverride` was already consumed by provider UI.
- LAN exposure was already loopback by default and required explicit opt-in for `0.0.0.0` binding.
- Room migration infrastructure already contained an explicit chain through schema version 12; absence of a v1 JSON snapshot was not treated as a migration failure by itself.

## Not treated as confirmed bugs
- Reference-identity attachment handling was not a current defect in the existing list traversal, but it was hardened to index-based logic anyway.
- A guaranteed Gemini 400 for `gemini-1.5-flash` from `thinkingBudget` was not established from source/tests alone; capability gating was added as defensive compatibility hardening.
- A 10-minute OkHttp call timeout was not treated as a universal bug; it remains a bounded safety limit.
- The root directory name inside a ZIP was not a Gradle correctness issue.
- The existing FGS/Alarm watchdog claim was stale relative to the supplied source.

## Verification
- PFP static compliance: **PASS**
- Deep static audit: **22/22 PASS**
- Source boundary checks: **PASS**
- No APK/AAB/keystore artifacts in source tree: **PASS**
- Gradle test/compile: **NOT VERIFIED** because the audit environment cannot download Gradle 8.10.2 from `services.gradle.org` (`UnknownHostException`).
- Device runtime: **NOT VERIFIED**.

## Release truth
This source package is not labelled production-ready until Gradle compilation/tests, APK installation, and physical-device smoke testing are completed in a network-capable Android environment.
