# Zyclipra security, privacy and launch audit – 16 November 2026

## Overview
- **Project:** Zyclipra 0.1.0 – Windows desktop app (Java 21 target, JavaFX, packaged with jpackage) with a built-in HTTPS dashboard on the PC, plus a static companion website on GitHub Pages (`madaai.github.io/zyclipra-dashboard`, repo `MaDaAi/zyclipra-dashboard`) that connects to the PC over WebRTC on the same network.
- **Inspected:** source, tests, `pom.xml`, both GitHub Actions workflows, the packaged `dist/Zyclipra` folder, the live website (HTTP headers, pages, 404), and a running copy of the app with synthetic data (scratch data folder, test account).
- **Not inspected:** the owner's GitHub and Google Cloud account settings (MFA), Google's OAuth consent-screen review, independent penetration testing, and iPhone/Android browsers beyond the owner's own manual tests.
- **Owner jurisdiction:** Ontario, Canada (stated by the owner). Contact: notefirasupport@gmail.com (stated by the owner).
- **Threat model (short):**
  - *Assets:* recordings and clips, microphone/screen access, the dashboard password, the Google Drive token, and the website link key.
  - *Entry points:* the PC dashboard (HTTPS; loopback by default, home network or internet only when the owner turns them on), the WebRTC data channel, Google Drive files, and the website.
  - *Attackers:* someone on the same network; an internet attacker if outside access is on; a malicious website trying CSRF or CORS tricks; someone who obtains a stolen Google session; a supply-chain compromise.
- **Highest open risks:**
  - The exe bundles an end-of-life Java runtime (24.0.1).
  - The website can't send some security headers on GitHub Pages.
  - MFA on the owner's accounts is unverified.
  - The code is largely AI-assisted, with no independent human security review on record.

## Security scorecard (all original 70)

| Measure | Count |
|---|---:|
| **FAIL (confirmed not passed)** | **2** |
| **UNKNOWN (not verified)** | **2** |
| **FAIL + UNKNOWN (not passed or unverified)** | **4** |
| PASS (verified) | 49 |
| NOT APPLICABLE (justified) | 17 |
| **TOTAL** | **70** |

**FAILED: 2 / 70.**

Equations: 49 + 2 + 2 + 17 = 70; FAIL + UNKNOWN = 4.

Applicable-check pass rate: 49 / 53 = 92 %.

UNKNOWN means not verified, not proven vulnerable. Verified by `scripts/count_checks.py security-checks.csv`.

## All 70 checks
See the table at the end of this file (generated from `security-checks.csv`).

## Legal, privacy, accessibility, IP and platform (separate from the 70)

