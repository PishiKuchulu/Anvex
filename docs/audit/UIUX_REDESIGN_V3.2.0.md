# UI/UX Redesign Audit — Anvex v3.2.0

## Scope

First UI/UX implementation pass over the v3.1.4 source baseline, using the project design system and the approved UI/UX Pro Max methodology for Jetpack Compose.

## Findings

### P0 — capability discoverability

- Agent Center existed as a route but was not a root-level destination.
- Advanced agent functionality was therefore effectively hidden from the primary workflow.

**Fix:** Agent is now a first-class root destination alongside Home and Settings.

### P1 — visual hierarchy

- Home relied on a generic project list with weak grouping.
- Settings used long ungrouped `ListItem` runs.
- Primary AI/agent capabilities did not have enough visual prominence.
- The interface had inconsistent card, radius and surface treatment.

**Fix:** Introduced shared `AnvexCard`, `AnvexIconTile`, `AnvexSectionHeader` and `AnvexStatusBadge` primitives and applied them to Home and Settings.

### P1 — design-system enforcement

- `AnvexTypography` existed but was not passed into `MaterialTheme`.
- Shapes were defined separately but were not globally applied.

**Fix:** `AnvexTheme` now applies typography and shared shapes globally.

### P1 — developer workflow hierarchy

- Build, Terminal and GitHub were discoverable mainly through project overflow menus.
- The first viewport did not communicate the main developer workflow.

**Fix:** Home includes explicit quick actions and project overflow remains the secondary action surface.

### P1 — project workspace navigation

- Secondary project actions were not grouped coherently.

**Fix:** Project overflow now groups Home, Terminal, Build APK, History and GitHub sync while the bottom navigation stays focused on Chat, Editor and Preview.

### P1 — source consistency / build-surface integrity

The supplied v3.1.4 archive referenced `BuildScreen` and `core.android.build.*` classes but did not contain those source files.

**Fix:** Restored the build UI surface and a conservative app-owned toolchain/build boundary so the source tree is internally consistent. The build engine remains evidence-gated and refuses to claim an APK until a real toolchain produces one.

## Design decisions

- Obsidian IDE aesthetic: neutral dark surfaces + restrained blue.
- Material 3 native controls.
- 4/8dp spacing rhythm.
- 16dp page gutters.
- 48dp primary touch targets.
- Semantic colors for functional state.
- No decorative gradients or emoji icons.
- Light/dark pairing remains mandatory.
- Dynamic Material You remains supported.

## Verification status

`STATIC_UIUX_REVIEWED`

Not claimed:

- `BUILD_VERIFIED`
- `RUNTIME_VERIFIED`
- `PRODUCTION READY`

Reason: the available environment cannot download the Gradle 8.10.2 distribution, so Gradle compilation could not be executed here.

## Next UI/UX pass

- Chat composer and message hierarchy
- Editor toolbar/file explorer
- Preview/console split
- Developer Center information architecture
- Agent Center approval/task cards
- Build/Terminal visual polish
- Tablet/landscape adaptive layouts
- Accessibility/dynamic-font verification on device
