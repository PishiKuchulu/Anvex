#!/usr/bin/env python3
"""Run Anvex's release verification gates without depending on private PFP files.

Exit codes:
  0 = all available gates pass and build gate is READY
  1 = one or more gates failed or build/schema gate is blocked
"""
from pathlib import Path
from datetime import datetime, timezone
import json, subprocess, sys

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "FINAL_RELEASE_AUDIT.json"

def run(name, cmd):
    p = subprocess.run(cmd, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return {"name": name, "returncode": p.returncode, "output": p.stdout[-12000:]}

results = [
    run("deep_audit", [sys.executable, "tools/deep_audit_30.py"]),
    run("launcher_icon_gate", [sys.executable, "tools/verify_launcher_icon.py"]),
    run("room_schema_gate", [sys.executable, "tools/verify_room_schema_snapshots.py"]),
    run("build_environment_gate", [sys.executable, "tools/verify_build_environment.py"]),
]

report = {
    "project": "Anvex",
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "privatePfpEvidenceRequired": False,
    "results": results,
    "interpretation": {
        "deep_audit": "must pass",
        "launcher_icon_gate": "must pass; CI additionally compares compiled APK icon pixels with source assets",
        "room_schema_gate": "must pass; missing current snapshot is a real block",
        "build_environment_gate": "must be READY before claiming BUILD_VERIFIED",
        "runtime": "not claimed by this source-only audit"
    }
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

failed = [r for r in results if r["returncode"] != 0]
print(f"WROTE | {OUT}")
for r in results:
    if r["returncode"] == 0:
        status = "PASS"
    elif "RESULT: BLOCKED" in r["output"]:
        status = "BLOCKED"
    else:
        status = "FAIL"
    print(f"{status} | {r['name']}")
print("RESULT: PASS" if not failed else f"RESULT: NOT_RELEASE_VERIFIED ({len(failed)} gate(s) not green)")
sys.exit(0 if not failed else 1)
