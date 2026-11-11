# Zyclipra

**Free, open-source (MIT) gaming utility for Windows**: screen and game recording, instant
replay, a clip library, a multi-channel audio mixer with per-app control, a 9-band equalizer,
microphone effects, a soundboard, optional Google Drive clip sync, and an optional remote
dashboard for your phone.

No account, no subscription, no ads, no telemetry. Everything works offline.

Built with Java 21+ and JavaFX (no Electron). Windows 11 is the main platform; the code is split
behind interfaces so Linux and macOS back-ends can be added.

---

## Install and run

### Option A – portable app (recommended for players)
1. Download or build the `Zyclipra` folder (see *Build* below: `packaging/build-installer.ps1`).
2. Install FFmpeg (needed for recording, replays, thumbnails and MP3 import) – see *FFmpeg* below.
3. Double-click `Zyclipra.exe`.

### Option B – run the jar
Requires Java 21 or newer.
```
java --enable-native-access=ALL-UNNAMED -jar target/zyclipra.jar
```

Command-line options:

| Option | What it does |
|---|---|
| `--minimized` | start minimised |
| `--page clips` | open on a section (home, clips, mixer, soundboard, settings) |
| `--data-dir <folder>` | keep settings somewhere else (default `%APPDATA%\Zyclipra`) |
| `--screenshot <file.png>` | render the window off-screen, save a PNG and exit (used by build checks) |
| `--self-test <file.json>` | record, save a replay, take a screenshot, play a sound and check the UI, then write results and exit. Uses a temporary folder and never changes your Windows volumes |

## Build

Requirements: JDK 21+ (tested with JDK 24). Maven is optional: `mvnw.cmd` / `./mvnw` fetch
Maven 3.9.9 into the project's `.tools` folder and keep dependencies in `.m2/` inside the project.
Node.js is only needed if you change the dashboard's TypeScript.

```
mvnw.cmd package               # (or: mvn package) compiles, runs all tests, writes target/zyclipra.jar
cd src/main/web && npx -p typescript@5.6.3 tsc -p tsconfig.json   # only after editing app.ts
powershell -File packaging/build-installer.ps1 -Type app-image     # portable folder in dist/
powershell -File packaging/build-installer.ps1 -Type msi           # installer (needs WiX 3 on PATH)
```

The installer installs per user (no administrator rights). To ship FFmpeg inside the package,
put `ffmpeg.exe` and `ffprobe.exe` in `packaging/ffmpeg/` before running the script.

GitHub Actions (`.github/workflows/build.yml`) builds and tests on Windows and Linux, runs the
self-test on Windows and produces the MSI. Nothing is published automatically.

## Optional native dependencies

### FFmpeg (recording)
Zyclipra uses FFmpeg as an external program for screen capture (`gdigrab`), encoding
(NVIDIA NVENC, Intel QuickSync, AMD AMF or x264), replay joining, trimming and thumbnails.
It looks for it in: Settings → Recording → FFmpeg, the `ZYCLIPRA_FFMPEG` variable, an `ffmpeg`
folder next to the app, then your `PATH`.

Get a free Windows build from https://www.gyan.dev/ffmpeg/builds/ (the "essentials" build is
enough) or `winget install Gyan.FFmpeg`. FFmpeg builds with x264 are GPL-licensed; Zyclipra
only runs it as a separate program, so Zyclipra itself stays MIT.

Without FFmpeg everything else (mixer, EQ, mic effects, soundboard with WAV files, dashboard) works.

### Virtual audio cable (sending soundboard / processed mic into voice chat)
Windows has no built-in way for an app to create a microphone. To let Discord or a game hear
your soundboard or your processed voice, install a virtual audio cable and pick it in Zyclipra
(Soundboard → "Also send to", Mixer → "Send processed voice to"); then choose the cable's
output as the microphone in the chat app.

Free options (Zyclipra never installs drivers itself; installing one needs administrator rights
and a reboot, and you should only install drivers you trust):
- **VB-CABLE** (free donationware, closed source) – https://vb-audio.com/Cable/
- **Virtual Audio Driver by VirtualDrivers** (open source, MIT) – https://github.com/VirtualDrivers/Virtual-Audio-Driver
- On Linux, PipeWire/PulseAudio can create virtual sinks without extra drivers.

## Features and how they work

