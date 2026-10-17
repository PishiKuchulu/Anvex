# Anvex v3.2.8 — Targeted Deep Verification

## Scope
Verified against the v3.2.7 source archive: AI HTTP timeout, Gemini model capabilities, Room migration/schema evidence, app-level cleartext policy, and Gradle/version metadata.

## Findings

### 1. HTTP call timeout — FIXED
The shared OkHttp client used a fixed 10-minute total call timeout. It is now 30 minutes to accommodate long reasoning and streaming sessions while retaining an absolute upper bound. Existing cancellation via coroutine/call cancellation remains the primary interactive stop path.

### 2. Gemini capability detection — FIXED/HARDENED
Replaced broad `contains()` checks with a conservative capability helper. Known `gemini-2.5-*` variants receive thinkingBudget; known `gemini-3` variants receive thinkingLevel. Unknown/future families receive no thinkingConfig until explicitly classified, avoiding speculative wire parameters.

### 3. Room schema v1 — VERIFIED, NO SOURCE FIX REQUIRED
The database is version 12 and ships an explicit 1→2→...→12 migration chain. The migration tests construct the historical v1 SQLite schema directly and execute the production v1→2 statements against a real SQLite engine. Room does not require a checked-in `1.json` snapshot to execute a manual migration. The existing checked-in `2.json` is stale evidence, however, because it predates the current entity set. It must not be presented as the current schema. A current schema export should be regenerated in a build-capable environment rather than hand-authored.

### 4. App-level cleartext — VERIFIED WITH HARDENING NOTE
The app Manifest explicitly references `@xml/network_security_config`; therefore the earlier claim that the app manifest lacked the configuration is false. The current policy enables cleartext globally because user-configured local Ollama/LM Studio HTTP endpoints may use arbitrary private IPs. Application-level `ProviderRepository` validation restricts HTTP provider endpoints to loopback/private/local ranges. This is a defense-in-depth limitation, not a missing-manifest bug. A future redesign can use HTTPS or a narrowly scoped transport proxy, but arbitrary private-IP HTTP cannot be expressed cleanly as a static Android Network Security Config allowlist.

### 5. Gradle/version metadata — VERIFIED
App versionName is 3.2.7 and versionCode is 51 in both Gradle and README. AGP is 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, and wrapper Gradle is 8.10.2. No mismatch was found in the current archive.

## Remaining verification limits
Full Gradle compilation and device runtime were not claimed here because the audit environment does not contain the required Gradle distribution. No production-ready claim is made until compile/install/runtime evidence exists.
