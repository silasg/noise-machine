# Noise Machine

Always-on Android noise-machine / ambient mixer that's fully controllable from a
web browser. Designed for a phone or old Android tablet sitting on a nightstand
or shelf, exposed only over Tailscale.

## What it does

- **Launchpad-style mixer.** Tap a tile and that sound starts. Tap another and
  it plays at the same time. Each tile has its own volume slider; everything
  feeds through a master volume.
- **Sounds:**
  - Procedurally synthesized: white noise, pink noise, brown noise,
    grandfather-clock ticking.
  - Looped recordings (download locally before building; bundled in the APK): rain, ocean, wind,
    fan, fireplace, thunderstorm, forest birds, stream, coffee shop,
    ambient pad.
  - User-uploaded audio (mp3, ogg, wav, m4a, flac, opus) via the web UI.
- **Scenes.** Save the current set of active sounds (and their volumes) under a
  name, then activate the whole scene with one click or from a schedule.
- **Schedule.** Per day-of-week recurring entries that activate a scene at a
  given time. Optional "auto-stop after N minutes". Backed by `AlarmManager`
  with `setExactAndAllowWhileIdle` so it fires even in Doze.
- **Always running.** A foreground service holds a partial wake lock and only
  pauses for transient audio focus loss (i.e. an alarm, phone call). When the
  alarm finishes the noise resumes. The service does not stop on its own — you
  stop it from the web UI.
- **Auto-mute notifications.** While playing, the service can flip the device
  into "alarms only" Do-Not-Disturb so notifications don't blast through your
  ambient bed. Alarms still ring.
- **Auto-set volume.** When playback starts the service can force the music
  stream to a configured percentage of max so the level is predictable.
- **Auto-start on boot.** Re-arms schedules and (optionally) restores the last
  active mixer state after the device reboots.
- **Web UI is the source of truth.** The Android activity itself is just a
  full-screen WebView pointing at `http://127.0.0.1:<port>/`, so the on-device
  controls are identical to remote-over-Tailscale controls.

## Building

You need Android Studio Hedgehog+ or the command-line Android SDK with build
tools 34, plus JDK 17.

```bash
# from the project root
./gradlew :app:assembleRelease
# or for a debuggable build
./gradlew :app:assembleDebug
```

Note: this repo does not include the Gradle wrapper jar. Either:
- open the project in Android Studio (it will fetch the wrapper for you), or
- run `gradle wrapper --gradle-version 8.7` once with a system Gradle to
  generate `gradle/wrapper/gradle-wrapper.jar` and `gradlew`.

## Preparing audio for the APK

This repository does not store audio binaries. Download and prepare the recordings
locally using the script below; the build then bundles them in the APK for offline
playback. See `SOUND-CREDITS.md` for individual licenses, source links and
preparation notes; credits are also available in the app's Settings tab.
The generated OGG Vorbis files go in `app/src/main/assets/sounds/`, named:

```
rain.ogg ocean.ogg wind.ogg fan.ogg fireplace.ogg
storm.ogg forest.ogg stream.ogg cafe.ogg ambient.ogg
```

Anything missing simply shows up as a disabled "missing audio file" tile.
You can also upload arbitrary audio files at runtime in the **Library** tab.

### Reproduce the audio library

Generated audio is ignored by Git; run the tracked downloader before packaging.
Requires `curl`, `ffmpeg` (with libvorbis), `ffprobe` and Python 3.9+ (stdlib only).

```bash
# Download rain manually from the credited Pixabay page, then:
bash tools/fetch-sounds.sh --rain-file /path/to/downloaded-rain.mp3

# Reproduce in isolation without touching installed assets:
bash tools/fetch-sounds.sh --output-dir /tmp/noise-reproduced --rain-file /path/to/downloaded-rain.mp3

# Explicitly replace existing generated assets:
bash tools/fetch-sounds.sh --force --rain-file /path/to/downloaded-rain.mp3
```

