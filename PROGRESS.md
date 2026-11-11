# Zyclipra – development progress

_Last updated: 2026-11-09 · version 0.1.0_

## Round 10 (2026-11-11): Google Drive sync, dashboard sign-in, hosting modes

**Built**
- *Dashboard sign-in*: username + password created in Settings › Hosting (no default account; the dashboard can't start without it). Argon2id hashes (Bouncy Castle), HttpOnly/Secure/SameSite=Strict session cookie (12 h idle, 7 days max), per-address and global rate limits, session list and revoke, password change needs the current password and signs out other sessions. The pairing code was removed.
- *Hosting*: this PC only by default. Home network and private VPN (Tailscale ranges) are separate switches. Public mode only listens on loopback behind the owner's own HTTPS reverse proxy, needs a 12+ character password, and is switched on only after Zyclipra reaches itself through the public https:// address with a trusted certificate. The page also has port-conflict reporting, Start/Stop/Restart, Copy/Open address, and a certificate export for the proxy. External hosting is documented as not available.
- *Google sign-in*: PKCE loopback flow in the system browser. Scopes `openid email profile drive.file`. Name, email and picture are shown. A revoked or expired sign-in shows "Reconnect". The refresh token lives in Windows Credential Manager (`Zyclipra/google-drive`, own JNA binding), and the old DPAPI file is migrated.
- *Drive sync*:
  - Folders Zyclipra/Clips, Recordings and Screenshots.
  - A persistent queue (`sync-queue.json`) with states local/queued/uploading/synced/failed/unavailable.
  - Two uploads at once, exponential backoff, resumable sessions that survive restarts.
  - Completion verified by size + MD5. Files are tagged with `appProperties.zyclipraId`, so retries never duplicate.
  - Automatic upload per kind, cancel, retry, cloud-copy check, delete cloud copy (with confirmation), download back, and disconnect (revokes; keeps files and queue).
  - Optional "free up space" moves verified uploads to the Recycle Bin, off by default and confirmed.
- *Desktop UI*: Settings › Cloud & Sync and Settings › Hosting pages. A Google Drive box in the clip editor; clip menu shows the sync state.
- *Web dashboard*:
  - Clip cards show sync badges; the player has Upload/Retry/Cancel.
  - Cloud & Sync tab: account, automatic-upload preferences, queue, retry.
  - Account tab: change username and password, sessions.
  - Recording actions now return the desktop's real success or error.

**Verified**
- `mvnw package`: 55 tests, 0 failures (9 hardware tests skipped).
  - New `AuthTest`, `RemoteTest` (real HTTPS on loopback) and `CloudSyncTest`. The last one is a **mock**: a local fake of Google's OAuth/Drive endpoints.
  - `CredentialStoreTest` is a **real** Windows Credential Manager round trip.
- Self-test with real capture: screenshot, 4.1 s NVENC recording, 6.9 s instant replay – clipping still works.
- Live in the in-app browser (scratch data, loopback): sign-in (wrong password refused), Account and Cloud tabs, and upload-while-disconnected → "queued" → cancel → "local", with no tokens in responses.
- Screenshots of the Hosting and Cloud & Sync pages.

**Not verified (needs the owner)**
- A real Google sign-in, upload and download. This needs the owner's own OAuth client ID; the mocked tests don't prove it.
- Tailscale access from a phone. Home-network access from another device.
- Public mode through a real reverse proxy.

## Round 9 (2026-11-11): logos, clip from the dashboard, private remote access

- **Logos**: the owner's "with background" design is the app icon (window, taskbar, installer `.ico`, dashboard favicon). The design without the background is the in-app logo, shown at the top of the rail and in the dashboard header. Sources are in `packaging/zyclipra.svg` and `packaging/zyclipra-logo.svg`.
- **Clip from the dashboard**: the Clips page has a capture bar showing the replay/recording state, plus **⚡ Clip it (last N s)**, Record/Stop and Screenshot. If replay is off, the main button turns it on. New clips are fetched a few times after saving and get a "NEW" badge. Seen live with replay off; I didn't press Clip it, because that would record the screen. The save endpoint itself is unchanged from before.
- **Away from home**: the dashboard now refuses any connection that isn't from loopback, a home network (RFC 1918 / link-local / IPv6 ULA) or a private VPN range (100.64.0.0/10, used by Tailscale). It stays private even if a router port is opened by mistake. With "Allow my phone and other devices" on, the Tailscale address is listed. Settings explains the three steps, with a "Get Tailscale" button. Test: `RemoteTest.onlyPrivateAddressesAreServed`. 36 tests pass, 9 skipped.

## Round 8 (2026-11-11): clips dashboard, editor full screen, Sonar-style Mic page, app icon, GitHub

- **Clips dashboard** (web remote, now the first tab): search, game/type filters (videos, screenshots, favourites) and sorting. Clips are grouped by day with thumbnails, durations and favourite stars. A player plays the clip in the browser with seeking, details, Previous/Next and Download. New endpoints `GET /api/clip/thumb?id=` and `GET /api/clip/media?id=[&download=1]` need the signed-in session cookie. Ids must match `[A-Za-z0-9_-]{1,80}` and are looked up in the library, never used as paths. Only known media types are served, in 64 KB chunks with byte-range support. CSP gained `media-src 'self'`. Tested: `RemoteTest.clipMediaNeedsLoginAndSupportsRanges` (401 when signed out, 206 ranges, download header, path tricks refused). Also checked live against four real clips through a pinned-certificate local proxy in the in-app browser, at desktop and phone width.
- **Editor**:
  - The full-screen button opens a black window with only the video, a thin clickable progress bar and the play controls. Esc or F leaves it.
  - Choosing 9:16 dims the picture outside the frame.
  - The preview now plays one audio file per track (`previewFiles` returns the picture plus one MP3 per track), so muting a track or changing its volume is heard. The preview tops out at 100 %; the saved clip can go to 200 %.
- **Mic page** rebuilt like the channel pages: a top bar (voice effects switch, live levels, Set up dialog for devices and "hear myself", Reset to defaults), then the Equalizer, Noise cancellation, Gate, Compressor, Mic boost and Limiter cards.
- **Switched-off cards** (any `Ui.section` with a switch) show only their title row until turned on.
- **Mixer**:
  - The centre detent zone grew to 5 % of the range, and letting go inside it lands exactly on the centre.
  - The balance reads the slider's current value, so a snap is never undone by a stale value.
  - Clicking a column's name opens that channel's page.
  - Aux now has a headphones icon.
- **App icon** from the owner's `zyclipra.svg` (editor metadata stripped, checked for scripts and outside links: none). It's used for the window, taskbar, installer (`packaging/zyclipra.ico`) and dashboard favicon.
- **Repository**: `.gitignore` excludes videos, keys, settings and local tools; `.gitattributes` keeps `mvnw` as LF. Before pushing, the staged files were scanned for secrets and personal data: none found.

## Round 7 (2026-11-11): FFmpeg in the exe, simpler channel pages, slider fills

- **FFmpeg not found in the exe**: the lookup only checked `.tools` next to the jar and one level up. The exe's jar sits in `app\dist\Zyclipra\app`, so it never found the project's `app\.tools`. It now checks `.tools` in up to five parent folders. Verified: the exe logs "Using FFmpeg at …\app\.tools\…".
- **Preview errors**: the editor retries a failed preview once by itself, then shows a "Try again" button. Before, the error replaced the whole video area, including the details-panel arrow.
- **Mixer chips**: SteelSeries Sonar's router sessions, Voicemeeter/AudioRelay and any Zyclipra instance are no longer listed or adjusted. A router replays every app's sound, so changing it would move all channels together, and it lit up the Game meter.
- **Channel pages (Game/Chat/Media/Aux)**: the Sound source card is now a single status line with a "Set up" button. That opens a dialog with three numbered steps and the two device choices. Each page and the Mic page have **Reset to defaults**, which keeps saved presets, favourites, devices and routing.
- **Sliders**: every slider shows a filled bar (`Ui.fill`). Sliders that run from minus to plus fill from the middle, show a centre notch and briefly stick at the centre while you drag. The Game/Chat balance fills in game or chat colour; clip-editor track sliders stick at 100 %. The fill is drawn through a `-slider-fill` colour variable on the slider: styling the track node directly left it 0 px tall.
- Verified: screenshots of the mixer, Game tab, Mic tab and editor, plus `mvnw package` (34 tests, 0 failures, 9 skipped). Not tried by hand yet: the Set up dialog and Reset buttons, and the centre stick while dragging.

## Round 6 (2026-11-11): balance, device pickers, typeable values, launcher

- The Game/Chat balance now sits under the Game and Chat columns, with its middle on the line between them. The track fills from the middle out to the handle in the colour of the side it leans to. Double-click centres it.
- Clicking the device under a column's name opens a picker. For Game, Chat, Media and Aux it is the channel's output (`fx.outputDevice`); for Mic it is the voice-effects microphone (`mic.inputDevice`). Master shows the Windows default. The picker says honestly that a channel's output only applies once Zyclipra processes that channel (a sound source is set and an effect is on), because Windows has no driver-free way to re-route an app.
- Clicking any value next to a slider lets you type a number. Mixer and soundboard volumes go 0–100 %, clip-editor tracks 0–200 %, and dB and other values use each slider's own range.
- The editor's play/pause button is a 38 px circle with its icon centred. JavaFX buttons left-align their content by default, which put the icon off-centre.
- New `Start Zyclipra.cmd` in the workspace root opens `app\dist\Zyclipra\Zyclipra.exe`.
- Verified with screenshots (`-Dzyclipra.demo=1` moves the balance and draws the Game and Mic device panels), the layout tracer, and `mvnw package`: 34 tests, 0 failures, 9 skipped. Not yet done by hand: picking a device in the live app.

## Round 5 (2026-11-10): steady clip editor, one-bar mixer faders

- **Clip editor no longer jumps.** The timeline was a plain `Pane`, which snaps children back to their preferred size on every layout pass. The audio lanes lost 8 px per pass (down to thin lines), the trim handles shrank to 2 px, and the bottom panel's height kept changing, so the video, buttons and side panel moved. The timeline now places its own children and has a fixed height. The Save trim/Save copy button, the "keeps …" label, the transport buttons and the lane mute buttons have fixed sizes, and the Soundboard side section is built at full size before its check finishes.
- **Mixer faders**: each channel column now has one bar (`MeterFader`). The live level fills the track and the volume handle sits on it, like the reference. It supports drag, click, mouse wheel and arrow/Page/Home/End keys.
- Verified with a new opt-in layout tracer (`-Dzyclipra.trace=1` with `--screenshot`). Over 8 s it plays the clip and forces four full re-layouts, then prints every region that moved. Before the fix, more than 100 regions moved. After it, only the playhead moves (plus one status text getting wider when its check finishes). Screenshots reviewed, and `mvnw package`: 34 tests, 0 failures (9 hardware tests skipped). Not yet tried by hand in a live, resized window.

## Round 4 (2026-11-10): sound tracks, clip editor, channel effects
- **Recordings now have real sound tracks** (native Windows capture, no Stereo Mix needed): *Game audio* (everything except Zyclipra), *Chat audio* (comms device, when separate), *Microphone*, *Soundboard*. [hw-test] another program's tone is captured and Zyclipra's own sound is left out, which is why the soundboard can be removed from a clip. [self-test] recordings contain "Game audio" + "Soundboard" tracks.
- **Clip editor** like the reference: picture strip, a lane per sound track with mute and volume, draggable trim handles, exact start/end times, I/O keys, frame-step and skip buttons with proper icons, text captions, 9:16, and a "Keep soundboard sounds" switch when the soundboard was used. Export of trim + captions + removed tracks is [auto] tested with real FFmpeg.
- **Game / Chat / Media / Aux tabs** like the reference: preset bar with favourites and synthesized test sounds, equalizer with Bass/Voice/Treble, spatial audio (headphone crossfeed / speaker widening + room), volume boost, smart volume; Chat has noise cancellation, gate and compressor. They process the sound of a **source device** you choose (a virtual cable your apps play into via Windows' app sound settings) and play it on your headphones. [hw-test] tone into the VB-Audio cable came out processed; +12 dB EQ measured exactly ×4.0.
- Tried and **rejected** (tested, didn't work here): muting apps and processing their captured sound (Windows captures them after their volume), and setting an app's output device from Zyclipra (undocumented call didn't move sound). So routing apps to the source device is done in Windows' own settings; Zyclipra opens that page.

## Round 3 (2026-11-10): fixes and real-hardware testing
Verified on this PC, not just compiled:
- **Clip shortcut**: found the cause. Single-key shortcuts (e.g. "Right") were refused, and Alt+F9/Alt+F10/Ctrl+Alt+M are held by another program here. Replay was also off. Single keys and held keys now work through a watch-only keyboard hook (keys are never blocked or stored). Pressing the clip key while replay is off turns replay on and says so. Proven with a real keypress.
- **Mixer writes real Windows volume** [hw-test]: the session went to 25%, and Windows reported 0.25. With the mixer channel at 40%, the session volume was 0.40. Channel mute muted the session.
- **Mic capture + monitoring** [hw-test via VB-Audio cable]: the tone was captured at the expected level, and 64,800 frames reached the monitor output.
- **Feedback-loop protection** [hw-test]: mic "CABLE Output" + send to "CABLE Input" is refused, and 0 frames were routed.
- **Virtual cable routing** [hw-test]: soundboard sound sent to CABLE Input arrived on CABLE Output. The cable is detected, and the app shows install steps if none exists.
- **Latency** [hw-test]: about 95–105 ms through the cable.
- **Clip editor** like the reference: info bar, player, 9:16 canvas, details, timeline with trim handles and named audio lanes. Export is tested with real FFmpeg.
- **Preview playback**: Java's player can't decode AAC on this PC, so previews play a silent copy of the picture plus an MP3 mix in sync.
- **Capture bar** shows "CAPTURING · game" with a REC badge. The Clip Settings page no longer grows sideways. App chips can be dragged between channels, and the Game/Chat balance snaps to the middle.

Still needs you: **Google Drive end-to-end**. It needs your own free OAuth "Desktop app" client ID. Paste it in Settings › Google Drive, then press Connect and "Test connection" (this only reads your Drive). It has not been tested against Google yet. A test with your own real voice is also still to do.

## Interface redesign (round 2)
The interface was rebuilt to follow the person's reference pictures and be simpler: a slim icon rail (Clips, Audio Mixer, Soundboard, Settings at the bottom); Clips is the start page with a capture bar (status, Record, instant replay switch, save-clip keys, length/quality/resolution/fps) above a large clip grid grouped by day; the Audio Mixer has tall channel columns with app chips plus Game/Chat/Media/Aux/Mic tabs (Mic holds the draggable equalizer and voice effects); Settings is a list of pages on the left. The separate Home page was merged into Clips. No logos, names or artwork of the original were used. Verified by self-test and screenshots of every page.

## Current milestone
**Milestone 1 – working foundation of all eight phases.** Every section exists and does real
work. Next: the items in "Next planned tasks".

## How things were verified
Three kinds of evidence, kept separate:
- **[auto]** automated JUnit tests (`mvn package`, 24 tests, all passing).
- **[self-test]** `--self-test`: the real app, driven by script on this PC (Windows 11, NVIDIA GPU), with real files written and checked.
- **[hw-read]** read-only integration tests against real Windows APIs on this PC.
- **[not tested]** written but not yet exercised with real hardware or services. Never counted as done.

## Completed and tested
| Feature | Evidence |
|---|---|
| Build: Maven, shaded jar, JDK 21 target (built on JDK 24) | [auto] `mvn package` |
| Settings: load/save, atomic writes, repair of bad values, damaged-file recovery | [auto] CoreTest |
| JavaFX shell: sidebar, 5 sections, header status, status bar, toasts, accent colours, Ctrl+1…5 | [self-test] navigated all 5 sections; screenshots reviewed |
| Manual recording (start/stop, NVENC, MP4) | [self-test] 4.1 s 1920×1080 H.264 file recorded with `h264_nvenc`, probed with ffprobe |
| Hardware encoder detection by real test encode | [self-test] NVENC works; QuickSync and AMF correctly rejected on this PC |
| Instant replay (segment ring, save last N s) | [self-test] 6.0 s replay saved after 7 s of buffering |
| Screenshot | [self-test] 1920×1080 PNG written |
| Clip library: scan, add, rename, move to folder, delete, index survives restart, invalid names refused | [auto] LibraryTest |
| Clip probing, thumbnails, lossless trim | [auto] LibraryTest with real FFmpeg (6 s test video → 3 s trim) |
| REC timer in the header while recording | [self-test] |
| Core Audio: endpoints, friendly names, app sessions, volumes, peak meters | [hw-read] 11 devices, 33 sessions; live meters visible in the Mixer screenshot |
| Global hotkeys: register, conflict reporting, invalid keys refused, unbind | [hw-read] WindowsIntegrationTest |
| Displays (physical pixels, multi-monitor) and window list | [hw-read] 2 monitors, 15 windows |
| EQ biquads (peak, shelves), 9-band response, enable/disable | [auto] DspTest (measured gains within 0.3 dB) |
| Noise gate, compressor, limiter, noise suppression | [auto] DspTest (limiter never exceeds ceiling; noise −8 dB+ while speech bursts keep > −2 dB) |
| Soundboard import (WAV/MP3… via FFmpeg conversion), play, stop | [self-test] tone imported, played, stopped |
| Mixer channel change (dry-run in checks) | [self-test] |
| Dashboard: HTTPS, pairing-code login, cookie flags, CSRF header, origin check, input validation, size limit, lock-out, loopback default, static files, CSP | [auto] RemoteTest |
| Drive resumable upload incl. interruption and resume | [auto] DriveClientTest against a **local fake** server |
| PKCE (RFC 7636 test vector) | [auto] CoreTest |
| Dashboard TypeScript | compiled with `tsc --strict` without errors |
| Portable Windows package (jpackage app-image) | built; `Zyclipra.exe --screenshot` ran and rendered |

## Implemented but NOT yet tested on real hardware/services
- Changing real app volumes from the mixer (deliberately disabled in automated checks so your volumes are never touched). The COM calls are the same family as the tested read calls.
- Microphone capture, monitoring and routing to a virtual cable (the DSP is tested; opening a real mic wasn't, to avoid recording you without asking).
- Audio inputs inside recordings (dshow devices); separate tracks vs mixed track.
- Window-mode capture (`title=`), second-monitor capture, 120/144 fps.
- Recording overlay window (click-through styling).
- Google sign-in and real Drive upload/download (needs your own OAuth client ID).
- Dashboard used from a phone over the LAN.
- MSI installer (needs WiX 3 on the build machine; GitHub's Windows runner images have shipped WiX 3, but the workflow hasn't been run yet).
- The GitHub Actions workflow itself (not pushed anywhere, as asked).
- Clip preview playback (JavaFX media) – UI built, not driven by a test.
- Long recordings, low-disk behaviour during a recording, device unplugged mid-session.

## Known bugs / rough edges
- Some protected processes show as "pid 1234" in the mixer because Windows refuses to tell a normal app their name.
- Trim points snap to keyframes (2 s in Zyclipra's recordings).
- Replay length is approximate to ±2 s (segment granularity).
- The packaged app-image is ~176 MB (full Java runtime). A trimmed runtime via `jlink` is planned.
- JavaFX logs an "unsupported configuration" warning when started from the shaded jar; harmless.

## Technical limitations (operating system)
- **EQ/effects on other apps' audio** need a system audio driver (APO) or a virtual device; Zyclipra only processes the audio it produces itself.
- **Routing an app to a different output device** uses an undocumented Windows interface; not implemented. Apps are grouped into channels and their session volumes controlled instead.
- **Game sound in recordings** needs a capture device carrying it until native WASAPI loopback capture is added.
- **Exclusive full-screen** games hide overlays and may record black with `gdigrab`.
- **Google sign-in on the LAN dashboard** isn't allowed by Google for private IP addresses; pairing code used instead.

## Next planned tasks
1. Native WASAPI loopback capture (per-device and per-process on Windows 10 2004+) piped into FFmpeg, so game/chat audio records on separate tracks without Stereo Mix.
2. Desktop Duplication (DXGI) capture for lower CPU and full-screen games.
3. Hardware-tested mic path and mixer writes with the person present; add device-unplug test.
4. `jlink` trimmed runtime; signed MSI.
5. Linux back-end (PipeWire for audio, x11grab/pipewire for capture).
