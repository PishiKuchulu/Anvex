# Anvex Audit Reconciliation — v3.3.9

This document reconciles the external model audit against the actual v3.3.8 source tree. A finding is not treated as a defect merely because it was suggested: it must be supported by the current source.

| ID | Finding | Verdict | Action |
|---|---|---|---|
| C-01 | Provider API keys stored plaintext | FALSE / already hardened | Keystore AES-GCM storage verified in ProviderRepository |
| C-02 | WebView file/universal file access open | FALSE / already hardened | File/content access and file-URL access disabled; navigation constrained |
| C-03 | CI signing keystore/secret exposure | TRUE | Fixed: runner-temp keystore, env-backed Gradle properties, masking, unconditional cleanup |
| H-01 | No DI framework / singleton leak | NOT A DEFECT | Manual DI is intentional; no evidence of leak |
| H-02 | Missing foregroundServiceType | FALSE | `dataSync` declared in manifest |
| H-03 | Exact Alarm / SCHEDULE_EXACT_ALARM | FALSE | Backup uses inexact repeating alarm |
| H-04 | Missing certificate pinning | OPTIONAL HARDENING | Not a demonstrated vulnerability; pinning deferred |
| H-05 | Destructive Room migration | FALSE AS STATED | No destructive fallback; schema 12 snapshot remains build-gated |
| H-06 | Babel/React asset size | VALID OPTIMIZATION | Large runtime assets remain; optimize only with preview regression coverage |
| H-07 | Missing instrumented/UI tests | TRUE | Remains a release-quality test gap |
| M-01 | Deprecated Compose plugin | FALSE | `org.jetbrains.kotlin.plugin.compose` already used |
| M-02 | OkHttp old | MAINTENANCE | Upgrade only after compatibility/security review |
| M-03 | Room 2.6.1 old | MAINTENANCE | Upgrade is optional; migration snapshot must be regenerated if changed |
| M-04 | vectorDrawables support library | LOW | Non-critical cleanup |
| M-05 | kotlin_module packaging exclusion | REJECTED | Suggested exclusion is not a valid general duplicate-class fix |
| M-06 | R8 fullMode | OPTIONAL OPTIMIZATION | No fixed percentage claim without benchmark |
| M-07 | localeConfig missing | FALSE | Manifest declares localeConfig |
| M-08 | FileProvider broad paths | FALSE | Only app-private build-artifacts path is exposed |
| M-09 | Boot permission runtime check | FALSE AS STATED | RECEIVE_BOOT_COMPLETED is manifest permission; no runtime prompt required |
| M-10 | Alarm watchdog duplication | FALSE / already hardened | No repeating AlarmManager FGS watchdog remains |
| M-11 | Syntax highlighting jank | VALID OPTIMIZATION | Candidate for profiling/debouncing, not correctness defect |
| M-12 | DiffSheet lacks LazyColumn | FALSE | LazyColumn is present |
| M-13 | Persian strings incomplete | PARTIALLY TRUE | Hardcoded Compose strings remain; translation audit needed |
| L-01 | README.md.txt | FALSE | Current release contains README.md |
| L-02 | Wrapper jar | INFO | Normal wrapper distribution; validation remains enabled |
| L-03 | Extended material icons size | VALID OPTIMIZATION | Candidate for icon minimization |
| L-04 | Debug FileProvider conflict | FALSE | Authority uses `${applicationId}` |
| L-05 | JUnit 4 must be replaced | OPTIONAL | No defect requiring migration |
| L-06 | sqlite-jdbc wrong scope | FALSE | Test-only scope is already used |
| L-07 | Python requirements missing | NOT A DEFECT WITHOUT THIRD-PARTY IMPORTS | Current audit scripts use standard library |
| L-08 | Duplicate changelog | DOCUMENTATION DEBT | Keep canonical root changelog; docs copy is historical/reference |
| L-09 | Manual third-party notices | PROCESS IMPROVEMENT | Automation can be added later |

## Current gates

- Static audit: PASS (22/22)
- Source hygiene: PASS
- Room migration declarations: PASS (1→12)
- Room schema 12.json: BLOCKED until real Room/KSP build generates it
- Gradle build: UNVERIFIED in the current offline environment
- Device/runtime verification: UNVERIFIED

No score or production-readiness claim is made by this reconciliation.
