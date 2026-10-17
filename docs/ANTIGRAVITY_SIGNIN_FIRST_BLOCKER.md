# Google Antigravity "sign-in-first" UX — blocker assessment

**Date:** 2026-10-17 · **Prompt:** `AntiG_Fix_1` ("Replace the Manual Antigravity
Adapter Form with a Google Sign-In-First Experience")
**Verdict: STOP — do not implement.** No code was changed. This document is the
deliverable the prompt requires when direct sign-in is unsupported
("stop and document the exact technical and policy blocker before implementing
a workaround").

Companion document: `docs/ANTIGRAVITY_ASSESSMENT.md` (research date 2026-10-16)
already decided the adapter route (Option B) and rejected Options A and C.
This file records the re-verification performed for the sign-in-first request
on 2026-10-17, including an independent live check of the official terms page.

## Phase 1 — what the source actually contains (verified 2026-10-17)

- `ProviderKind.ANTIGRAVITY` ("Google Antigravity (via adapter)"), `defaultBaseUrl`
  deliberately empty — the adapter endpoint is always user-configured.
- `AntigravityClient` (`:core:ai`) speaks ONLY to the user-configured adapter:
  `GET {base}/models` (live discovery, never hardcoded), `POST {base}/chat/completions`
  (SSE), adapter admin API (`/admin/auth/start`, `/admin/auth/status`,
  `/admin/status`). It never calls Google, never embeds a first-party OAuth
  client id/secret, never forges the official CLI fingerprint.
- The Add-provider UI already carries the honest disclosures the prompt's
  fallback requires: "Via your own adapter — there is no official third-party
  Antigravity API", the unofficial-route warning (ToS prohibition, account
  risk, no quota data), and the "Connect account" / "Check account" buttons are
  wired to the adapter-mediated browser sign-in (`ProvidersScreen.kt`,
  `ProvidersViewModel.kt`, `Strings.kt` — EN + FA).
- Conclusion: the "clearly separated, optional adapter-based mode" the prompt
  prescribes as the fallback is exactly what 4.4.3/4.4.4 already ships.

## Phase 2 — verification results (each question answered)

**Q1. Is there an officially supported OAuth/authorization flow for a
third-party Android app to authenticate and access Antigravity?**
No. The official docs (`antigravity.google/docs/`, sitemap inspected 2026-10-16)
cover the IDE, CLI, IDE extensions, an SDK for agents running *inside*
Antigravity, MCP, skills, marketplace, plans, enterprise — no third-party
inference API and no third-party OAuth client registration exist.

**Q2. Are third-party clients allowed to use the authenticated account for
Antigravity model requests?**
No — explicitly prohibited (see Policy blocker below), with documented
enforcement (account suspensions).

**Q3. Is there a documented API, SDK, or permitted integration mechanism?**
No, for third-party consumption of Antigravity entitlement. Google directs
third-party tools to Vertex AI / AI Studio (Gemini API) keys instead — a
different product with different billing. The Gemini API "Antigravity agent" is
a managed agent billed under Gemini API quotas, not Antigravity plan
entitlement; substituting it would misrepresent the user's entitlement.

**Q4. Client registration, redirect URI, PKCE, scopes, token handling?**
Not applicable — no official third-party flow exists. The only working
community flows reuse Google's *first-party* OAuth client id + secret, the
official redirect pattern, and scopes (`cloud-platform`, `userinfo.email`,
`userinfo.profile`, `cclog`, `experimentsandconfigs`) — i.e. exactly the
client impersonation the prompt forbids.

**Q5. Restrictions on using Antigravity credentials/sessions in third-party apps?**
Yes — the prohibition is explicit and enforced (see below).

**Q6. Can Anvex do this without a separately installed adapter/proxy?**
No. Not without impersonating the first-party client, which the prompt's own
Phase 2 ("Do not … impersonate an official client") and Phase 4 ("Never embed
Google client secrets in the Android app") forbid.

**Authentication ≠ authorization.** Even a genuine Android Google sign-in
(standard OAuth scopes) would only authenticate the user; it grants zero
authorization to Antigravity's internal endpoints. Entitlement is enforced
server-side on the undocumented `v1internal` API and is only reachable with
the first-party client identity. A sign-in-first button would therefore be a
lie: it would either fail at service access or require the forbidden
impersonation. Per Phase 2's rule, a successful Google sign-in must not be
treated as proof of Antigravity access — and there is no supported way to get
from the former to the latter.

## The exact blockers

### Technical blocker

1. **No public client registration.** Google offers no OAuth client id/secret,
   no documented scopes, and no redirect-URI registration for third-party
   Antigravity access. The working community implementations all extract the
   first-party client credentials from official binaries and call the
   undocumented internal Cloud Code API (`cloudcode-pa.googleapis.com`,
   `v1internal` RPCs such as `:loadCodeAssist`, `:fetchAvailableModels`,
   `:retrieveUserQuotaSummary`).
2. **Client attestation is part of the gate.** Requests must carry the official
   CLI fingerprint (`User-Agent: antigravity/cli/… (aidev_client; …)`,
   `X-Goog-Api-Client` / `Client-Metadata` header transforms). Sending those
   from Anvex is first-party client impersonation — out of bounds.
3. **Secrets cannot live in an APK.** The prompt forbids embedding client
   secrets in the app; an APK is also trivially extractable, so even a
   "hidden" secret would be public on day one.

### Policy blocker

Google Antigravity Additional Terms of Service, clause 6 (verified live at
`https://antigravity.google/terms` on 2026-10-17):

> "…using the Service in connection with products not provided by us. Using
> third party software, tools, or services to access the Service (e.g. using
> OpenClaw with Antigravity OAuth) is a breach of this Agreement."

Breach "may be grounds for suspension or termination of your Antigravity
and/or Gemini CLI accounts." The FAQ confirms the same position, and Google
has enforced it (documented ban wave against third-party Antigravity
OAuth/proxy use, with a reinstatement-appeal process; a second violation is
described as permanent). Independent community projects reached the same
conclusion in 2026: third-party Antigravity integrations now ship explicit
ToS warnings (OpenClaw plugin NOTICE, 2026-09-23), document the risk as
unresolved (Orkestra research), or removed the integration entirely (nixfiles,
2026-08-30).

## Decision and what would reopen it

- **No sign-in-first flow is implemented.** Building one would require either a
  fake OAuth screen / mock connection (explicitly forbidden by the prompt's
  definition of done) or the forbidden impersonation. Both are refused.
- **The existing adapter route stays as the only path**, with its disclosures
  intact. It is not removed or weakened.
- **Reopen condition (unchanged from the assessment):** Google publishes a
  documented authentication + inference interface that permits third-party
  clients to use the user's Antigravity entitlement. If that happens, it slots
  in as an isolated `ProviderClient` behind the existing Keystore-backed
  credential store — no other provider is touched.
