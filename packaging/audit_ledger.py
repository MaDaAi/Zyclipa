"""Writes security-checks.csv and docs/security-audit-2026-11-16.md from one table (security audit, 2026-11-16)."""
import csv, pathlib, collections

ROOT = pathlib.Path(__file__).resolve().parent.parent
P, F, U, N = 'PASS', 'FAIL', 'UNKNOWN', 'NOT APPLICABLE'
NA_DB = 'No database: data lives in local files on the owner\'s PC and in their own Google Drive.'
NA_AI = 'Zyclipa has no AI/LLM features, prompts, retrieval or agents.'
NA_PAY = 'Zyclipa is free: no payments, purchases, subscriptions or webhooks.'

rows = [
 ('01', 'Exposed database credentials', N, '', NA_DB, '', 'Searched source/config for DB drivers and URLs: none.'),
 ('02', 'Public .env files', P, '', '.gitignore ignores .env, *.key, *.p12, /private/; no .env exists; the Pages workflow copies an explicit file list only.', '', 'git ls-files; workflow review; live site files listed'),
 ('03', 'Hardcoded API keys or secrets', P, 'LOW', 'Desktop OAuth client (private/google-oauth.json) is git-ignored and packaged in the jar; Google treats installed-app client secrets as non-confidential (PKCE protects the flow). Website ships only the public OAuth client ID.', 'Keep private/ out of any public repo.', 'git grep of working tree and full history for key patterns: no matches except a placeholder in docs'),
 ('04', 'Weak or missing authentication', P, '', 'Dashboard: username + Argon2id password; website: 256-bit link key from the owner\'s Drive traded for a bearer session; sessions expire (12 h idle / 7 d).', '', 'AuthTest, RemoteTest, AuthAlertTest; live webtest.py 15/15'),
 ('05', 'Missing server-side authorization', P, '', 'All checks in DashboardServer/RtcBridge.route on the PC; website sessions limited to an allowlist of routes.', '', 'webtest.py: account and cloud routes 403 for website sessions'),
 ('06', 'Cross-user data access', P, '', 'No shared backend: each PC has its own credentials and link key; each user reads only their own Drive via their own Google token (drive.file).', '', 'wrong key → 401; token without website origin → 401; RtcRouteTest blocked routes'),
 ('07', 'Open database permissions', N, '', NA_DB, '', ''),
 ('08', 'Misconfigured Firebase / Supabase / S3', P, '', 'Only Google Drive (drive.file, files private to the owner, no sharing/permission calls) and GitHub Pages (public static files only).', '', 'grep for Drive permissions API: none'),
 ('09', 'Unprotected admin routes', P, '', 'Account/session/password routes need a password session and the X-Zyclipa header + same-origin; never available to website sessions.', '', 'RemoteTest; webtest.py'),
 ('10', 'Production debug tools exposed', P, '', 'Diagnostic flags (--self-test, -Dzyclipa.*) are local command-line only; no network debug endpoints; Zyclipa.cfg has no debug agents.', '', 'Zyclipa.cfg reviewed; route list reviewed'),
 ('11', 'Build logs leaking secrets', P, '', 'CI workflows use no secrets and print none; Pages build copies static files.', '', 'workflow review'),
 ('12', 'Verbose production errors', P, '', 'Unexpected errors return a generic message; details stay in the local log.', '', 'DashboardServer.handle catch-all; RtcBridge.route catches'),
 ('13', 'Secrets in Git history', P, '', 'Both repos\' full histories scanned for Google secrets, private keys, API keys, OAuth tokens.', '', 'git grep over git rev-list --all: only a docs placeholder'),
 ('14', 'Secrets shipped in frontend JavaScript', P, '', 'Website: public client ID only; PC dashboard JS has no secrets.', '', 'config.js/app.js review'),
 ('15', 'Client-side-only security checks', P, '', 'Gain 0–3, replay length, ids, sizes and permissions validated on the PC (Commands, RtcBridge).', '', 'CommandsTest, RtcRouteTest (e.g. 400 % rejected)'),
 ('16', 'Missing input validation', P, '', 'Typed/length/range checks on every command; names reject control and text-direction characters; request body size limit.', '', 'live test: bidi/control page names → 400'),
 ('17', 'SQL injection', N, '', NA_DB, '', ''),
 ('18', 'NoSQL injection', N, '', NA_DB + ' Drive queries use quoted literals built by the app (DriveClient.lit).', '', ''),
 ('19', 'Cross-site scripting (XSS)', P, '', 'No innerHTML/eval anywhere; all text via textContent; CSP on both dashboards.', '', 'grep for HTML sinks: none'),
 ('20', 'Cross-site request forgery (CSRF)', P, '', 'Cookie sessions: SameSite=Strict + required custom header + Origin check; website uses bearer tokens (not ambient).', '', 'RemoteTest; webtest.py other-origin → 403'),
 ('21', 'Insecure file uploads', N, '', 'No remote upload endpoint; sounds are imported by the owner from local files in the desktop app.', '', ''),
 ('22', 'Path traversal', P, '', 'Static files restricted to [a-z0-9_-]+.ext from the jar; clips served by library id (validated), never by path.', '', 'live: ../etc/passwd → 404; LibraryTest invalid names'),
 ('23', 'Server-side request forgery (SSRF)', P, '', 'The PC only fetches Google APIs and the owner-entered public URL (desktop UI only); the website only sends the link key to private/loopback HTTPS URLs.', '', 'app.js privateUrl(); route review'),
 ('24', 'Broken password-reset flows', N, '', 'No remote reset: the password can only be changed in the desktop app or with the current password.', '', ''),
 ('25', 'Weak session management', P, '', '256-bit random tokens, idle/absolute expiry, logout, revoke one/all, password change signs out other sessions, link reset signs out website sessions.', '', 'AuthTest; DashboardAuth.changePassword'),
 ('26', 'Weak or incorrectly validated JWTs', N, '', 'No JWTs are issued or trusted; Google access tokens are used as opaque bearer tokens against Google APIs.', '', ''),
 ('27', 'Overly permissive CORS', P, '', 'CORS only for the exact configured origin, only with website controls on; GET/POST and three headers.', '', 'webtest.py preflight tests'),
 ('28', 'Missing rate limits', P, '', 'Login and website-key lock-outs (per address + global), sound triggers 10/5 s, flood guard 3000 req/min per address.', '', 'AuthAlertTest; CommandsTest'),
 ('29', 'Unprotected staging or test environments', N, '', 'No staging environment; tests use synthetic data in temporary folders.', '', ''),
 ('30', 'Default credentials left unchanged', P, '', 'No default accounts: the dashboard won\'t start until the owner creates credentials.', '', 'DashboardServer.start requires configured()'),
 ('31', 'Webhook signatures not verified', N, '', NA_PAY, '', ''),
 ('32', 'Frontend-only payment checks', N, '', NA_PAY, '', ''),
 ('33', 'IDOR / BOLA', P, '', 'Single-owner PC: every object is the owner\'s; ids are validated and resolved server-side; commands deduplicated per caller.', '', 'RtcRouteTest; CommandsTest'),
 ('34', 'APIs trusting user-controlled roles or IDs', P, '', 'Caller identity comes from the server-side session; no role/owner fields are accepted.', '', 'route review'),
 ('35', 'Sensitive data in logs', P, '', 'Logs hold no tokens, passwords or emails; sign-in logs record time, address and a session hash only.', '', 'grep of log statements'),
 ('36', 'Sensitive source maps or build artifacts', P, '', 'No source maps; the website deploys an explicit file list; the jar contains no private data beyond the OAuth client (see 03).', '', 'tsconfig, workflow and jar listing'),
 ('37', 'Vulnerable or abandoned dependencies', P, '', 'Fixed: the exe now bundles Eclipse Temurin 21.0.13 LTS (Oct 2026 security update, SHA-256 verified) instead of end-of-life Java 24.0.1. Libraries upgraded (BouncyCastle 1.86, JNA 5.19.1, Gson 2.15.0); JavaFX 23.0.2 kept (no known applicable issue).', 'Keep the JDK on the latest 21.0.x update each quarter and rebuild.', '69 tests on 21.0.13; new exe ran, 15/15 dashboard security checks, WebRTC interop OK; runtime/release = 21.0.13'),
 ('38', 'Malicious or compromised packages', P, 'LOW', 'All from Maven Central, exact versions, established publishers, releases at least 2 weeks old; webrtc-java ships a native DLL from a single maintainer.', 'Consider verifying artifact signatures (PGP) in CI.', 'pom review; Maven checksum validation'),
 ('39', 'Prompt injection', N, '', NA_AI, '', ''),
 ('40', 'AI tools bypassing user permissions', N, '', NA_AI, '', ''),
 ('41', 'Excess database privileges', N, '', NA_DB, '', ''),
 ('42', 'Missing audit logs', P, '', 'Fixed: dashboard password/website sign-ins, failed attempts and lock-outs are logged (address, session hash).', '', 'AuthAlertTest; live log lines observed'),
 ('43', 'No security monitoring or alerts', P, '', 'Fixed: lock-outs (per address or global) raise an in-app alert telling the owner to change the password.', '', 'AuthAlertTest (one alert per lock-out)'),
 ('44', 'No tested backup / restore plan', P, '', 'Settings are written atomically with damaged-file recovery; clips can be backed up to Google Drive and restored (real upload and download verified earlier).', '', 'CoreTest; CloudSyncTest; real Drive round trip (Round 11)'),
 ('45', 'Public internal dashboards', P, '', 'The only dashboard is the authenticated PC dashboard; off by default; this PC only unless the owner opens it.', '', 'RemoteTest loopback default'),
 ('46', 'Missing security headers', F, 'LOW', 'PC dashboard sends CSP, HSTS, nosniff, X-Frame-Options, Referrer-Policy, COOP. GitHub Pages can\'t send custom headers: the website has a meta CSP, a JS frame guard and github.io\'s HSTS, but no nosniff or frame-ancestors header.', 'Accept, or move the website to a host that allows headers (e.g. Cloudflare Pages, free).', 'curl -I of the live site'),
 ('47', 'Unsafe cookie settings', P, '', 'zs cookie: HttpOnly; Secure; SameSite=Strict; Path=/; 7-day max age. The website sets no cookies.', '', 'RemoteTest cookie flags'),
 ('48', 'Sensitive data unprotected in transit / at rest', P, 'LOW', 'HTTPS/TLS 1.2–1.3, WebRTC DTLS; tokens in Windows Credential Manager; passwords Argon2id. The website link key sits in a private Drive file (by design).', '', 'code review; webtest.py over TLS'),
 ('49', 'Poor tenant isolation', P, '', 'No multi-tenant backend; each install and Google account is separate.', '', 'architecture review'),
 ('50', 'Over-trusting AI-generated code', U, 'MEDIUM', 'Much of the code, including auth, was written with AI assistance. It has tests (69) and was reviewed in this audit, but there\'s no independent human security review on record.', 'Have a person review DashboardAuth, DashboardServer, RtcBridge, WebsiteLink and GoogleAuth before a public launch.', 'Not verifiable from the repo'),
 ('51', 'Mass assignment / over-posting', P, '', 'Dashboard settings changes allowlist 4 booleans; everything else is refused.', '', 'AppContext.cloudSettings'),
 ('52', 'Command / OS injection', P, '', 'All processes start via ProcessBuilder argument lists (FFmpeg, explorer, control); no shell.', '', 'grep ProcessBuilder/exec'),
 ('53', 'Unsafe deserialization', P, '', 'Gson into typed classes only; no Java object serialization.', '', 'grep ObjectInputStream: none'),
 ('54', 'Misconfigured OAuth / OIDC / social login', P, '', 'Desktop: PKCE S256, state check, loopback redirect, drive.file + profile scopes, refresh token in Credential Manager. Website: Google Identity Services token model, token in memory, drive.file scope checked.', '', 'GoogleAuth review; real sign-ins verified'),
 ('55', 'No MFA on privileged accounts', U, 'MEDIUM', 'Can\'t verify MFA on the owner\'s GitHub and Google (Cloud console) accounts, which control the website and OAuth clients.', 'Turn on 2-step verification for GitHub and the Google account.', 'Owner to confirm'),
 ('56', 'Account enumeration', P, '', 'One message for wrong username or password; dummy hash keeps timing equal.', '', 'DashboardAuth.login'),
 ('57', 'Business-logic abuse', P, '', 'Limits enforced on the PC: gain ≤ 300 %, replay 15–300 s, sound rate limit, idempotent commands.', '', 'CommandsTest; SoundboardGainTest'),
 ('58', 'Race conditions', P, '', 'Command dedupe is synchronized; settings writes atomic; recording start/stop idempotent.', '', 'CommandsTest'),
 ('59', 'Webhook replay / duplicate processing', N, '', NA_PAY + ' (Command replay is covered by command ids, see 58.)', '', ''),
 ('60', 'Overpowered CI/CD credentials', P, '', 'App CI: contents: read. Website: contents read, pages write, id-token write (required for Pages); no stored secrets.', '', 'workflow review'),
 ('61', 'Untrusted build actions or scripts', P, '', 'Only official actions/* actions; no third-party actions or install scripts.', '', 'workflow review'),
 ('62', 'Unpinned build dependencies', P, '', 'Fixed: all GitHub Actions pinned to full commit SHAs; Maven dependencies use exact versions.', '', 'workflow files after change'),
 ('63', 'Security checks fail open', P, '', 'Connections denied unless allowed; missing auth → 401; errors → error codes; website features off unless the key hash exists.', '', 'RemoteTest; webtest.py'),
 ('64', 'Missing resource limits', P, '', 'Request body limit, flood guard, sound rate limit, WebRTC 64 KB messages, 4 peers, 64 in-flight requests per connection (added), 36 KB clip parts.', '', 'RtcRouteTest; code review'),
 ('65', 'AI sensitive-information disclosure', N, '', NA_AI, '', ''),
 ('66', 'Unsafe use of AI output', N, '', NA_AI, '', ''),
 ('67', 'AI agents have excessive agency', N, '', NA_AI, '', ''),
 ('68', 'Sensitive browser storage', P, '', 'Nothing sensitive persisted: tokens in memory only; one sessionStorage flag; no localStorage/IndexedDB.', '', 'grep localStorage/indexedDB'),
 ('69', 'Open redirects', P, '', 'No redirect parameters anywhere; OAuth redirect is a fixed loopback URL.', '', 'route review'),
 ('70', 'Unsecured GraphQL / WebSocket / realtime endpoints', P, '', 'WebRTC: offer must carry the link key and be < 2 min old; DTLS fingerprints exchanged via the owner\'s Drive; every message authorized by RtcBridge.route; size and in-flight caps.', '', 'RtcRouteTest; Chromium interop test'),
]
assert len(rows) == 70 and [r[0] for r in rows] == [f'{i:02d}' for i in range(1, 71)]
with open(ROOT / 'security-checks.csv', 'w', newline='', encoding='utf-8') as f:
    w = csv.writer(f)
    w.writerow(['id', 'name', 'status', 'severity', 'evidence', 'fix', 'verification'])
    w.writerows(rows)
c = collections.Counter(r[2] for r in rows)
print({k: c[k] for k in (P, F, U, N)})
