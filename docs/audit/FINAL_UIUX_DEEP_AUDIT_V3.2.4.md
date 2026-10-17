# Anvex 3.2.4 — Final UI/UX Deep Audit & Remediation

Date: 2026-09-15
Baseline: Anvex 3.2.3 FINAL UIUX PFP

## Executive result

The final audit found that the supplied 3.2.3 package was **not internally complete** despite earlier audit notes: `AnvexApp.kt` referenced `BuildScreen`, `AppGraph` referenced `ToolchainManager` and `AndroidBuildEngine`, but those source files were absent from the packaged source tree. This is a release-blocking source integrity issue.

The 3.2.4 remediation restores those boundaries and also addresses the phone screenshot's Chat UX problems.

## Screenshot-driven findings

### P1 — Chat vertical density
The previous Thinking selector rendered five equal-width segments across the entire row. On a ~690px-wide phone this consumed excessive horizontal/vertical visual weight and pushed the composer toward the navigation bar.

**Fix:** replaced it with horizontally scrollable Material 3 FilterChips. The selected state remains explicit while preserving all five levels.

### P1 — Misleading Agent affordance
The composer displayed `AI Agent` as plain text without an action or mode state. This implied a capability switch that did not exist.

**Fix:** renamed it to `Chat mode`, making the current surface descriptive rather than a false affordance.

### P2 — Excess transcript top spacing
The transcript used 12dp top padding in addition to the scaffold/app-bar spacing, producing unnecessary empty space above the first message on compact screens.

**Fix:** reduced transcript vertical padding to 4dp while retaining 16dp horizontal page rhythm.

## Source-integrity findings

### P0 — Missing build UI/core source boundary
`AnvexApp.kt` imported `com.anvex.app.ui.screens.build.BuildScreen` and registered the `build/{projectId}` route. `AppGraph` constructed `ToolchainManager` and `AndroidBuildEngine`. Those implementations were absent from the 3.2.3 package.

**Fix:** restored:
- `core/android/.../build/ToolchainManager.kt`
- `core/android/.../build/AndroidBuildEngine.kt`
- `app/.../screens/build/BuildScreen.kt`

The build path remains evidence-gated: no APK is reported as successful unless a real Gradle process exits successfully and a non-empty APK is found.

## Build artifact UX

Verified APKs are persisted through `ProjectRepository.persistBuildArtifact()` outside the disposable Gradle workspace and surfaced in Build → APK artifacts. Sharing uses Android `FileProvider` with `exported=false` and URI grants.

## Navigation / discoverability

Audited primary paths:
- Home → Create / Import → Project
- Project → Chat / Editor / Preview
- Project overflow → Terminal / Build / History / GitHub
- Home → Agent
- Home → Settings → Providers / Developer / Usage / Backup

The Agent surface is a first-class top-level destination rather than a hidden feature.

## Accessibility / mobile UX

- Material 3 controls are used for interactive surfaces.
- Primary controls retain >=44–48dp practical touch areas.
- Chat thinking options no longer require squeezing five labels into one fixed-width segmented control.
- RTL-aware message/composer text direction is retained.
- Semantic Material color tokens are used instead of arbitrary UI colors in the remediated Chat surface.
- No horizontal page scrolling is introduced; only the bounded Thinking/quick-prompt rows scroll horizontally.

## Security/PFP UI checks

- No credentials are surfaced in the Chat/Build UI.
- Agent approvals remain exact-action/durable rather than broad capability-only grants.
- Build workspace is disposable; verified artifacts are copied to app-owned persistent storage.
- FileProvider is non-exported and grants only URI-based read access for sharing.
- Source ZIP hygiene remains free of APK/AAB/keystore artifacts.

## Verification status

| Gate | Result |
|---|---|
| Static source presence / route consistency | PASS |
| Build boundary restored | PASS (source-level) |
| Artifact persistence boundary | PASS (source-level) |
| Chat mobile UX remediation | PASS (source-level) |
| PFP static baseline | PASS — 22/22 baseline checks |
| Gradle compile | PENDING — Gradle 8.10.2 distribution unavailable in audit environment |
| Device smoke test | PENDING |
| Production Ready | **NOT CLAIMED** |

## Release conclusion

3.2.4 is the corrected source baseline for Android Studio/device verification. A production-ready claim must wait for a real Gradle compile, APK installation, and device smoke test.
