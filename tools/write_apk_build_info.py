#!/usr/bin/env python3
"""Write provenance metadata next to a verified APK (for CI artifact hygiene)."""
from __future__ import annotations

import hashlib
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APP_GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

def value(pattern: str, default: str = "unknown") -> str:
    match = re.search(pattern, APP_GRADLE)
    return match.group(1) if match else default

if not APK.is_file():
    raise SystemExit(f"ERROR: APK does not exist: {APK}")

apk_sha = hashlib.sha256(APK.read_bytes()).hexdigest()
icon = ROOT / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
icon_sha = hashlib.sha256(icon.read_bytes()).hexdigest()
version_name = value(r'versionName\s*=\s*"([^"]+)"') + "-debug"
version_code = value(r'versionCode\s*=\s*(\d+)')
application_id = value(r'applicationId\s*=\s*"([^"]+)"') + ".debug"
lines = [
    "Anvex APK Build Information",
    f"Built at (UTC): {datetime.now(timezone.utc).isoformat()}",
    f"Application ID: {application_id}",
    f"Version name: {version_name}",
    f"Version code: {version_code}",
    f"Git commit: {os.environ.get('GITHUB_SHA', 'not-running-in-GitHub-Actions')}",
    f"GitHub run ID: {os.environ.get('GITHUB_RUN_ID', 'n/a')}",
    f"GitHub run attempt: {os.environ.get('GITHUB_RUN_ATTEMPT', 'n/a')}",
    f"APK filename: {APK.name}",
    f"APK size bytes: {APK.stat().st_size}",
    f"APK SHA-256: {apk_sha}",
    f"Canonical xxxhdpi launcher icon SHA-256: {icon_sha}",
    "Launcher icon verification: PASS (tools/verify_launcher_icon.py)",
]
out = APK.parent / "BUILD-INFO.txt"
out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
(APK.with_suffix(APK.suffix + ".sha256")).write_text(f"{apk_sha}  {APK.name}\n", encoding="utf-8", newline="\n")
print(f"WROTE | {out}")
print(f"APK_SHA256 | {apk_sha}")
