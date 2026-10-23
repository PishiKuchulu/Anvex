#!/usr/bin/env python3
"""Builds the GOAL 6 source-mirror for the harness ZIP export.

Copies the full v4 source tree into app/src/main/assets/source/ and generates
source-manifest.json (path + size per file). The mirror excludes itself (it
cannot contain itself), build outputs, and environment-local files.

Run from the project root:  python3 tools/build_source_mirror.py
"""
from pathlib import Path
import json
import os
import sys

ROOT = Path(__file__).resolve().parents[1]
MIRROR = ROOT / "app" / "src" / "main" / "assets" / "source"
MANIFEST_NAME = "source-manifest.json"

EXCLUDED_TOP = {
    ".git", ".gradle", ".idea", "build", "local.properties", ".kotlin",
    ".DS_Store", "__pycache__", ".superpowers",
}
BINARY_BY_EXT = {
    ".jar", ".zip", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
    ".ttf", ".otf", ".woff", ".woff2", ".apk", ".jks", ".keystore", ".pyc",
}


EXCLUDED_SOURCE_PREFIXES = (
    "app/src/main/assets/runtime/",       # React/Babel vendor bundles (in the app assets)
    "app/src/main/assets/harness/fonts/",  # JetBrains Mono (in the app assets)
    "app/src/main/assets/harness/jszip.min.js",
    "tools/",                              # build and audit scripts
    "docs/audit/",                         # historical audit reports
    "docs/superpowers/",                    # private implementation plans, not app source
    "docs/FINAL_RELEASE_AUDIT.json",         # machine-specific generated audit output
    "docs/PFP_V4_TRACEABILITY.md",            # internal governance traceability, not app source
    ".github/",                            # CI configuration
    "keystore/",                           # v4.10.1 (CI-02): the fixed public debug keystore is
                                           # build infrastructure, not app source — it must not
                                           # be mirrored into the app's bundled source assets
                                           # (the mirror verifier rejects keystore artifacts).
)


def is_excluded(rel: str) -> bool:
    """Decide whether a repo-relative path stays out of the source mirror.

    v4.2 fix: the previous rule dropped EVERY path segment named ``build`` (i > 0),
    which silently deleted real source packages -- ``core/android/.../build/``
    (ToolchainManager, AndroidBuildEngine, SafeDirUnzip) and ``.../screens/build/``
    (BuildScreen) -- so the exported ZIP could not compile. Only Gradle *output*
    directories are excluded now: the root ``build/``, ``app/build/`` and
    ``core/<module>/build/``.
    """
    parts = rel.split("/")
    top = parts[0]
    if top in EXCLUDED_TOP:
        return True
    if rel.startswith("app/src/main/assets/source"):
        return True
    if rel.startswith("app/build/"):
        return True
    if len(parts) >= 3 and parts[0] == "core" and parts[2] == "build":
        return True
    for part in parts[1:]:
        if part in {"__pycache__", ".gradle", ".idea"}:
            return True
    if rel == "local.properties" or rel.endswith("/local.properties"):
        return True
    # v4.3.0: keep the export to source. Vendor bundles and fonts ship in the installed app's assets
    # (single copy), build tooling and audit reports are not part of the app source.
    if rel.startswith(EXCLUDED_SOURCE_PREFIXES):
        return True
    return False


def main() -> int:
    if not (ROOT / "settings.gradle.kts").is_file():
        print("ERROR: run from the Anvex project root", file=sys.stderr)
        return 2

    # 1. collect files
    entries = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT).as_posix()
        if is_excluded(rel):
            continue
        entries.append((rel, path))

    # 2. wipe and rebuild the mirror
    if MIRROR.exists():
        for child in MIRROR.iterdir():
            if child.is_dir():
                import shutil
                shutil.rmtree(child)
            else:
                child.unlink()
    MIRROR.mkdir(parents=True, exist_ok=True)

    import shutil
    manifest = []
    total = 0
    for rel, path in entries:
        target = MIRROR / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        size = path.stat().st_size
        total += size
        manifest.append({
            "path": rel,
            "size": size,
            "encoding": "binary" if path.suffix.lower() in BINARY_BY_EXT else "text",
        })

    (MIRROR / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=1, ensure_ascii=False) + "\n", encoding="utf-8",
    )

    print(f"Mirrored {len(manifest)} files ({total / (1024 * 1024):.1f} MB) into {MIRROR}")
    print(f"Manifest: {MIRROR / MANIFEST_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
