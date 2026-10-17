# BUILD.md

## Requirements

- JDK 17 (Temurin recommended) — must include `javac` (a JRE is not enough)
- Android SDK with `platforms;android-35`, `build-tools;35.0.0` and `platform-tools`
  (set via `local.properties`: `sdk.dir=/path/to/sdk`, or `ANDROID_HOME`)
- ~4 GB free RAM for Gradle + R8

Source build baseline: AGP 8.7.3, Gradle 8.10.2,
Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, JDK 17.0.20.

## Imported-project trust boundary

Build scripts (`build.gradle*`, `settings.gradle*`) are executable project code. Before starting an imported build, the UI should show the static risk scan and require explicit user confirmation. `BuildScriptRiskScanner` is advisory; it is not a sandbox and a Gradle build can still execute arbitrary build logic.

## Build

```bash
# debug APK (installable, debug-signed)
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk

# unit tests (engine + provider adapters, plain JVM)
./gradlew test
./gradlew :app:lint

# release APK (R8 minified + resource shrinking)
./gradlew :app:assembleRelease \
  -PANVEX_KEYSTORE=/abs/path/release.keystore \
  -PANVEX_KEYSTORE_PASSWORD=secret \
  -PANVEX_KEY_PASSWORD=secret \
  -PANVEX_KEY_ALIAS=mykey
# output: app/build/outputs/apk/release/app-release.apk

# AAB
./gradlew :app:bundleRelease -PANVEX_KEYSTORE=... -PANVEX_KEYSTORE_PASSWORD=... -PANVEX_KEY_PASSWORD=... -PANVEX_KEY_ALIAS=...
# output: app/build/outputs/bundle/release/app-release.aab
```

Without signing properties the release/AAB artifacts are unsigned. The preferred property prefix is `ANVEX_*`; legacy `BOLT_*` aliases remain supported. If any signing input is provided but incomplete or invalid, the build fails instead of silently producing an unsigned package. If no signing properties are set, Gradle intentionally creates an unsigned release; sign it with `apksigner` before installing.

## Signing

Create your own key (do not ship a known key in a public repo):

```bash
keytool -genkeypair -keystore release.keystore -alias mykey \
  -keyalg RSA -keysize 2048 -validity 10000
```

For Play Store distribution, use Play App Signing and upload the AAB.

## CI (reproducible builds without a local Android setup)

`.github/workflows/build-apk.yml` runs source/icon/schema checks and JVM unit tests, then builds a fresh debug APK. The CI verifies that the compiled APK contains the committed launcher-icon pixels and uploads the APK with its SHA-256 and build provenance. Release signing is intentionally not performed in CI.

## Verify an APK

```bash
$SDK/build-tools/35.0.0/aapt2 dump badging app-release.apk | head
$SDK/build-tools/35.0.0/apksigner verify --print-certs app-release.apk
```

Expected for release: `package: name='com.anvex.app'`, `sdkVersion:'26'`, `targetSdkVersion:'35'`. The debug workflow APK uses `com.anvex.app.debug` by design. Review the merged manifest when validating permissions; Anvex also declares foreground-service, notification, boot-recovery, and speech-recognition query entries.

## Install on a device

```bash
adb install app-release.apk      # or sideload the APK directly on the device
```

Android 8.0+ (minSdk 26). "Install unknown apps" permission is needed when sideloading.


## On-device build contract (Anvex)

The in-app Android Build Engine is a real Gradle/AGP process runner, not a simulator. The imported toolchain must be an **Android-compatible JDK/runtime built for the phone ABI**, plus Android SDK and Gradle. A normal desktop Temurin/Oracle JDK built against glibc is not a valid Android toolchain and is rejected by `toolchain.json`.

The required manifest fields are: `platform: "android"` and a non-empty `abi` (for example `arm64-v8a`). The bundle must contain `jdk/bin/java`, `sdk/platforms/`, `sdk/build-tools/`, and `gradle/bin/gradle` (or a supported wrapper bundle).

## Room schema snapshots

`core/android` uses `exportSchema = true` and the migration chain currently runs through schema version 12.
Historical v2 and current v12 snapshots are checked in. The schema gate verifies that the current snapshot is version-matched and that migrations 1→12 are declared. Do not hand-edit Room identity hashes; regenerate snapshots with Room/KSP in a configured Gradle environment when the schema changes.