Existing assets are skipped by default, including `rain.ogg`. Missing `fan.pcm`
is derived from the retained `fan.ogg`; replacing fan regenerates both. Local
existing audio is user-owned and is not asserted to match pinned sources.
Without rain already present, `--rain-file` is required. Download it manually:
https://pixabay.com/sound-effects/nature-calming-rain-loop-398653/
There is no Pixabay automation, YouTube download or yt-dlp dependency.

Seven Blanket recordings are pinned to commit
`2eaca9ba5a1b624adf90b6ccee1008efc46f0273` and original SHA-256 checksums.
The official Freesound HQ fan preview and OpenGameArt ambient original also
have pinned source checksums. Originals are staged, never overwritten in place.
Cafe uses the original FFmpeg 150 ms circular quarter-sine overlap; forest uses
750 ms. Fan uses its distinct 50 ms float32 circular cosine/sine overlap.
All three are Vorbis quality 5. Ambient and the other five Blanket OGGs remain
byte-for-byte originals. Rain is converted to Vorbis quality 5 without loop edits.
All ten recorded sounds have mono 44100 Hz signed 16-bit little-endian `.pcm` copies decoded from their final retained OGG, without normalization or trimming. Existing OGG processing and sources are unchanged. Missing PCM is rebuilt without downloading; replacing OGG also replaces its PCM.

PCM assets currently total 60,084,066 bytes (57.30 MiB) before APK compression (roughly 66 MiB budget, duration-dependent). Streaming uses fixed buffers per active sound, not a roughly 131 MiB decoded stereo cache; fan retains its tiny cached loop. User uploads still use MediaPlayer.
Source verification is byte-exact; re-encoded Ogg bytes can vary with encoder
versions/container serials. Matching FFmpeg/libvorbis versions are needed for
sample-equivalent lossy output. No normalization or arbitrary cropping is added.

All outputs are prepared and decoded before publication; each file is atomically
renamed, with temporary files cleaned on failure. Publication is per-file, not a
multi-file transaction. HTTP errors include their response body (including network
approval instructions). A checksum mismatch aborts rather than accepting changed
upstream content. See `SOUND-CREDITS.md` for licenses; Pixabay is not CC0.

Validation (no network for focused tests):

```bash
bash -n tools/fetch-sounds.sh
python3 -B -m unittest discover -s tools -p 'test_fetch_sounds.py'
```

## Installing & permissions

1. Install the APK (`adb install app/build/outputs/apk/debug/app-debug.apk`).
2. Open the app once. It will request **ignore battery optimizations** —
   approve. Without this Android will eventually doze the service.
3. In Android settings → **Apps → Noise Machine → Notifications →
   Notification access** (a.k.a. Notification policy access), grant access.
   Required for DND control. The web UI's Settings tab shows a warning while
   this is missing.
4. On Android 12+, in Apps → Noise Machine → **Alarms & reminders**, enable
   it. Required for exact, wake-up alarms.
5. The first time playback starts, you may also need to grant
   `POST_NOTIFICATIONS` for the persistent foreground notification.

## Wi-Fi Watchdog

The watchdog is off by default. On Android 8 or 9, connect the tablet to a
saved Wi-Fi network, then turn on **Automatically reconnect Wi-Fi** in the
Noise Machine **Settings** tab. The foreground service saves that network as
the target and checks its local connection state even when audio is idle and
the display is off. If Wi-Fi stays disconnected for 15 seconds, the watchdog
asks Android to select the saved network and waits up to 30 seconds for
confirmation. Failed attempts are followed by 30-, 60-, and 120-second
retry delays and then a five-minute cooldown. For recovery across a reboot,
leave **Auto-start on boot** enabled. If Android refuses access or cannot identify the saved network, the
toggle shows the reason. Select a saved network in Android Settings, then
try again. The stock Android 8.1 API path needs no Location grant or Location
services.

To verify on the Android 8.1 tablet:

1. Leave playback idle, enable the watchdog and confirm that **Target SSID**
   and **Connected** appear.
2. Turn off the display. From the access point, interrupt the tablet's
   connection for more than 15 seconds while leaving the tablet's Wi-Fi on
   and the saved configuration intact. Restore access to the same Wi-Fi
   network. Use `adb logcat -s WifiWatchdog` over USB to confirm a repair
   request followed by a verified connection. A short interruption that
   Android repairs during the grace period may show no request.
