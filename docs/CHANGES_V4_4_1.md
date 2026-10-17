# CHANGES — Anvex 4.4.1 (OpenCode integration round)

Every changed/added file versus the v4.4.0 delivery, with the architectural reason.
All build/test/lint claims below were executed in THIS environment (commands and
observed results at the end of the file). Live upstream facts were verified with
safe, non-destructive probes on 2026-10-15 — see docs/OPENCODE_INTEGRATION_REPORT.md
for the full probe table (HTTP statuses, response bodies, endpoint layout).

## Files changed

### core/model — provider catalog
| File | Change | Reason |
|---|---|---|
| `core/model/src/main/kotlin/com/anvex/core/model/Provider.kt` | OPENCODE_FREE/OPENCODE_ZEN kdocs rewritten with the verified live facts; `defaultBaseUrl` moved from legacy `https://opencode.ai/zen/v1` to the documented `https://opencode.ai/inference/openai/v1`; OPENCODE_ZEN displayLabel → "OpenCode Console (API key)" | Default URL must match the endpoint family actually serving traffic today (chat = `/inference/openai/v1/chat/completions`, verified). Zen was renamed Console upstream; the enum NAME is unchanged so persisted Room rows (`kind` stored as `ProviderKind.name`) keep decoding without a migration. Legacy bases still work (models fallback path + both endpoints verified live). |

### core/ai — isolated OpenCode adapter
| File | Change | Reason |
|---|---|---|
| `core/ai/src/main/kotlin/com/anvex/core/ai/OpenCodeClient.kt` | **NEW** — the isolated adapter for both OpenCode tiers | Single place for OpenCode-specific behavior: public model discovery + `-free` filtering (free tier), family-root models-URL derivation, honest User-Agent interceptor, two-step free-tier validation (discovery verdict + real chat-gate verdict), Console key verification via the authenticated models fetch, verbatim upstream error reporting with exact HTTP status. Chat streaming delegates to the shared OpenAI transport — no duplicated SSE code. |
| `core/ai/src/main/kotlin/com/anvex/core/ai/AiTypes.kt` | Added `AiError.Kind.ACCESS_RESTRICTED` (+ userTitle "Access restricted by the provider") | The task requires distinguishing authentication failures (401, bad key) from access restrictions (403, upstream policy — the OpenCode free-tier gate). `Kind` is not serialized anywhere, so adding a value is safe; the only exhaustive `when` (`userTitle`) was updated. |
| `core/ai/src/main/kotlin/com/anvex/core/ai/Sse.kt` | `mapHttpError`: 403 → ACCESS_RESTRICTED (was lumped with 401 → AUTH) | Same distinction at the shared transport boundary; affects all providers uniformly (a 403 from any upstream is now correctly reported as a policy refusal rather than "invalid key"). No test relied on the old mapping. |
| `core/ai/src/main/kotlin/com/anvex/core/ai/OpenAiCompatibleClient.kt` | Removed the OPENCODE_FREE special cases (`-free` filter in `executeModelsCall`, the unconditional chat-probe branch in `validateConnection`); extracted the one-token chat probe as `internal probeChatEndpoint(http)` | Provider-specific logic must not live in the shared adapter (isolation requirement). The probe is now a reusable, tested seam that OpenCodeClient calls for the free-tier gate check. |
| `core/ai/src/main/kotlin/com/anvex/core/ai/ProviderClient.kt` | Factory routes OPENCODE_FREE/OPENCODE_ZEN → OpenCodeClient | Dispatch entry point for the isolated adapter; all other kinds unchanged. |

