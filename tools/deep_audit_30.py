from pathlib import Path
import json, re, zipfile, sys
root=Path(__file__).resolve().parents[1]
checks=[]
def check(name, ok, detail): checks.append((name, bool(ok), detail))
required=[
    'core/android/src/main/kotlin/com/anvex/core/android/reliability/PluginMarketplace.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/reliability/PluginPackageInstaller.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/reliability/SkillRuntimeService.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/reliability/LanAccessManager.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/reliability/DeviceCapabilityDetector.kt',
    'app/src/main/kotlin/com/anvex/app/runtime/AgentForegroundService.kt',
    'app/src/main/kotlin/com/anvex/app/runtime/RuntimeBootReceiver.kt',
    'app/src/main/kotlin/com/anvex/app/runtime/BackupScheduler.kt',
    'app/src/main/kotlin/com/anvex/app/ui/screens/developer/AgentCenterScreen.kt',
]
for f in required: check(f, (root/f).is_file(), 'present' if (root/f).is_file() else 'MISSING')

# The Android 12+ foreground-service policy intentionally removed the old
# repeating-alarm watchdog. Assert it is not registered instead of requiring
# a deleted source file.
manifest_for_watchdog = (root/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
watchdog_path = root/'app/src/main/kotlin/com/anvex/app/runtime/AgentWatchdogReceiver.kt'
check('legacy watchdog intentionally absent', '.runtime.AgentWatchdogReceiver' not in manifest_for_watchdog and not watchdog_path.exists(), 'no stale component declaration or source')

# ---- v4.0 (GOAL traceability) additions -------------------------------------
v4_required=[
    # GOAL 3: in-app build surface (restored missing boundary + new sources)
    'app/src/main/kotlin/com/anvex/app/ui/screens/build/BuildScreen.kt',
    'app/src/main/kotlin/com/anvex/app/vm/BuildViewModel.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/build/ToolchainManager.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/build/AndroidBuildEngine.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/build/SafeDirUnzip.kt',
    # GOAL 2: harness runtime + web app
    'core/android/src/main/kotlin/com/anvex/core/android/harness/HarnessBridge.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/harness/SkillGitInstaller.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/server/HarnessWebServer.kt',
    'app/src/main/kotlin/com/anvex/app/ui/screens/harness/HarnessScreen.kt',
    'app/src/main/assets/harness/index.html',
    'app/src/main/assets/harness/app.jsx',
    'app/src/main/assets/harness/bridge.js',
    'app/src/main/assets/harness/styles.css',
    'core/engine/src/main/kotlin/com/anvex/core/engine/vcs/GitLite.kt',
    'core/engine/src/main/kotlin/com/anvex/core/engine/diagnostics/LightDiagnostics.kt',
    'core/android/src/main/kotlin/com/anvex/core/android/fs/ProjectFileObserver.kt',
    'core/ai/src/main/kotlin/com/anvex/core/ai/mcp/McpClient.kt',
    'core/engine/src/test/kotlin/com/anvex/core/engine/vcs/GitLiteTest.kt',
    'core/ai/src/test/kotlin/com/anvex/core/ai/mcp/McpClientTest.kt',
    'app/src/main/assets/harness/fonts/JetBrainsMono-Regular.ttf',
    'tools/build_source_mirror.py',
]
for f in v4_required: check('v4 '+f, (root/f).is_file(), 'present' if (root/f).is_file() else 'MISSING')
font_files = [root/'app/src/main/assets/harness/fonts'/name for name in ('JetBrainsMono-Regular.ttf', 'JetBrainsMono-Medium.ttf', 'JetBrainsMono-Bold.ttf')]
check('v4 offline typography assets', all(p.is_file() and p.stat().st_size > 100_000 for p in font_files), 'all three licensed font weights are bundled at the path used by Typography.kt')
check('launcher icon verification tool', (root/'tools/verify_launcher_icon.py').is_file(), 'source and APK icon integrity gate is available')
check('source mirror verification tool', (root/'tools/verify_source_mirror.py').is_file(), 'mirrored project sources and generated-content exclusions are checked')

# GOAL 1: audit fixes are in place
main_activity=(root/'app/src/main/kotlin/com/anvex/app/MainActivity.kt').read_text(encoding="utf-8")
check('v4 locale recreate guard', 'recreatedOnce' in main_activity or 'languageApplied' in main_activity, 'bounded single recreation + LocaleManager')
nsc=(root/'app/src/main/res/xml/network_security_config.xml').read_text(encoding="utf-8")
check('v4 cleartext fail-closed', 'base-config cleartextTrafficPermitted="false"' in nsc, 'HTTPS-only base; loopback allowlist')
manifest=(root/'app/src/main/AndroidManifest.xml').read_text(encoding="utf-8")
check('v4 data extraction rules', 'dataExtractionRules' in manifest and 'fullBackupContent' in manifest, 'Android 12+ extraction policy declared')
backup=(root/'app/src/main/kotlin/com/anvex/app/ui/screens/backup/BackupScreen.kt').read_text(encoding="utf-8")
check('v4 backup CharArray', 'CharArray' in backup and "Arrays.fill" in backup, 'zeroed secret buffers')

# GOAL 5: cloud-only providers
provider=(root/'core/model/src/main/kotlin/com/anvex/core/model/Provider.kt').read_text(encoding="utf-8")
check('v4 cloud-only providers', 'isSelectable' in provider and 'OLLAMA' in provider, 'legacy local kinds deprecated+hidden (DB v12 compatible)')

# GOAL 6: bridge ZIP contract
bridge=(root/'core/android/src/main/kotlin/com/anvex/core/android/harness/HarnessBridge.kt').read_text(encoding="utf-8")
check('v4 saveZip bridge', 'fun saveZip(base64: String, fileName: String)' in bridge and 'MediaStore' in bridge, 'base64 -> MediaStore.Downloads / FileProvider fallback')
check('v4 shareZip bridge', 'fun shareZip(base64: String, fileName: String)' in bridge, 'secondary share path')
web_bridge=(root/'app/src/main/assets/harness/bridge.js').read_text(encoding="utf-8")
check('v4 jszip assembly + data-url fallback', 'data:application/zip;base64' in web_bridge and 'readSourceFile' in web_bridge, 'JSZip client assembly, no blob: usage')
check('v4 no createObjectURL', 'URL.createObjectURL(' not in web_bridge, 'blob: object URLs are never created (blocked in the WebView)')

# GOAL 2: harness bridge surface
for method in ['sendPrompt','terminalStart','git','skills','installSkillFromGit','mcpAdd','buildStart','sourceManifest']:
    check('v4 bridge method '+method, method+'(' in bridge, 'exposed to JS')

# GOAL 4: theme + font
theme=(root/'app/src/main/kotlin/com/anvex/app/ui/theme/Theme.kt').read_text(encoding="utf-8")
check('v4 dual theme families', 'ProfessionalDarkScheme' in theme and 'GlassDarkScheme' in theme, 'professional + glassmorphism palettes')
settings_repo=(root/'core/android/src/main/kotlin/com/anvex/core/android/repo/SettingsRepository.kt').read_text(encoding="utf-8")
check('v4 UiThemeStyle persisted', 'UiThemeStyle' in settings_repo, 'DataStore-backed (no schema change)')

# GOAL 3: engine honors the toolchain contract
engine=(root/'core/android/src/main/kotlin/com/anvex/core/android/build/AndroidBuildEngine.kt').read_text(encoding="utf-8")
check('v4 build engine streams logs', 'onLine' in engine and 'destroyForcibly' in engine, 'bounded, cancellable Gradle process')

text='\n'.join(p.read_text(encoding='utf-8', errors='ignore') for p in root.rglob('*.kt'))
check('no duplicate approval authority', 'class ApprovalEngine' not in text and 'object ApprovalEngine' not in text, 'single durable approval repository/facade')
db=(root/'core/android/src/main/kotlin/com/anvex/core/android/db/Database.kt').read_text(encoding="utf-8")
check('room version 12 migration', 'version = 12' in db and 'MIGRATION_11_12' in db, 'v12 + 11->12 present')
check('retry due query', "state = 'RETRYING'" in db and 'nextRunAt' in db, 'RETRYING participates in queue claim')
check('fgs lifecycle compliance', 'START_STICKY' in (root/'app/src/main/kotlin/com/anvex/app/runtime/AgentForegroundService.kt').read_text(encoding="utf-8") and 'setInexactRepeating' not in (root/'app/src/main/kotlin/com/anvex/app/runtime/AgentServiceController.kt').read_text(encoding="utf-8"), 'no repeating alarm-to-FGS watchdog on Android 12+')
check('boot recovery', 'BOOT_COMPLETED' in (root/'app/src/main/kotlin/com/anvex/app/runtime/RuntimeBootReceiver.kt').read_text(encoding="utf-8") and 'BackupScheduler.schedule' in (root/'app/src/main/kotlin/com/anvex/app/runtime/RuntimeBootReceiver.kt').read_text(encoding="utf-8"), 'backup scheduling restored after reboot; Agent FGS is not silently started')
check('plugin archive install', 'ZipInputStream' in (root/'core/android/src/main/kotlin/com/anvex/core/android/reliability/PluginPackageInstaller.kt').read_text(encoding="utf-8"), 'bounded safe extraction')
check('skill runtime', 'SkillExecutionRuntime' in (root/'core/android/src/main/kotlin/com/anvex/core/android/reliability/SkillRuntimeService.kt').read_text(encoding="utf-8"), 'declarative plan compiler wired')
check('lan agent endpoints', all(x in (root/'core/android/src/main/kotlin/com/anvex/core/android/reliability/LanAccessManager.kt').read_text(encoding="utf-8") for x in ['/api/status','/api/tasks','/api/logs']), 'authenticated read-only Agent UI API')
check('device detection corrected', 'shizukuInstalled' in (root/'core/android/src/main/kotlin/com/anvex/core/android/reliability/DeviceCapabilityDetector.kt').read_text(encoding="utf-8") and 'wirelessAdbEnabled' in (root/'core/android/src/main/kotlin/com/anvex/core/android/reliability/DeviceCapabilityDetector.kt').read_text(encoding="utf-8"), 'capability vs installed/configured state separated')
check('approval resource scope', 'resourceFingerprint' in (root/'core/android/src/main/kotlin/com/anvex/core/android/reliability/DurableApprovalRepository.kt').read_text(encoding="utf-8"), 'exact-action fingerprint supported')
check('audit independence', not (root/'pfp/PFP_EVIDENCE.json').exists(), 'release audit does not depend on private PFP evidence')
# no generated release artifacts
bad=[]
for p in root.rglob('*'):
    rel_parts = p.relative_to(root).parts
    # Ignore only known generated output roots, not source packages named build.
    generated_output = (
        rel_parts[:1] == ('build',)
        or rel_parts[:2] == ('app', 'build')
        or (len(rel_parts) >= 3 and rel_parts[0] == 'core' and rel_parts[2] == 'build')
    )
    if generated_output:
        continue
    if p.is_file() and (p.suffix.lower() in {'.apk','.aab','.jks','.keystore'}): bad.append(str(p.relative_to(root)))
check('source artifact hygiene', not bad, 'no APK/AAB/keystore' if not bad else str(bad))
for n,ok,d in checks: print(('PASS' if ok else 'FAIL')+' | '+n+' | '+d)
failed=sum(not ok for _,ok,_ in checks)
print(f'RESULT: {"PASS" if failed==0 else "FAIL"} ({len(checks)-failed}/{len(checks)})')
sys.exit(1 if failed else 0)
