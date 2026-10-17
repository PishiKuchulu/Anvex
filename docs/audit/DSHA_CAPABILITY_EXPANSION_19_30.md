# DSHA Capability Expansion 19–30

## Implemented boundaries
- Plugin marketplace catalog and HTTPS Git URL descriptor installer.
- Plugin dependency graph with cycle detection and dependency-aware enable/disable.
- Executable declarative Skill plan compiler with capability checks; arbitrary code execution remains prohibited.
- Foreground Agent Service drains the durable task engine and exposes a watchdog heartbeat.
- Authenticated LAN web surface with read-only status/HTML UI and configurable port settings.
- Device capability matrix for API/ABI, ARM64, wireless ADB eligibility, foreground service and web runtime.
- Advanced encrypted backup envelope migration pipeline.
- Unified command approval facade over durable approval grants.

## Security boundaries
- No credentials in plugin Git URLs.
- Marketplace fetches metadata only; repository code is not executed.
- Skill runtime cannot execute arbitrary code and cannot request undeclared capabilities.
- LAN surface exposes no files, shell, agent execution or credentials.
- Web port is bounded to non-privileged ports 1024–65535.
- Foreground service is app-owned; watchdog only restarts the service and does not execute remote commands.

## Verification limitation
The source was statically checked and PFP static compliance passes. A real Gradle build and Android device runtime verification remain required before BUILD_VERIFIED/RUNTIME_VERIFIED can be claimed.
