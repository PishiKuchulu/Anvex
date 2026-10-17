# Anvex 3.2.6 — External Audit Remediation

- Fixed missing coroutine imports and retained one NavHost instance.
- Reworked Gradle execution as cancellable, deadline-bounded process management with forced cleanup.
- Builds use the trusted app-managed Gradle executable rather than project-local `gradlew`.
- APK verification is limited to fresh `build/outputs/apk` artifacts.
- Agent file mutations use the exact durable approval decision at the Android bridge boundary.
- ChatSession rebuilds when the selected/default provider configuration changes.
- Removed the Android 12+ incompatible repeating AlarmManager foreground-service watchdog.
- Preview WebView is explicitly destroyed on release and its JavascriptInterface members are kept for R8.
- Project-local Node executables are no longer trusted.
- LAN status defaults to loopback; LAN exposure is an explicit user choice with token copy support.
- Security/limitations docs now distinguish Preview, Agent/Terminal, Node and Gradle capabilities.
- Local HTTP providers are allowed only for loopback/private/local endpoints by repository validation.
- Version metadata aligned to 3.2.6 / versionCode 49.

Full Gradle/device verification remains pending until a real Android SDK/JDK/Gradle environment and physical device are available.
