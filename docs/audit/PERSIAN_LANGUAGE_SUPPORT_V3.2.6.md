# Persian Language Support — v3.2.7

## Scope
Anvex now exposes an application language preference in Settings > Appearance > Language.

Supported values:
- System default
- English
- فارسی (Persian)

## Behavior
- The selected language is persisted in DataStore.
- The language setting is included in the serialized settings backup and restored with existing settings.
- Android 13+ uses the platform per-app locale API through `LocaleManager`.
- `res/xml/locales_config.xml` declares English and Persian as supported app locales.
- Persian mode provides RTL layout direction at the Compose application root.
- Android 8.0+ fallback updates the application resources and recreates the Activity when an explicit language is selected.

## Verification
- XML resources parse successfully.
- Manifest declares `android:localeConfig`.
- No credentials or user data are introduced by the language feature.
- Gradle compile remains pending in the audit environment because Gradle 8.10.2 cannot be downloaded from `services.gradle.org`.

## UX note
This release establishes the language-selection infrastructure and Persian RTL behavior. Existing Compose copy that remains hard-coded in English will be localized incrementally; selecting فارسی does not claim that every string in the application is translated yet.