| Area | Status | Evidence / action |
|---|---|---|
| Privacy policy | PASS (needs legal review) | `privacy.html` on the website and the PC dashboard.<br>Matches observed behaviour: no telemetry, local storage, drive.file only, Google Limited Use statement, PIPEDA and GDPR sections.<br>Updated today to mention the local sign-in log. |
| Terms of use | PASS (needs legal review) | `terms.html`: as-is, liability limits, recording-consent duty, outside-access risk, Ontario law with mandatory consumer rights preserved. |
| EULA | PASS (needs legal review) | `eula.html`: MIT grant, what the app does, third-party components, Ontario law.<br>It isn't shown for acceptance on first launch; it relies on "by using you agree". |
| Cookie policy and consent | PASS | No cookies, analytics or ads. One session-storage flag and a service worker, both strictly necessary, so no banner is needed (EU ePrivacy / UK PECR rules on necessary storage).<br>Canada has no cookie-banner rule for this. |
| Refund policy | NOT APPLICABLE | Free product; `refunds.html` says so. |
| Form consent | PASS | Only sign-in forms. Consent sentence and links under both sign-in buttons. No marketing or opt-ins. |
| Business transparency | PASS | Developer "MaDaAi", Ontario, Canada, and contact email on every legal page and in the app.<br>No street address published; the owner decides whether one is needed. |
| CASL (anti-spam) | NOT APPLICABLE | No commercial electronic messages are sent. |
| Data minimisation and third parties | PASS | Only Google (sign-in, Drive, thumbnails) and GitHub Pages hosting. No analytics, pixels or embeds. |
| Children | PASS (policy) | Not child-directed; 13+ (or local age of consent) in the privacy policy. |
| Accessibility (WCAG 2.2 AA aim) | PARTIAL | Contrast fixed (all text 4.5:1+), real buttons, keyboard focus outlines, labels, alt text, live regions.<br>Not done: a screen-reader pass on iOS/Android, a zoom/reflow test, and an accessibility review of the desktop app. |
| Honest marketing | PASS | No reviews, ratings or "best" claims. "Open source" changed to "MIT-licensed" while the repo is private. Non-affiliation statement added. |
| Copyright: images, icons, fonts | PASS | Logo and icons are the owner's own. Emoji come from system fonts. No third-party images, music or fonts bundled. |
| Software licences | PASS | `THIRD-PARTY-NOTICES.txt` (OpenJFX GPLv2+CE, Apache-2.0, BSD WebRTC + patents, Bouncy Castle, NVIDIA header) and `LICENSE.txt` ship next to the exe and on both sites. FFmpeg isn't bundled. |
| Trademark ("Zyclipra") | UNKNOWN | No trademark search was done. Search CIPO and USPTO before promoting the name. |
| Google OAuth policy | UNKNOWN | The app is in Testing mode (test users only). A public launch needs Google verification, which needs the privacy and terms links (now present). |
| Desktop platform | PARTIAL | Credential Manager for secrets, no admin rights, no drivers, local server loopback by default.<br>Open: the exe isn't code-signed (SmartScreen warnings, no tamper evidence), there's no auto-update channel, and the bundled runtime is end of life (check 37). |

## Changes in this audit

| File | Change |
|---|---|
| `remote/DashboardAuth.java` | Logs password and website sign-ins, failed attempts and lock-outs (address and session hash only). Raises one alert per lock-out. |
| `app/AppContext.java` | Shows lock-out alerts in the app. |
| `remote/RtcBridge.java` | At most 64 in-flight requests per WebRTC connection; extra messages are dropped. |
| `.github/workflows/build.yml`, website `pages.yml` | Every action pinned to a full commit SHA. |
| `privacy.html` (website and PC dashboard) | Mentions the local sign-in log. |
| `AuthAlertTest.java` (new) | Lock-out alert fires once; a wrong website key counts toward the lock-out; other devices aren't affected. |
| `security-checks.csv`, this report, `packaging/audit_ledger.py` | The audit record. |

Rollback: each change is self-contained and can be reverted on its own; behaviour for normal use is unchanged.

**Original issues fixed and recorded:**
- **42:** sign-ins weren't logged.
- **43:** the owner wasn't alerted on lock-outs.
- **62:** CI actions were unpinned.
- **64/70:** WebRTC requests per connection weren't bounded.

## Tests actually run
- `mvnw -o package`: **69 tests, 0 failures, 9 skipped** (hardware tests).
- Live security test against the running app (`webtest.py`, synthetic key): **15/15 passed**.
  - Covers CORS preflight from the website and from another origin, wrong key, right key, the route allowlist, a missing token, a token without the origin, duplicate command ids, logout, and the token after logout.
- Live checks:
  - Hidden characters in names: 400.
  - `../etc/passwd`: 404 page.
  - Legal pages and notices served by the PC dashboard: 200.
  - Live website pages: 200; missing page: 404 "Page not found".
- Secret scan of both repos' working trees and full history: no secrets, only a docs placeholder.
- **Not run:**
  - Dependency CVE scanner (no OWASP tool available offline); dependencies were checked against Maven Central releases instead.
  - External penetration test.
  - Screen-reader testing.

