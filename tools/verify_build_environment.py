#!/usr/bin/env python3
"""Report whether the local environment can perform the authoritative Gradle/KSP gate."""
from pathlib import Path
import shutil, subprocess, sys

import os
root = Path(__file__).resolve().parents[1]
wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
print('Anvex Build Environment Gate')
print(f'Gradle wrapper: ./{wrapper.name}')
print(f'Exists: {wrapper.exists()}')

if not wrapper.exists():
    print('RESULT: BLOCKED (gradlew missing)')
    sys.exit(2)

try:
    p = subprocess.run([str(wrapper), '--version'], cwd=root, text=True,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    print(p.stdout[-4000:])
    if p.returncode == 0:
        print('RESULT: READY')
        sys.exit(0)
    lowered = p.stdout.lower()
    if any(marker in lowered for marker in ('unknownhostexception', 'unknown host', 'could not resolve host', 'connection timed out', 'network is unreachable')):
        print('RESULT: BLOCKED (Gradle distribution download failed because network/DNS is unavailable)')
    else:
        print('RESULT: BLOCKED (Gradle wrapper failed; distribution or toolchain is unavailable)')
    sys.exit(1)
except subprocess.TimeoutExpired:
    print('RESULT: BLOCKED (Gradle wrapper timed out)')
    sys.exit(1)
except OSError as exc:
    print(f'RESULT: BLOCKED ({exc})')
    sys.exit(1)

print('RESULT: BLOCKED (Gradle distribution/toolchain unavailable in this environment)')
sys.exit(1)
