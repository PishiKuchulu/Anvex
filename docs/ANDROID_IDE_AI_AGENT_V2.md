# Anvex 2.0 — Android IDE + AI Agent Architecture

## Implemented in this source
- On-device Android build orchestration with an explicit Android-compatible JDK/Gradle/Android SDK toolchain contract and real process exit verification.
- Safe toolchain ZIP import with traversal protection.
- Project-wide search and replace.
- Persistent mutation history with revert UI.
- Starter templates: blank, landing page, todo, dashboard, React.
- Provider fallback before partial-token output; no duplicate partial answers.
- Configurable reasoning levels: Low, Normal, High, XHigh, Max.
- Quick prompts in Chat.
- Copy assistant response to clipboard.
- Token/usage dashboard.
- Password-portable encrypted provider/settings backup.
- GitHub REST sync without a git executable; tokens stored in Android Keystore.
- Onboarding gate and modern Material 3 surfaces.
- Existing-folder workspace model with safe detach semantics.

## Build contract
Anvex never claims Android compilation without executing a real Gradle process. The build engine requires a JDK, Android SDK and Gradle wrapper/home. A signed release APK still requires the user's signing key.

## Android IDE roadmap surfaces
The architecture leaves explicit seams for debugger/logcat, APK install, device bridge, Kotlin/Java language services, Compose previews, resource inspection, manifest/Gradle editors, refactoring, test runners, profiler, GitHub OAuth/device flow and richer tablet split panes. These require platform/toolchain integrations and are not represented as fake success states.


## 2.0.2 hardening pass
- Build process output is drained concurrently so a verbose Gradle build cannot deadlock before timeout handling.
- Build results require both exit code 0 and a produced APK.
- Build workspaces are bounded and exclude generated VCS/IDE/build metadata.
- Toolchain import is atomic and rejects non-Android/desktop JDK bundles, path traversal, oversized entries, and oversized total archives.
- Fallback providers now survive thrown client exceptions and never emit duplicate partial responses.
- Thinking parameters are provider-aware: OpenAI-family reasoning effort, Gemini 3 thinking levels, and newer Anthropic adaptive effort are handled separately.
- Project-wide search avoids generated directories and caps text file size.
