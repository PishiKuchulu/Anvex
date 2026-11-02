#!/usr/bin/env python3
"""Check the Gradle version catalog against the public OSV database (E1).

Dependabot proposes updates but nothing in CI said "this exact pinned version has a published
advisory". This script reads gradle/libs.versions.toml, asks https://api.osv.dev for each Maven
coordinate at the pinned version and prints the advisory ids.

* stdlib only (Python 3.11+: tomllib) — no third-party action or package to trust.
* Only package coordinates and versions are sent (public information).
* Report-only by default; pass --fail to exit 1 when any advisory is found.
* --dry-run prints the queries without any network access.
"""
import argparse
import json
import os
import sys
import tomllib
import urllib.request

CATALOG = "gradle/libs.versions.toml"
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
# Build plugins live in [plugins]/classpath, not [libraries]; their Maven coordinates differ.
PLUGIN_COORDS = {
    "agp": "com.android.tools.build:gradle",
    "kotlin": "org.jetbrains.kotlin:kotlin-gradle-plugin",
}


def coordinates(catalog: dict):
    versions = catalog.get("versions", {})
    out = []
    for alias, spec in catalog.get("libraries", {}).items():
        if isinstance(spec, str):  # "group:artifact:version"
            parts = spec.split(":")
            if len(parts) == 3:
                out.append((alias, f"{parts[0]}:{parts[1]}", parts[2]))
            continue
        module = spec.get("module") or f"{spec.get('group')}:{spec.get('name')}"
        ver = spec.get("version")
        if isinstance(ver, dict):
            ver = versions.get(ver.get("ref", ""))
        if module and isinstance(ver, str):
            out.append((alias, module, ver))
    for key, module in PLUGIN_COORDS.items():
        if key in versions:
            out.append((f"plugin:{key}", module, versions[key]))
    return out


def query(coords):
    body = json.dumps({"queries": [
        {"package": {"name": module, "ecosystem": "Maven"}, "version": version}
        for _, module, version in coords
    ]}).encode()
    req = urllib.request.Request(OSV_BATCH, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)["results"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fail", action="store_true", help="exit 1 when advisories are found")
    ap.add_argument("--dry-run", action="store_true", help="print the queries, no network")
    ap.add_argument("--catalog", default=CATALOG)
    args = ap.parse_args()

    with open(args.catalog, "rb") as fh:
        coords = coordinates(tomllib.load(fh))
    if args.dry_run:
        for alias, module, version in coords:
            print(f"{module}@{version}  ({alias})")
        print(f"{len(coords)} coordinates", file=sys.stderr)
        return 0

    results = query(coords)
    lines, hits = [], 0
    for (alias, module, version), res in zip(coords, results):
        ids = [v["id"] for v in res.get("vulns", [])]
        if ids:
            hits += 1
            links = ", ".join(f"[{i}](https://osv.dev/vulnerability/{i})" for i in ids)
            lines.append(f"- `{module}@{version}` ({alias}): {links}")
    summary = (f"## OSV audit of {args.catalog}\n\n"
               + (f"{hits} of {len(coords)} pinned versions have published advisories:\n\n" + "\n".join(lines)
                  if hits else f"No published advisories for {len(coords)} pinned versions.") + "\n")
    print(summary)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(summary)
    return 1 if (hits and args.fail) else 0


if __name__ == "__main__":
    sys.exit(main())
