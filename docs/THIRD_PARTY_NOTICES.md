# THIRD_PARTY_NOTICES.md
| JetBrains Mono (JetBrains) | SIL OFL 1.1 | harness/editor monospace font (bundled under app/src/main/assets/harness/fonts) |
| Material Components for Android (Google) | Apache-2.0 | manifest Theme.Material3.DayNight frame |


Anvex includes and derives from open-source software. Machine-verified via
`scripts/license_audit.sh` (upstream copy in the build environment) reading the
license blocks of every POM resolved by the release build (151 artifacts).

## Upstream project

**bolt.diy** — MIT License, Copyright (c) 2024 StackBlitz, Inc. and bolt.diy
contributors (https://github.com/stackblitz-labs/bolt.diy)

- The streaming `<boltArtifact>/<boltAction>` engine protocol, the system-prompt
  artifact contract, and the action execution semantics were ported from
  `app/lib/runtime/message-parser.ts`, `app/lib/runtime/action-runner.ts`, and
  `app/lib/common/prompts/prompts.ts` with documented adaptations for the
  Android runtime. MIT requires preservation of this notice; see LICENSE.

## Shipped dependencies (all Apache-2.0)

| Component | License | Use |
|---|---|---|
| Kotlin stdlib / coroutines (JetBrains) | Apache-2.0 | language, async/streams |
| kotlinx-serialization (JetBrains) | Apache-2.0 (per POM) | provider/DTO JSON |
| OkHttp / Okio (Square) | Apache-2.0 | provider HTTP + SSE (MockWebServer used in tests only) |
| AndroidX: core, activity, lifecycle | Apache-2.0 | app foundation |
| Jetpack Compose (ui, foundation, material3, animation, runtime) | Apache-2.0 | UI |
| Material icons (core + extended) | Apache-2.0 | UI icons |
| Navigation Compose | Apache-2.0 | navigation |
| Room + KSP | Apache-2.0 | structured persistence |
| DataStore Preferences | Apache-2.0 | settings |
| Android Gradle Plugin / Gradle | Apache-2.0 | build tooling (not shipped in APK) |

## Bundled runtime assets (shipped in the APK, v1.1)

| Asset | Version | License | Use |
|---|---|---|---|
| `@babel/standalone` (babel.min.js) | 7.26.4 | MIT © Babel contributors | offline JSX/TSX transpilation in the preview browser |
| `react` UMD production build | 18.3.1 | MIT © Facebook, Inc. and its affiliates | offline React runtime for previews |
| `react-dom` UMD production build | 18.3.1 | MIT © Facebook, Inc. and its affiliates | offline React DOM renderer |
| `mermaid` (mermaid.min.js) | 12.2.0 | MIT © 2014-2024 Knut Sveidqvist & mermaid contributors | offline Mermaid diagram rendering in the preview browser (architect review item 3); fetched unmodified from https://cdn.jsdelivr.net/npm/mermaid@12/dist/mermaid.min.js (SHA-256 a8a6a6f805b074a8261a2825c86f99c8054acb3b35f50bb7e24a4f9f2c3c3b9f), embedded license notices kept in the file |

MIT license texts are embedded in the file headers and reproduced at
https://opensource.org/licenses/MIT. The ESM wrappers around them
(`react.js`, `react-dom-client.js`, `react-jsx-runtime.js`) are Anvex code
(MIT, this project).

## CDN-loaded assets at preview time (NOT bundled)

- esm.sh module CDN — dependency resolution for bare imports beyond the
  bundled react family (online-only, disclosed in the UI and to the model)

## Test-only dependencies (not shipped)

- JUnit 4.13.2 — Eclipse Public License 1.0 (test scope, excluded from APK)
- org.xerial:sqlite-jdbc 3.46.1.3 — Apache-2.0 (bundled SQLite public-domain
  core); JVM migration tests only, never packaged in the APK

## Notices

Apache-2.0 full text: https://www.apache.org/licenses/LICENSE-2.0
MIT full text: https://opensource.org/licenses/MIT
EPL-1.0 full text: https://www.eclipse.org/org/documents/epl-v10.php
