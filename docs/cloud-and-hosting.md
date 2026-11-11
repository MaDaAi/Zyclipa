# Google Drive, the dashboard and hosting

Everything here is optional. Recording, instant replay, screenshots, the clip library, the mixer and
the soundboard all work offline without any of it.

## Where your clips are

- **On your PC – the real library.** The folder in *Settings › Capture and Sound* (by default
  `C:\Users\<you>\Videos\Zyclipra`). Recording, instant replay and screenshots always save here first.
- **On Google Drive – backup copies** (only if you connect it): *My Drive › Zyclipra › Clips*,
  *Recordings* and *Screenshots*. Instant replays, trims and imports go in *Clips*.

Uploading never removes anything from your PC unless you turn off *Keep clips on this PC after
uploading* (Settings › Cloud & Sync). Zyclipra asks before you turn it off. Even then, a clip only
goes to the Recycle Bin after Google Drive confirms the same size and MD5 checksum as the file on
your PC. Clips that aren't uploaded are never touched.

## Connecting Google Drive

1. *Settings › Cloud & Sync* in Zyclipra (or *Cloud & Sync* on the dashboard) › **Sign in with Google**.
2. Google opens in your browser on the PC. Choose your account and allow access.
3. The tab says Zyclipra is connected, and your name, email and picture appear in Zyclipra.

That's all – there's nothing to set up in Google Cloud.

What Zyclipra asks Google for:

| Scope | Why |
|---|---|
| `drive.file` | create and see **only the files Zyclipra made** – never the rest of your Drive |
| `openid email profile` | show your name, email and picture in Zyclipra |

How it's protected:

- Sign-in uses Google's official OAuth 2.0 flow for installed apps: PKCE (S256), your own browser,
  and a one-time redirect to `http://127.0.0.1:<random port>`.
- Your Google password never reaches Zyclipra.
- The refresh token is stored in **Windows Credential Manager** (`Zyclipra/google-drive`). Access
  tokens are only kept in memory.
- Tokens are never written to settings files or logs, and never sent to the dashboard.
- On the dashboard, **Sign in with Google** opens the Google window in the browser **on the PC**,
  because Google sends the result back to the PC.

### For developers: Zyclipra's OAuth client

Builds include Zyclipra's own Google OAuth client ("Desktop app" type) from
`app/private/google-oauth.json`. That file is listed in `.gitignore` and is never committed, so a
fresh clone (or CI) builds without Google sign-in. To enable it in your own build, create that
file:

```json
{ "client_id": "....apps.googleusercontent.com", "client_secret": "GOCSPX-..." }
```

Google's downloaded `client_secret_*.json` (with an `"installed"` section) works too. You can also
set the `ZYCLIPRA_GOOGLE_CLIENT_ID` and `ZYCLIPRA_GOOGLE_CLIENT_SECRET` environment variables. Google
documents that a desktop app's client details can't stay secret once the app is shipped; security
comes from PKCE, the loopback redirect and each user's consent. Keeping the file out of git just
avoids publishing it.

The client's consent screen decides who can sign in:

- **Testing:** only the listed test users, and Google signs Zyclipra out every 7 days (**Sign in
  with Google again**).
- **In production:** anyone, with no weekly sign-out. `drive.file` and the profile scopes don't
  need Google's app verification.

## How syncing works

