#!/usr/bin/env python3
"""Verify the generated in-app source mirror is complete, current, and clean."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIRROR = ROOT / "app/src/main/assets/source"
MANIFEST_PATH = MIRROR / "source-manifest.json"
REQUIRED = (
    "core/android/src/main/kotlin/com/anvex/core/android/build/ToolchainManager.kt",
    "core/android/src/main/kotlin/com/anvex/core/android/build/AndroidBuildEngine.kt",
    "core/android/src/main/kotlin/com/anvex/core/android/build/SafeDirUnzip.kt",
    "app/src/main/kotlin/com/anvex/app/ui/screens/build/BuildScreen.kt",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
)
FORBIDDEN = (
    "tools/",
    ".github/",
    "docs/superpowers/",
    "docs/FINAL_RELEASE_AUDIT.json",
    "docs/PFP_V4_TRACEABILITY.md",
    "app/src/main/assets/runtime/",
    "app/src/main/assets/harness/fonts/",
    "app/src/main/assets/harness/jszip.min.js",
)
SENSITIVE_PATH_TOKENS = (
    "E:/4/BoltDroid/worktree-v4",
    "E:\\4\\BoltDroid\\worktree-v4",
    "/mnt/data/anvex_work/Anvex",
)


def report(ok: bool, label: str, detail: str = "") -> bool:
    print(f"{'PASS' if ok else 'FAIL'} | {label}" + (f" | {detail}" if detail else ""))
    return ok


def main() -> int:
    failures = 0
    if not MANIFEST_PATH.is_file():
        report(False, "source mirror manifest exists", str(MANIFEST_PATH))
        return 1
    try:
        entries = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        ok = isinstance(entries, list) and bool(entries)
        failures += not report(ok, "manifest is a non-empty JSON list", f"{len(entries) if isinstance(entries, list) else 'invalid'} entries")
    except (OSError, json.JSONDecodeError) as exc:
        report(False, "source mirror manifest parses", str(exc))
        return 1

    seen: set[str] = set()
    stale: list[str] = []
    for entry in entries:
        rel = entry.get("path", "") if isinstance(entry, dict) else ""
        if not rel or rel in seen:
            stale.append(f"invalid/duplicate entry: {rel!r}")
            continue
        seen.add(rel)
        path = MIRROR / rel
        expected_size = entry.get("size")
        if not path.is_file() or path.stat().st_size != expected_size:
            stale.append(rel)
    failures += not report(not stale, "manifest entries match mirrored file sizes", ", ".join(stale[:8]) if stale else f"{len(entries)} files consistent")

    missing = [rel for rel in REQUIRED if not (MIRROR / rel).is_file()]
    failures += not report(not missing, "critical build and launcher sources preserved", ", ".join(missing) if missing else "build package and icon sources included")

    leaked = [rel for rel in seen if rel.startswith(FORBIDDEN)]
    failures += not report(not leaked, "internal tooling/vendor binaries excluded from app source mirror", ", ".join(leaked[:8]) if leaked else "exclusion policy applied")

    sensitive_hits: list[str] = []
    for path in MIRROR.rglob("*"):
        if not path.is_file() or path.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".ttf", ".otf", ".jar", ".zip", ".woff", ".woff2"}:
            continue
        try:
            content = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for token in SENSITIVE_PATH_TOKENS:
            if token in content:
                sensitive_hits.append(f"{path.relative_to(MIRROR)} contains a stale machine path")
                break
    failures += not report(not sensitive_hits, "no known local-machine paths embedded", ", ".join(sensitive_hits[:8]) if sensitive_hits else "no known path leakage")

    bad_artifacts = [rel for rel in seen if Path(rel).suffix.lower() in {".apk", ".aab", ".jks", ".keystore", ".pyc"}]
    failures += not report(not bad_artifacts, "no build artifacts or bytecode embedded", ", ".join(bad_artifacts[:8]) if bad_artifacts else "clean")

    print(f"RESULT: {'PASS' if failures == 0 else 'FAIL'} ({6 - failures}/6 checks passed)")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
