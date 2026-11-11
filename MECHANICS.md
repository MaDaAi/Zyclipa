# Zyclipra – values that shape how it behaves

Zyclipra is a utility, not a game, so this sheet lists the numbers that decide how recording,
audio, the soundboard and the remote dashboard behave. The same values live in
`mechanics.json`. "Estimated" means a sensible default chosen by us (clean room: nothing of the
original program was opened, so none of its values were copied). Everything marked estimated
can be changed in Settings unless noted.

## Recording and instant replay
| Value | Setting | Source |
|---|---|---|
| Replay length range | 15 – 300 s | person's request |
| Replay default | 60 s (1 minute) | reference picture 12 |
| Replay segment length | 2 s (ring of MPEG-TS files on disk, not RAM) | Zyclipra design |
| Keyframe interval | 2 s (fps × 2) – trims snap to it | Zyclipra design |
| Frame rate | 30 fps default, 10–144 | default from reference picture 12 |
| Resolution / quality defaults | 1080p, High (20 000 kbps) | reference picture 12 (bitrate for "High" estimated) |
| Video bitrate | Low 6 000 / Medium 12 000 / High 20 000 / Very high 40 000 kbps | estimated |
| Audio | AAC 160 kbps, 48 kHz, one track per source | estimated |
| Containers | MP4 (default), MKV | person's request |
| Encoder order | NVENC → QuickSync → AMF → x264, each verified by a real test encode | measured on this PC: NVENC works, QSV/AMF don't |
| Free space needed to start | 1 GB | estimated |
| Clip names | `<Game>_<yyyy-MM-dd_HH-mm-ss>.mp4` (game = app in front) | estimated |

## Shortcuts (all changeable)
| Action | Default |
|---|---|
| Start / stop recording | Alt+F9 |
| Save instant replay | Alt+F10 |
| Instant replay on/off | Alt+Shift+F10 |
| Screenshot | Alt+F12 |
| Stop all sounds | Ctrl+Alt+End |
| Mute microphone | Ctrl+Alt+M |
| Switch section | Ctrl+1 … Ctrl+5 |

All estimated. Global shortcuts use Windows `RegisterHotKey`; a shortcut taken by another
program is reported instead of silently failing.

## Audio
| Value | Setting | Source |
|---|---|---|
| Channels | Game, Chat, Media, Aux, Microphone, Master | person's request |
| App → channel | Chat: Discord, TeamSpeak, Mumble, Teams…; Media: browsers, Spotify, VLC…; System sounds: Aux; others: Game | estimated |
| Per-app volume | channel volume × app volume, applied to the Windows audio session | Zyclipra design |
| EQ | 9 bands, low shelf + 7 peaks + high shelf at 32, 64, 125, 250, 500, 1k, 2k, 4k, 8k Hz; ±12 dB; Q 0.1–10 | count from request, rest estimated |
| EQ presets | Flat, Bass boost, Footsteps, Voice clarity, Treble boost, Warm + your own | estimated |
| Processing rate / block | 48 kHz, 10 ms | estimated |
| Noise gate | −50 dBFS, 6 dB hysteresis, 120 ms hold | estimated |
| Compressor | −20 dBFS, 3:1, 6 dB soft knee, 5 ms attack / 120 ms release, auto make-up | estimated |
| Limiter | −1 dBFS ceiling, 50 ms release | estimated |
| Noise suppression | spectral, 512-sample frames (10.7 ms latency), up to 12 dB default reduction | estimated |
| Meters | −60…0 dBFS, refresh 80 ms (only while visible) | estimated |
| App list refresh | 1.5 s | estimated |

## Soundboard
| Value | Setting | Source |
|---|---|---|
| Formats | WAV, MP3, OGG, FLAC, M4A, AAC, OPUS, WMA, AIFF | MP3/WAV from request |
| Import limit | 100 MB / 10 min per sound | estimated |
| Outputs | your device and, at the same time, a second device (e.g. a virtual cable) | person's request |

## Cloud and remote
| Value | Setting | Source |
|---|---|---|
| Drive scope | `drive.file` only | person's request (minimal permissions) |
| Upload | resumable, 2 MiB chunks, 5 retries with 2/4/8/16 s back-off, resumes after restart | Google rule for chunks; rest estimated |
| Dashboard | HTTPS, port 47820, 127.0.0.1 unless LAN ticked, 12 h sessions, 10-char pairing code, lock-out after 5 wrong codes | estimated |
