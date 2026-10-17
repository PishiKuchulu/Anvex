# Batch 1 Deep Hardening Audit — v2.3.1

## Scope
Post-Batch-1 static review of agent orchestration, action approval, workspace mutation, durable tasks, and Android shell gateways.

## Findings fixed

1. **Non-resumable queued tasks were never executed.**
   The task DAO previously filtered all queued work by `resumable = 1`. Queued non-resumable tasks are now executable; only interrupted non-resumable tasks are excluded from automatic recovery.

2. **Recursive direct workspace deletion bypassed approval.**
   `ActionDispatcher.deleteFile(..., recursive=true)` now applies the central approval policy before mutation.

3. **AI Android shell allowed shell-metacharacter bypasses.**
   AI-originated commands now reject command chaining, pipes, redirects, command substitution, traversal, absolute/home paths, and `find` execution/deletion predicates before spawning `/system/bin/sh`.

4. **Risk classification was too narrow.**
   The central safety policy now treats compound shell control syntax as requiring approval and recognizes additional destructive forms such as `--recursive`, `--force`, and `git reset --hard` variants.

5. **Interactive terminal AI gateway duplicated weaker validation.**
   The same bounded syntax/path/option restrictions are applied before AI commands are written to the persistent shell session.

## Verification

- PFP production static scan: **PASS**
- Python syntax check for PFP checker: **PASS**
- Source-counted tests: updated with regression coverage for compound shell commands and recursive workspace deletion approval.
- Full Gradle build/test: **BLOCKED BY ENVIRONMENT** — wrapper cannot resolve `services.gradle.org` in the current offline/DNS-restricted environment.

## Remaining limitations

- Full BUILD_VERIFIED and RUNTIME_VERIFIED remain pending a network-capable Android/Gradle environment.
- AgentTaskBridge remains a runtime foundation and is not yet exposed as the complete UI/provider-driven agent session.
- Linux/Node.js runtimes remain future phases.
