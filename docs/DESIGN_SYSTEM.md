# Anvex Design System v2 — Obsidian IDE

## Direction

Anvex is an **Android-native AI development workspace**, not a web page wrapped in an Android shell.

The v2 direction is a restrained **Obsidian IDE** aesthetic:

- Material 3 foundations and native Android controls.
- Deep neutral surfaces with a restrained blue primary accent.
- Clear surface/border hierarchy instead of decorative gradients.
- One primary action per surface.
- 4/8dp spacing rhythm with 16dp page gutters.
- Minimum 48dp touch targets for primary controls.
- Monospace only for code, paths and terminal output.
- Material icons only; no emoji as functional UI.
- Light and dark themes are designed as a pair.
- Dynamic Material You remains supported on Android 12+.

## Information architecture

Root navigation is intentionally limited to three destinations:

1. **Home** — projects, import/create, quick actions.
2. **Agent** — durable agent tasks, approvals and runtime capabilities.
3. **Settings** — providers, appearance, editor, storage and developer controls.

A project workspace owns three primary modes:

1. **Chat** — AI conversation and change review.
2. **Editor** — code/files.
3. **Preview** — rendered project.

Build, Terminal, History and GitHub remain secondary project actions available from the project overflow menu and contextual quick actions.

This keeps primary navigation within the platform-friendly bottom-navigation limit while exposing previously orphaned Agent functionality.

## Surface hierarchy

| Level | Use |
|---|---|
| Background | Page canvas |
| Surface | Cards, navigation and primary containers |
| Surface Variant | Segmented controls, secondary panels |
| Primary Container | AI/agent emphasis |
| Error/Success containers | State communication with icon + text |

Avoid excessive elevation. Borders and tonal separation carry most hierarchy.

## Components

Shared components live under `ui/components/AnvexUi.kt`:

- `AnvexCard`
- `AnvexIconTile`
- `AnvexSectionHeader`
- `AnvexStatusBadge`

These components use semantic Material color tokens rather than per-screen hex values.

## Home

The Home screen must answer three questions immediately:

- What can I work on?
- Can I start an AI agent task?
- How do I create/import a project?

The first viewport therefore contains:

1. Agent workspace status card.
2. Provider setup state when no provider exists.
3. Project list/empty state.
4. Quick actions for Build, Terminal and GitHub.

## Settings

Settings are grouped into scannable sections:

- AI
- Appearance
- Editor
- Storage & data
- Developer
- About

Advanced functionality is not hidden behind an inaccessible page. Agent Center is available from root navigation and Settings.

## Project workspace

The top app bar is intentionally quiet: title + contextual actions. The bottom navigation remains fixed to Chat/Editor/Preview.

Secondary actions are grouped under the overflow menu:

- Terminal
- Build APK
- History
- GitHub sync
- Home

## Chat

The composer is the dominant interaction surface.

- Multiline input.
- Attach file/image actions.
- Clear agent identity.
- One obvious send action.
- Quick prompts are subordinate chips.
- Thinking level is a compact segmented control.
- User and AI messages use different surface hierarchy.
- AI artifact changes expose direct diff/review affordances.

## Accessibility / UX quality bar

Following the UI/UX Pro Max methodology and Android-native guidance:

- Touch targets ≥48dp.
- Body text remains readable at normal and enlarged sizes.
- Primary and secondary text maintain accessible contrast.
- Semantic tokens are used instead of raw per-screen colors.
- Interactive controls have descriptive content descriptions.
- Error states contain icon + text + recovery action.
- Navigation is predictable and back behavior is explicit.
- Safe areas and bottom navigation insets are respected.
- Reduced-motion support must be preserved.
- Small-phone and landscape layouts must be checked before final release.

## Verification checklist

Before declaring the redesign complete:

- [ ] 375dp-class phone portrait
- [ ] Large phone portrait
- [ ] Landscape
- [ ] Light theme
- [ ] Dark theme
- [ ] Dynamic color on/off
- [ ] Large font / accessibility text
- [ ] Empty state
- [ ] Provider-not-configured state
- [ ] Agent running / approval-required state
- [ ] Long project names
- [ ] Long file paths
- [ ] RTL Persian + mixed English/code
- [ ] Keyboard open in Chat
- [ ] Bottom navigation does not cover content
- [ ] No horizontal scrolling
- [ ] Every major capability remains reachable from UI

## Provenance

Design methodology references the project-approved UI/UX Pro Max integration. The external skill is advisory only and cannot override project security, architecture, or PFP production-compliance gates.