3. Reopen Settings and check the last successful connection and repair
   attempt. Reboot with **Auto-start on boot** on and confirm the toggle and
   target are restored.
4. Turn off the watchdog and repeat the drop. Check that there are no
   watchdog repair requests. With the watchdog enabled, turning off Wi-Fi
   in Android Settings should leave Wi-Fi off.

The watchdog confirms the target connection locally and does not check DNS
or internet access. It leaves other working Wi-Fi connections alone. Android
10 and newer restrict saved-network repair for ordinary apps. OEM firmware
can also restrict repair, and Android suspension can delay checks. Diagnostic
timestamps cover the current service session.

The existing `POST /api/settings` accepts `{"wifiWatchdogEnabled":true}` or
`false`. The response, `GET /api/state`, and `GET /api/events` include a
`wifiWatchdog` snapshot with `enabled`, `targetSsid`, `status`,
`lastConnectedAtMs`, `lastAttemptAtMs`, and `lastError`. Timestamps are
Unix milliseconds. Failed toggle requests return HTTP 409 with an error.

## Connecting over Tailscale

1. Install the Tailscale app on the noise-machine Android device, sign in,
   and note the device's tailnet IP (e.g. `100.64.0.5`) or magicDNS name.
2. From any other tailnet device, open `http://<tailnet-ip>:8378` (or whatever
   port you set on the Settings tab).
3. The web server binds to `0.0.0.0`, so it's reachable on any interface that
   the Android device exposes — Tailscale, the local Wi-Fi, USB tethering,
   etc. If you only want it reachable over Tailscale, use Tailscale's MagicDNS
   and set up a stricter firewall on your network.

## Architecture

```
   ┌─────────────┐   tap / drag    ┌──────────────┐
   │  Web UI     │ ──────────────▶ │  HTTP /api   │
   │ (any browser)│ ◀── SSE state ─│  (NanoHTTPD) │
   └─────────────┘                 └──────┬───────┘
                                          │
                                  ┌───────▼────────────────────────┐
                                  │  NoiseService (foreground,     │
                                  │   wake-locked, mediaPlayback)  │
                                  ├────────────┬───────────────────┤
                                  │ Procedural │   FileVoice (×N)  │
                                  │   Mixer    │  (MediaPlayer per │
                                  │ (single    │   active loop)    │
                                  │  AudioTrack│                   │
                                  │  PCM-FLOAT)│                   │
                                  └─────┬──────┴────────┬──────────┘
                                        │               │
                                  ┌─────▼───────────────▼─────────┐
                                  │ Android STREAM_MUSIC (mixed)  │
                                  └───────────────────────────────┘
```

`AlarmManager` → `ScheduleReceiver` → `NoiseService.onStartCommand` activates
or stops scenes at scheduled times. After firing each entry, the service
re-arms the next occurrence so the schedule survives indefinitely.

## File layout

- `app/src/main/java/com/noisemachine/`
  - `audio/` — procedural voices, mixer, file voice, sound catalog
  - `state/` — persistent JSON settings, schedule manager
  - `net/` — embedded HTTP server
  - `NoiseService.kt`, `MainActivity.kt`, `BootReceiver.kt`,
    `ScheduleReceiver.kt`
- `app/src/main/assets/`
  - `web/index.html`, `web/static/style.css`, `web/static/app.js`
  - `sounds/` — drop your looped audio files here
- `app/src/main/AndroidManifest.xml` — permissions, foreground service type,
  receivers

## Notes on alarms taking priority

The service requests `AUDIOFOCUS_GAIN` with `USAGE_MEDIA`. When the system
fires an alarm clock, the alarm app requests its own audio focus, which sends
us `AUDIOFOCUS_LOSS_TRANSIENT`. The service immediately pauses every voice;
when focus is regained (alarm dismissed), playback resumes. Combined with
DND set to `INTERRUPTION_FILTER_ALARMS`, the alarm always wins.
