# Final UI/UX Deep Audit


## Remediation pass — v3.2.3
- **P0 fixed:** verified APKs are copied into persistent app-private `build-artifacts/<projectId>/` storage before the disposable build workspace is deleted.
- **P0 UX fixed:** Build UI now lists verified APK artifacts and exposes a secure Android share action through `FileProvider`.
- **P1 fixed:** GitHub token input is visually protected; Build/GitHub have explicit back navigation; GitHub is vertically scrollable on small screens; floating Agent status is raised above the bottom navigation safe area.
- **P1 fixed:** remaining hard-coded artifact status colors were replaced by semantic Material 3 color tokens.
- **Verification status:** static source checks can be performed; full Gradle compilation and physical-device smoke testing remain pending because this environment cannot currently fetch the Gradle distribution.
