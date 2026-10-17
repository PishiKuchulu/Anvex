# Anvex UI/UX Redesign — v3.2.2

## Scope
Mobile-first refinement of the AI coding IDE workflow, focused on Agent Workspace and Terminal.

## Applied
- Agent Workspace now communicates the durable lifecycle as Goal → Runtime → Task Queue.
- Active work and exact-action approval are visible without exposing credentials or approval internals.
- Task actions use full-width mobile controls where appropriate and preserve 48dp-class interaction targets.
- Terminal uses a dedicated console surface, connection status, copy-output action, and focused command composer.
- Existing app-private workspace restriction remains intact.

## Verification
- `tools/deep_audit_30.py`: expected 22/22 static checks.
- Full Gradle/device verification remains separate and must be run in an Android-capable environment with the required Gradle distribution available.