| Area | What you get | How |
|---|---|---|
| Recording | start/stop, display or single window, 10–144 fps, resolution, bitrate, quality, MP4/MKV, hardware encoding, one audio track per source | FFmpeg `gdigrab` + the best encoder that passes a real test encode on your PC |
| Instant replay | 15 s – 5 min, save-last-N-seconds hotkey | FFmpeg writes 2-second MPEG-TS segments into a fixed-size ring on disk; saving joins them without re-encoding |
| Screenshots | PNG of the display or window | FFmpeg, or Java's screen grabber without FFmpeg |
| Overlay | small "REC 01:23" pill and "Replay saved" flash, click-through, never takes focus | always-on-top transparent window |
| Clips | thumbnails, preview, search, filter, sort, folders, favourites, rename, delete (Recycle Bin), export, lossless trim, file/size/resolution/codec/tracks info | JavaFX media player, ffprobe, FFmpeg stream copy |
| Mixer | Game/Chat/Media/Aux/Mic/Master channels, per-app volume & mute, assign apps to channels, live meters, profiles, follows device changes | Windows Core Audio (WASAPI) through JNA: audio sessions, endpoint volume, peak meters |
| Equalizer | 9-band parametric (frequency, gain, Q), presets, live curve, on/off | biquad filters (RBJ cookbook) |
| Microphone | gain, noise gate, compressor, limiter, noise suppression, monitoring, routing to a virtual cable | Java Sound capture + Zyclipra's own DSP (no paid libraries) |
| Soundboard | import MP3/WAV/OGG/FLAC/M4A…, collections, rename, volume, loop, stop/replay, global shortcuts, two outputs at once | files converted once to 48 kHz WAV and played from memory |
| Google Drive | optional; automatic or manual upload into Zyclipra/Clips, Recordings, Screenshots; queue with states, progress, retry, cancel; download back; delete cloud copy; disconnect revokes the token | OAuth 2.0 + PKCE in the system browser, `drive.file` scope, resumable uploads verified by MD5, persistent queue, token in Windows Credential Manager |
| Dashboard | clips page (play, download, upload to Drive), Clip it / record / screenshot, Google Drive status and preferences, account and sessions, mixer and soundboard | HTTPS with a certificate made on your PC, username + Argon2id password, session cookies, rate limiting, CSRF protection; this PC by default, home network / Tailscale by choice |

## Google Drive (optional)
Back up clips, recordings and screenshots to *My Drive › Zyclipra*, automatically or one at a time.
Your PC stays the main library; nothing is removed from it unless you choose that.

1. *Settings › Cloud & Sync* › **Sign in with Google**, choose your account, allow access.
2. Turn on **Automatic upload**, or upload single clips from the clip menu, the editor or the dashboard.

Sign-in is Google's own OAuth 2.0 + PKCE in your browser, with the `drive.file` scope (Zyclipra
only sees files it made). The token is kept in Windows Credential Manager. Uploads resume after
interruptions, are checked against the local file's checksum, and never create duplicates.

## Dashboard and hosting (optional)
A web page served by Zyclipra on your PC: clip, record, browse/download clips, Google Drive status,
mixer and soundboard – from your phone or another PC.

1. *Settings › Hosting*: create a dashboard **username and password** (there's no default account).
2. Press **Start dashboard**, then open `https://localhost:47820`.
3. Optionally allow **devices on my home network**, or – for away from home – **Private VPN
   (Tailscale)**, which keeps the dashboard off the internet.

Public internet access is possible only behind your own HTTPS reverse proxy, off by default and
not recommended. Details, security notes and troubleshooting:
[docs/cloud-and-hosting.md](docs/cloud-and-hosting.md).

## Privacy and security
- No telemetry, no accounts, no background uploads. Data folder: `%APPDATA%\Zyclipra`, logs in `logs\`.
- No administrator rights needed. No drivers installed. No keyboard hooks: global shortcuts use `RegisterHotKey`, so Zyclipra never sees other keystrokes.
- Microphone is only opened when you turn on voice effects. Screen capture only runs while recording or while instant replay is on, and the header always shows it.
- Settings files are written atomically; a damaged settings file is set aside and defaults are used.

## Known limitations (honest list)
See `PROGRESS.md` for the full, current list. The main ones:
- The EQ and mic effects shape the audio Zyclipra produces (your processed mic and the soundboard). Changing other apps' sound itself (e.g. EQ on a game) needs an audio driver (APO), which Zyclipra does not install.
- Capturing game/desktop **sound** in recordings needs a capture device that carries it ("Stereo Mix", or a virtual cable the game outputs to). Direct WASAPI loopback capture is planned.
- `gdigrab` captures the desktop; exclusive full-screen games may show black – use borderless windowed mode.
- Dashboard sign-in is a username and password you create in the app; signing in to the dashboard with a Google account isn't offered (Google only allows localhost or public domains as sign-in targets).
- Google sign-in is built from `app/private/google-oauth.json`, which isn't in the repository; builds without it have no Google Drive (see docs/cloud-and-hosting.md). While the OAuth consent screen is in Testing mode, only its test users can sign in and must sign in again every 7 days.

## Project layout
```
src/main/java/io/zyclipra/
  app/        start-up, wiring, command-line checks
  core/       settings, paths, logging, event bus
  platform/   OS facts (displays, windows, foreground app)
  recording/  FFmpeg, encoders, recorder, replay buffer, screenshots
  clips/      clip library, probing, thumbnails
  audio/      Core Audio (WASAPI), mixer, mic processor, devices
  audio/dsp/  biquad EQ, gate, compressor, limiter, FFT, noise suppressor
  soundboard/ soundboard
  hotkeys/    global shortcuts
  cloud/      OAuth PKCE, DPAPI token store, Drive client, sync
  remote/     HTTPS dashboard server, auth, TLS certificate
  ui/         JavaFX screens
src/main/web/       dashboard TypeScript source
src/main/resources/ CSS, dashboard HTML/CSS/compiled JS
src/test/java/      automated tests
```

## Licence
MIT – see `LICENSE`. Zyclipra is an independent project and is not affiliated with any hardware or software vendor.