## Owner actions needed
1. **Bundled runtime (check 37).** Approve downloading a current JDK (for example Eclipse Temurin 21.0.x latest or 25.0.x LTS). I'll then rebuild the exe with it.
2. **MFA (check 55).** Turn on 2-step verification for your GitHub and Google accounts.
3. **Human review (check 50).** Have someone review the auth and remote-control code before a public launch.
4. **Legal review.** Have a lawyer review the privacy policy, terms and EULA. Run a trademark search for "Zyclipra".
5. **Google verification.** Submit the Google sign-in for verification before inviting the public.
6. **Optional, check 46.** Host the website somewhere that allows headers (for example Cloudflare Pages, free) to add nosniff and frame-ancestors headers.
7. **Optional.** Code-sign the exe.

## Readiness
**CONDITIONALLY READY** for personal use and invited testers.

**NOT READY** for a public launch until:
- the bundled Java runtime is updated (check 37);
- MFA is confirmed (check 55);
- a human security review is done (check 50);
- Google OAuth verification is complete;
- a lawyer has reviewed the legal pages.

No audit can guarantee immunity from attacks or legal claims.

## All 70 checks (from security-checks.csv)

| ID | Check | Status | Severity | Evidence | Fix | Verification |
|---|---|---|---|---|---|---|
| 01 | Exposed database credentials | NOT APPLICABLE |  | No database: data lives in local files on the owner's PC and in their own Google Drive. |  | Searched source/config for DB drivers and URLs: none. |
| 02 | Public .env files | PASS |  | .gitignore ignores .env, *.key, *.p12, /private/; no .env exists; the Pages workflow copies an explicit file list only. |  | git ls-files; workflow review; live site files listed |
| 03 | Hardcoded API keys or secrets | PASS | LOW | Desktop OAuth client (private/google-oauth.json) is git-ignored and packaged in the jar; Google treats installed-app client secrets as non-confidential (PKCE protects the flow). Website ships only the public OAuth client ID. | Keep private/ out of any public repo. | git grep of working tree and full history for key patterns: no matches except a placeholder in docs |
| 04 | Weak or missing authentication | PASS |  | Dashboard: username + Argon2id password; website: 256-bit link key from the owner's Drive traded for a bearer session; sessions expire (12 h idle / 7 d). |  | AuthTest, RemoteTest, AuthAlertTest; live webtest.py 15/15 |
| 05 | Missing server-side authorization | PASS |  | All checks in DashboardServer/RtcBridge.route on the PC; website sessions limited to an allowlist of routes. |  | webtest.py: account and cloud routes 403 for website sessions |
| 06 | Cross-user data access | PASS |  | No shared backend: each PC has its own credentials and link key; each user reads only their own Drive via their own Google token (drive.file). |  | wrong key → 401; token without website origin → 401; RtcRouteTest blocked routes |
| 07 | Open database permissions | NOT APPLICABLE |  | No database: data lives in local files on the owner's PC and in their own Google Drive. |  |  |
| 08 | Misconfigured Firebase / Supabase / S3 | PASS |  | Only Google Drive (drive.file, files private to the owner, no sharing/permission calls) and GitHub Pages (public static files only). |  | grep for Drive permissions API: none |
| 09 | Unprotected admin routes | PASS |  | Account/session/password routes need a password session and the X-Zyclipra header + same-origin; never available to website sessions. |  | RemoteTest; webtest.py |
| 10 | Production debug tools exposed | PASS |  | Diagnostic flags (--self-test, -Dzyclipra.*) are local command-line only; no network debug endpoints; Zyclipra.cfg has no debug agents. |  | Zyclipra.cfg reviewed; route list reviewed |
| 11 | Build logs leaking secrets | PASS |  | CI workflows use no secrets and print none; Pages build copies static files. |  | workflow review |
| 12 | Verbose production errors | PASS |  | Unexpected errors return a generic message; details stay in the local log. |  | DashboardServer.handle catch-all; RtcBridge.route catches |
| 13 | Secrets in Git history | PASS |  | Both repos' full histories scanned for Google secrets, private keys, API keys, OAuth tokens. |  | git grep over git rev-list --all: only a docs placeholder |
| 14 | Secrets shipped in frontend JavaScript | PASS |  | Website: public client ID only; PC dashboard JS has no secrets. |  | config.js/app.js review |
| 15 | Client-side-only security checks | PASS |  | Gain 0–3, replay length, ids, sizes and permissions validated on the PC (Commands, RtcBridge). |  | CommandsTest, RtcRouteTest (e.g. 400 % rejected) |
| 16 | Missing input validation | PASS |  | Typed/length/range checks on every command; names reject control and text-direction characters; request body size limit. |  | live test: bidi/control page names → 400 |
| 17 | SQL injection | NOT APPLICABLE |  | No database: data lives in local files on the owner's PC and in their own Google Drive. |  |  |
| 18 | NoSQL injection | NOT APPLICABLE |  | No database: data lives in local files on the owner's PC and in their own Google Drive. Drive queries use quoted literals built by the app (DriveClient.lit). |  |  |
| 19 | Cross-site scripting (XSS) | PASS |  | No innerHTML/eval anywhere; all text via textContent; CSP on both dashboards. |  | grep for HTML sinks: none |
| 20 | Cross-site request forgery (CSRF) | PASS |  | Cookie sessions: SameSite=Strict + required custom header + Origin check; website uses bearer tokens (not ambient). |  | RemoteTest; webtest.py other-origin → 403 |
| 21 | Insecure file uploads | NOT APPLICABLE |  | No remote upload endpoint; sounds are imported by the owner from local files in the desktop app. |  |  |
| 22 | Path traversal | PASS |  | Static files restricted to [a-z0-9_-]+.ext from the jar; clips served by library id (validated), never by path. |  | live: ../etc/passwd → 404; LibraryTest invalid names |
| 23 | Server-side request forgery (SSRF) | PASS |  | The PC only fetches Google APIs and the owner-entered public URL (desktop UI only); the website only sends the link key to private/loopback HTTPS URLs. |  | app.js privateUrl(); route review |
| 24 | Broken password-reset flows | NOT APPLICABLE |  | No remote reset: the password can only be changed in the desktop app or with the current password. |  |  |
| 25 | Weak session management | PASS |  | 256-bit random tokens, idle/absolute expiry, logout, revoke one/all, password change signs out other sessions, link reset signs out website sessions. |  | AuthTest; DashboardAuth.changePassword |
| 26 | Weak or incorrectly validated JWTs | NOT APPLICABLE |  | No JWTs are issued or trusted; Google access tokens are used as opaque bearer tokens against Google APIs. |  |  |
| 27 | Overly permissive CORS | PASS |  | CORS only for the exact configured origin, only with website controls on; GET/POST and three headers. |  | webtest.py preflight tests |
| 28 | Missing rate limits | PASS |  | Login and website-key lock-outs (per address + global), sound triggers 10/5 s, flood guard 3000 req/min per address. |  | AuthAlertTest; CommandsTest |
| 29 | Unprotected staging or test environments | NOT APPLICABLE |  | No staging environment; tests use synthetic data in temporary folders. |  |  |
| 30 | Default credentials left unchanged | PASS |  | No default accounts: the dashboard won't start until the owner creates credentials. |  | DashboardServer.start requires configured() |
| 31 | Webhook signatures not verified | NOT APPLICABLE |  | Zyclipra is free: no payments, purchases, subscriptions or webhooks. |  |  |
| 32 | Frontend-only payment checks | NOT APPLICABLE |  | Zyclipra is free: no payments, purchases, subscriptions or webhooks. |  |  |
| 33 | IDOR / BOLA | PASS |  | Single-owner PC: every object is the owner's; ids are validated and resolved server-side; commands deduplicated per caller. |  | RtcRouteTest; CommandsTest |
| 34 | APIs trusting user-controlled roles or IDs | PASS |  | Caller identity comes from the server-side session; no role/owner fields are accepted. |  | route review |
| 35 | Sensitive data in logs | PASS |  | Logs hold no tokens, passwords or emails; sign-in logs record time, address and a session hash only. |  | grep of log statements |
| 36 | Sensitive source maps or build artifacts | PASS |  | No source maps; the website deploys an explicit file list; the jar contains no private data beyond the OAuth client (see 03). |  | tsconfig, workflow and jar listing |
| 37 | Vulnerable or abandoned dependencies | FAIL | MEDIUM | The exe bundles Java runtime 24.0.1, which no longer gets security updates (Java 24 is end of life). Libraries were upgraded (BouncyCastle 1.86, JNA 5.19.1, Gson 2.15.0); JavaFX 23.0.2 kept. | Rebuild the exe with a current LTS JDK (21.0.x latest or 25.0.x) – needs a JDK download (owner approval). | runtime/release file: JAVA_VERSION="24.0.1" |
| 38 | Malicious or compromised packages | PASS | LOW | All from Maven Central, exact versions, established publishers, releases at least 2 weeks old; webrtc-java ships a native DLL from a single maintainer. | Consider verifying artifact signatures (PGP) in CI. | pom review; Maven checksum validation |
| 39 | Prompt injection | NOT APPLICABLE |  | Zyclipra has no AI/LLM features, prompts, retrieval or agents. |  |  |
| 40 | AI tools bypassing user permissions | NOT APPLICABLE |  | Zyclipra has no AI/LLM features, prompts, retrieval or agents. |  |  |
| 41 | Excess database privileges | NOT APPLICABLE |  | No database: data lives in local files on the owner's PC and in their own Google Drive. |  |  |
| 42 | Missing audit logs | PASS |  | Fixed: dashboard password/website sign-ins, failed attempts and lock-outs are logged (address, session hash). |  | AuthAlertTest; live log lines observed |
| 43 | No security monitoring or alerts | PASS |  | Fixed: lock-outs (per address or global) raise an in-app alert telling the owner to change the password. |  | AuthAlertTest (one alert per lock-out) |
| 44 | No tested backup / restore plan | PASS |  | Settings are written atomically with damaged-file recovery; clips can be backed up to Google Drive and restored (real upload and download verified earlier). |  | CoreTest; CloudSyncTest; real Drive round trip (Round 11) |
| 45 | Public internal dashboards | PASS |  | The only dashboard is the authenticated PC dashboard; off by default; this PC only unless the owner opens it. |  | RemoteTest loopback default |
| 46 | Missing security headers | FAIL | LOW | PC dashboard sends CSP, HSTS, nosniff, X-Frame-Options, Referrer-Policy, COOP. GitHub Pages can't send custom headers: the website has a meta CSP, a JS frame guard and github.io's HSTS, but no nosniff or frame-ancestors header. | Accept, or move the website to a host that allows headers (e.g. Cloudflare Pages, free). | curl -I of the live site |
| 47 | Unsafe cookie settings | PASS |  | zs cookie: HttpOnly; Secure; SameSite=Strict; Path=/; 7-day max age. The website sets no cookies. |  | RemoteTest cookie flags |
| 48 | Sensitive data unprotected in transit / at rest | PASS | LOW | HTTPS/TLS 1.2–1.3, WebRTC DTLS; tokens in Windows Credential Manager; passwords Argon2id. The website link key sits in a private Drive file (by design). |  | code review; webtest.py over TLS |
| 49 | Poor tenant isolation | PASS |  | No multi-tenant backend; each install and Google account is separate. |  | architecture review |
| 50 | Over-trusting AI-generated code | UNKNOWN | MEDIUM | Much of the code, including auth, was written with AI assistance. It has tests (69) and was reviewed in this audit, but there's no independent human security review on record. | Have a person review DashboardAuth, DashboardServer, RtcBridge, WebsiteLink and GoogleAuth before a public launch. | Not verifiable from the repo |
| 51 | Mass assignment / over-posting | PASS |  | Dashboard settings changes allowlist 4 booleans; everything else is refused. |  | AppContext.cloudSettings |
| 52 | Command / OS injection | PASS |  | All processes start via ProcessBuilder argument lists (FFmpeg, explorer, control); no shell. |  | grep ProcessBuilder/exec |
| 53 | Unsafe deserialization | PASS |  | Gson into typed classes only; no Java object serialization. |  | grep ObjectInputStream: none |
| 54 | Misconfigured OAuth / OIDC / social login | PASS |  | Desktop: PKCE S256, state check, loopback redirect, drive.file + profile scopes, refresh token in Credential Manager. Website: Google Identity Services token model, token in memory, drive.file scope checked. |  | GoogleAuth review; real sign-ins verified |
| 55 | No MFA on privileged accounts | UNKNOWN | MEDIUM | Can't verify MFA on the owner's GitHub and Google (Cloud console) accounts, which control the website and OAuth clients. | Turn on 2-step verification for GitHub and the Google account. | Owner to confirm |
| 56 | Account enumeration | PASS |  | One message for wrong username or password; dummy hash keeps timing equal. |  | DashboardAuth.login |
| 57 | Business-logic abuse | PASS |  | Limits enforced on the PC: gain ≤ 300 %, replay 15–300 s, sound rate limit, idempotent commands. |  | CommandsTest; SoundboardGainTest |
| 58 | Race conditions | PASS |  | Command dedupe is synchronized; settings writes atomic; recording start/stop idempotent. |  | CommandsTest |
| 59 | Webhook replay / duplicate processing | NOT APPLICABLE |  | Zyclipra is free: no payments, purchases, subscriptions or webhooks. (Command replay is covered by command ids, see 58.) |  |  |
| 60 | Overpowered CI/CD credentials | PASS |  | App CI: contents: read. Website: contents read, pages write, id-token write (required for Pages); no stored secrets. |  | workflow review |
| 61 | Untrusted build actions or scripts | PASS |  | Only official actions/* actions; no third-party actions or install scripts. |  | workflow review |
| 62 | Unpinned build dependencies | PASS |  | Fixed: all GitHub Actions pinned to full commit SHAs; Maven dependencies use exact versions. |  | workflow files after change |
| 63 | Security checks fail open | PASS |  | Connections denied unless allowed; missing auth → 401; errors → error codes; website features off unless the key hash exists. |  | RemoteTest; webtest.py |
| 64 | Missing resource limits | PASS |  | Request body limit, flood guard, sound rate limit, WebRTC 64 KB messages, 4 peers, 64 in-flight requests per connection (added), 36 KB clip parts. |  | RtcRouteTest; code review |
| 65 | AI sensitive-information disclosure | NOT APPLICABLE |  | Zyclipra has no AI/LLM features, prompts, retrieval or agents. |  |  |
| 66 | Unsafe use of AI output | NOT APPLICABLE |  | Zyclipra has no AI/LLM features, prompts, retrieval or agents. |  |  |
| 67 | AI agents have excessive agency | NOT APPLICABLE |  | Zyclipra has no AI/LLM features, prompts, retrieval or agents. |  |  |
| 68 | Sensitive browser storage | PASS |  | Nothing sensitive persisted: tokens in memory only; one sessionStorage flag; no localStorage/IndexedDB. |  | grep localStorage/indexedDB |
| 69 | Open redirects | PASS |  | No redirect parameters anywhere; OAuth redirect is a fixed loopback URL. |  | route review |
| 70 | Unsecured GraphQL / WebSocket / realtime endpoints | PASS |  | WebRTC: offer must carry the link key and be < 2 min old; DTLS fingerprints exchanged via the owner's Drive; every message authorized by RtcBridge.route; size and in-flight caps. |  | RtcRouteTest; Chromium interop test |