- **Automatic upload** (off by default): new clips are queued as soon as they're saved, including
  clips made from the dashboard's **Clip it** button. You choose clips, recordings and screenshots
  separately. Older clips stay local unless you upload them yourself (clip menu › *Upload to Google
  Drive*, the editor's *Google Drive* panel, or the dashboard player).
- Each clip has a state: **Local only → Queued → Uploading → Synced**, or **Failed** (with the
  reason), or **Cloud file unavailable** if you deleted it in Google Drive.
- Uploads are resumable in 2 MB chunks. If the network drops or Zyclipra closes, the upload
  continues where it stopped – even after a restart.
- At most two uploads run at once. Rate limits and server errors wait and retry (5 s, 10 s, 20 s…,
  up to 6 tries), then show **Failed**.
- Every uploaded file is tagged with the clip's id. A retry or a reinstall finds the earlier upload
  instead of making a duplicate, and folders are reused rather than created twice.
- The queue is saved in `%APPDATA%\Zyclipra\sync-queue.json`, so it survives restarts. If you
  aren't connected, clips wait in the queue until you are.

### Recovering from failed uploads

- *Settings › Cloud & Sync › Sync status* (or the dashboard's *Cloud & Sync* tab) lists each
  failed clip with the reason. Press **Retry**, or **Retry failed uploads**.
- "Your Google Drive is full": free space in Drive, then retry.
- "Google needs you to connect again": the sign-in was revoked or expired. Press **Reconnect**.
  Queued clips continue afterwards.
- "The clip's file isn't on this PC anymore": the file was moved or deleted. Put it back, or
  **Cancel** the upload.
- **Check cloud copies** looks for clips you deleted in Google Drive and marks them *Cloud file unavailable*.

### Downloading, deleting and disconnecting

- Clips that are only on Google Drive are listed under *Sync status* with **Download to this PC**.
  An interrupted download continues.
- **Delete cloud copy** (editor › Google Drive) asks first and removes only the Drive copy.
- **Disconnect** revokes Zyclipra's access at Google and forgets the token on this PC. It does not
  delete anything on Google Drive or your PC. The queue is kept for when you reconnect.

## The dashboard

The dashboard is a web page served by Zyclipra on your PC. It lets you clip, record, take
screenshots, browse and download clips, see Google Drive status and use the mixer and soundboard.
It's **off** until you start it.

### First-time setup: username and password

*Settings › Hosting › Dashboard sign-in*: choose a username (3–32 letters, digits, `.`, `_`, `-`)
and a password (at least 8 characters, or 12 for public access). There is no default account, and
the dashboard can't be started before this is done. Then press **Start dashboard**.

- The password is stored only as an Argon2id hash (19 MiB memory, 2 passes).
- Sign-in sessions use a random token in a `Secure; HttpOnly; SameSite=Strict` cookie, never
  `localStorage`. They end after 12 hours unused, 7 days at most, on sign-out, or when you change
  the password (which signs out every other browser).
- Five wrong passwords from one address lock it out for a while (growing up to an hour). Lots of
  failures from many addresses lock sign-in for everyone for 5 minutes.
- Every API call needs a session. Changes also need a same-origin request with a custom header,
  which blocks cross-site attacks.
- You can change the username or password, and see and sign out sessions, in the app
  (*Settings › Hosting*) or on the dashboard (*Account*).

### On this PC

Open **https://127.0.0.1:47820** (the port can be changed; a port already used by another program
is reported). Your browser warns once because the certificate is made on your PC. Check that the
fingerprint shown in *Settings › Hosting* matches, then continue.

### On your home network

*Settings › Hosting › Devices on my home network*. The page then shows your PC's network address
(e.g. `https://192.168.1.20:47820`) for your phone on the same Wi-Fi. Windows may ask whether to
allow Java on private networks the first time.

### Away from home: Tailscale (recommended)

Tailscale puts your phone and PC on a private, encrypted network that works from anywhere. Your
dashboard never faces the internet and no router setup is needed.

1. Install Tailscale on this PC and on your phone (<https://tailscale.com/download>, free for
   personal use) and sign in to the same account on both.
2. In Zyclipra, turn on *Settings › Hosting › Private VPN – Tailscale*. The page shows the
   Tailscale address (`https://100.x.y.z:47820`).
3. Open that address on your phone and sign in.

Zyclipra doesn't install or configure Tailscale for you.

### Who can connect

Connections are accepted only from:

- this PC,
- home-network ranges when that switch is on (10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA),
- Tailscale ranges when that switch is on (100.64/10, fd7a:115c:a1e0::/48).

Anything else is refused. Zyclipra never opens router ports and never uses UPnP or NAT-PMP. It
never changes Windows Firewall either.

### Advanced: public internet (not recommended)

This puts your recording controls and clips where anyone on the internet can try to sign in. Use
Tailscale instead unless you know how to run a server. Address filtering alone doesn't make public
hosting safe, so Zyclipra requires all of the following. You set up every network piece yourself:

1. A domain name pointing at your home IP (dynamic DNS is fine).
2. An HTTPS reverse proxy on this PC with a **real** certificate (e.g. Caddy with Let's Encrypt).
   It forwards to Zyclipra's local HTTPS address and keeps the `Host` header. Save the dashboard's
   certificate (*Settings › Hosting › Save dashboard certificate*) so the proxy can trust it.
   Example Caddyfile:

   ```
   clips.example.com {
       reverse_proxy https://localhost:47820 {
           transport http {
               tls_trust_pool file C:/path/to/zyclipra-dashboard.pem
           }
       }
   }
   ```
3. Port 443 forwarded on your router to this PC, and allowed in Windows Firewall.
4. A dashboard password of at least 12 characters.
5. In *Settings › Hosting › Advanced*, enter `https://clips.example.com` and your dashboard
   password, then press **Check address and turn on**. Zyclipra connects to that address with
   normal certificate checking and confirms it reaches *this* running app before switching on.

While public access is on, Zyclipra listens only on this PC (for the proxy), and the home-network
and VPN switches are off. Sign-in rate limiting uses the proxy's `X-Forwarded-For`. Turn it off any
time with **Turn public access off**.

**External hosting** (the dashboard on a website instead of your PC) isn't available. It would need
an authenticated relay between that website and your PC, and Zyclipra won't expose its controls
without one.

## Troubleshooting sign-in

| Problem | What to do |
|---|---|
| Dashboard: "Finish setting up the dashboard in the Zyclipra app first" | Create the username and password in *Settings › Hosting*. |
| Dashboard: "Too many attempts" | Wait a few minutes (it lifts by itself), or restart Zyclipra. |
| Dashboard: forgot the password | In the Zyclipra app, *Settings › Hosting* – change it with the current password. If you don't know it, close Zyclipra, delete the `username` and `passwordHash` values under `remote` in `%APPDATA%\Zyclipra\settings.json`, and create a new sign-in. |
| Browser certificate warning | Expected for the PC's own certificate; check the fingerprint. |
| Google: "access blocked" / "app not verified" | While the app is in Testing mode only listed test users can sign in; on "Google hasn't verified this app" choose *Continue*. |
| Google: `invalid_client` | Zyclipra's OAuth client was deleted or changed in Google Cloud – rebuild with the current `private/google-oauth.json`. |
| Google: "Google didn't return a refresh token" | Remove Zyclipra at myaccount.google.com › Security › Third-party access, then sign in again. |
| Google: "Sign in with Google again" every week | The OAuth consent screen is in Testing mode – see above. |