### app — UI, localization, version
| File | Change | Reason |
|---|---|---|
| `app/src/main/kotlin/com/anvex/app/ui/screens/providers/ProvidersScreen.kt` | Free-tier note rewritten with the VERIFIED 403 fact + "availability may change" + pointer to the Console provider; Zen key labels → "Console API key" | Phase 4 requirements: honest, actionable messaging; no unverified quota/availability claims; existing UI structure/style untouched. |
| `app/src/main/kotlin/com/anvex/app/i18n/Strings.kt` | FA translations for the new/changed strings (+ kept the old note's translation for history) | Localization convention: every new `tr()` key gets a Persian entry. |
| `app/src/main/kotlin/com/anvex/app/ui/screens/settings/SettingsScreen.kt` | About row "Anvex 4.4.0" → "Anvex 4.4.1" | Version display parity. |
| `app/build.gradle.kts` | versionCode 65 → 66, versionName "4.3.0" → "4.4.1" | The in-app stamp now matches the delivery name (v4.4.0 shipped with stamp 4.3.0 — disclosed then, fixed now). |

### docs & tooling
| File | Change | Reason |
|---|---|---|
| `docs/OPENCODE_INTEGRATION_REPORT.md` | **NEW** | The required endpoint-test report: research references, live probe table with exact HTTP statuses/bodies, the free-tier blocker, the permitted Console path, what is honestly NOT verified. |
| `docs/KNOWN_LIMITATIONS.md` | v4.4.1 OpenCode section; updated free-tier probe wording (today's probe = FreeTierError; earlier probe had observed RegionError — both 403, both surfaced verbatim) | Honest scope documentation required by the brief. |
| `CHANGELOG.md` | 4.4.1 entry | Delivery record. |
| `docs/CHANGES_V4_4_1.md` | **NEW** (this file) | Required file-change list with architectural reasons. |

### tests
| File | Change | Reason |
|---|---|---|
| `core/ai/src/test/kotlin/com/anvex/core/ai/OpenCodeClientTest.kt` | **NEW** — 27 tests | Full required matrix (see below). Mock bodies are REAL payloads captured from the live probes, so the wire-format tests encode today's verified upstream behavior. |

## Verification matrix (executed in this environment)

Toolchain: JDK 17.0.20 (Temurin), Android SDK platform 35 / build-tools, Gradle 8.10.2,
4 GB / 2-core box (`--max-workers=1` for lint; the daemon is OOM-sensitive above that).

| Check | Command | Result |
|---|---|---|
| OpenCode unit tests | `:core:ai:test --tests ...OpenCodeClientTest` | **27/27 PASS** |
| All core:ai tests | `:core:ai:test` | **79 PASS** (52 existing + 27 new), 0 failures |
| core:model + core:engine | `:core:model:test :core:engine:test` | **176 PASS**, 0 failures |
| core:android tests | `:core:android:test` (debug+release variants) | **60 PASS**, 0 failures |
| app unit tests | `:app:testDebugUnitTest` | **21 PASS**, 0 failures |
| Kotlin compile (app + android) | `:core:android:compileDebugKotlin :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| APK | `:app:assembleDebug` | BUILD SUCCESSFUL — 28,250,395 B, `versionCode=66 versionName=4.4.1-debug` |
| Lint | `:app:lint` | **0 errors**, 57 warnings + 8 info (identical warning count to the v4.4.0 baseline — no new issues) |

**Total: 336 unit tests, 0 failures, 0 errors, 0 skipped.**

### Mock-based vs. genuine upstream results — separated honestly

- **Mock-based (automated suite)**: everything in the matrix above. The OpenCode wire
  behavior is tested against MockWebServer with live-captured payloads.
- **Genuine upstream (manual probes, 2026-10-15)**: the 8-probe table in
  docs/OPENCODE_INTEGRATION_REPORT.md, re-runnable via `tools/opencode_probe.sh`
  (shipped as `tools/opencode_probe.sh` in the source tree). Real HTTP statuses observed: 200 (public
  discovery), 403 FreeTierError (free-tier chat gate), 401 AuthError/Invalid
  credential (missing/invalid key), 400 (unknown model), 404 (per-family models path).
- **NOT performed (environment-blocked, honestly)**: live chat completion with a
  genuine Console API key (no key exists here); on-device Android smoke test.

## Remaining upstream restrictions

1. **OpenCode Free chat is gated to the official OpenCode client** (HTTP 403
   FreeTierError for Anvex's honest identity). Anvex will not reproduce the official
   client fingerprint, so free-tier chat stays unavailable until OpenCode offers a
   permitted third-party path. Discovery + honest gate reporting are delivered.
2. The `-free` suffix filter is the endpoint's own signal; a few pricing-page-free
   models (e.g. `big-pickle`) lack the suffix and won't appear in the free-tier list
   (typing them manually is possible; the gate applies regardless).
3. Free-model availability/limits change upstream without notice — the UI says so and
   promises no quota.
