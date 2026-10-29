# NODE

**An old phone, rebuilt as a Wi-Fi camera.**

A Redmi Note 8 running stock MIUI — bootloader locked, no root, no custom recovery — turned
into a fixed-function camera appliance. Its rear camera is far better than any laptop webcam;
NODE puts it on your network. Open a URL in any browser on the Wi-Fi to watch it live, with
sound, while it records the last couple of hours to the phone in a loop.

<p align="left">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2011%2B%20(API%2030)-000000">
  <img alt="language" src="https://img.shields.io/badge/Kotlin-2.0.21-000000">
  <img alt="dependencies" src="https://img.shields.io/badge/runtime%20dependencies-zero-000000">
  <img alt="permissions" src="https://img.shields.io/badge/permissions-INTERNET%20CAMERA%20RECORD__AUDIO-000000">
  <img alt="apk size" src="https://img.shields.io/badge/APK-~2.4%20MB-000000">
</p>

NODE began life as a desk clock. That version lives on in git history at `9a42e35`.

---

## What it does

| | |
|---|---|
| **Live view** | 1280×720 at up to 15 fps in any browser on the LAN. Nothing to install |
| **Sound** | Live microphone audio on the same page — press **Sound** |
| **Loop recording** | 1080p H.264 at 8 Mbps plus AAC audio, in 5-minute MP4 clips on the phone. The oldest go once the clips pass 8 GB, or when the phone drops below 2 GB free |
| **Recordings** | Listed newest first and downloadable from the same page |

Video runs at 30 fps and, where the camera allows it, slows to 15 fps in a dim room rather
than going dark.

## Use it

1. Build and install (below), open NODE, allow the camera and microphone.
2. The screen shows `● REC` and an address such as `http://192.168.1.50:8080/k7m2x9qa4c/`.
   **Tap the screen** to brighten it for 30 seconds — the tap also refreshes the address.
3. Open that address on any phone, tablet or PC on the same Wi-Fi.

The screen stays on at minimum brightness because Android only lets the app in front use the
camera and microphone. Leaving NODE, or pressing the power button, stops recording and the
live view; coming back resumes both. If the camera fails while NODE is in front — another app
grabs it, a media service restarts — NODE says why on screen and tries again every 5 seconds.

**As a Zoom / Teams / Meet webcam:** in [OBS Studio](https://obsproject.com) add a **Browser**
source with the address, then press **Start Virtual Camera**. "OBS Virtual Camera" then appears
as a webcam in every app. No extra code.

## Recordings

Clips are named by the phone's local time, `NODE_yyyyMMdd_HHmmss.mp4`, in the app's own folder:

```bash
# Git Bash on Windows: stop it mangling the device path first
export MSYS_NO_PATHCONV=1
adb pull /sdcard/Android/data/dev.node01.afterlife/files/Movies/ clips
```

- At about 1 MB/s, the 8 GB cap holds roughly **2 hours**. Change `CAP_BYTES` for more.
- A clip is written as `.mp4.part` and renamed only once finalized. If the app is killed
  mid-clip, that file has no index and will not play — it stays hidden as `.part` (a tool such
  as `untrunc` can often recover it) and is pruned with the rest. At most one clip is lost.
- **Uninstalling NODE deletes every clip** — Android wipes an app's folder with it. That
  includes the uninstall forced when the signing key changes, e.g. building on another PC.
  `adb install -r` with the same key keeps them.

## Leaving it running

- **Set NODE as the default Home app and the screen lock to None.** After a reboot or a crash
  it then comes straight back and resumes recording without anyone touching it.
- **Keep it on the charger, with airflow.** The camera and encoders run constantly and the
  phone gets warm; if it throttles, the frame rate drops rather than the recording stopping.
- **Check the battery for swelling now and then.** An old battery held at 100% and kept warm,
  around the clock, is the real hazard of this project. A timer plug that lets it cycle helps.
- **Flash wear:** at 8 Mbps NODE writes about 85 GB a day to old storage. For a static scene
  `VIDEO_BITRATE = 4_000_000` looks nearly as good and halves both wear and space.

## Security

- The random key in the address is the only thing between the network and your camera and
  microphone. Any path without it gets a 404. It is generated on first run, stored on the
  phone and never in this repo. Clearing NODE's data issues a new key — and deletes the clips.
- It is **plain HTTP**: anyone able to watch your Wi-Fi traffic can read the key and the
  streams. Use it on a home network only and **never port-forward 8080**.
- Recording audio needs consent in more places than recording video does. Know your local law.

## Design principles

**Nothing runs in the background.** The camera, encoders, microphone and server start in
`onStart` and stop in `onStop`. A backgrounded NODE costs nothing.

**Zero runtime dependencies.** No AndroidX, no CameraX, no HTTP library. Camera2, MediaCodec,
MediaMuxer, AudioRecord and `java.net` are all in the platform. JUnit is test-only and never
reaches the APK.

**Hardware does the heavy lifting.** H.264 and AAC come from the phone's hardware encoders.
The only software work is the live JPEGs, and they are made only while someone is watching.

**Bounded everywhere.** A slow viewer drops frames instead of growing memory; the recordings
never pass the cap or the free-space floor; a clip interrupted by a kill is hidden, not served.

**Honest status.** The screen shows what is actually happening — recording, or the reason not.

## Requirements

- JDK 17 or newer
- Android SDK with platform 34 and build-tools 34.0.0
- A device on Android 11+ (API 30) with a rear camera

## Build

```bash
git clone <this-repo>
cd node01-afterlife
./gradlew testDebugUnitTest assembleDebug     # gradlew.bat on Windows CMD/PowerShell
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`. Point Gradle at your SDK by
creating `local.properties` (gitignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

## Install

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.node01.afterlife android.permission.CAMERA        # or tap Allow
adb shell pm grant dev.node01.afterlife android.permission.RECORD_AUDIO
```

> **On MIUI**, sideloading is refused with `INSTALL_FAILED_USER_RESTRICTED` until you turn
> **Developer options → Turn on MIUI optimization → OFF**. This is not an AOSP permission
> problem and needs no Mi account. See [engineering notes](docs/ENGINEERING-NOTES.md).

## Configure

Everything configurable is a constant — there is no settings screen, by design.

| What | Where |
|---|---|
| Recording size, bitrate and frame rate; live-view size, rate and JPEG quality | `Camcorder.kt` |
| Clip length, size cap, free-space floor | `LoopRecorder.kt` |
| Port | `HttpServer.kt` |
| Screen brightness, tap-to-undim time, camera retry delay | `NodeActivity.kt` |
| Colours and type sizes | `res/values/colors.xml`, `res/values/dimens.xml` |

The video sizes are fixed for this phone's rear camera. Another phone may need them changed.

## Limits

- Recording stops whenever NODE is not in front — the power button included. Keeping it going
  with the screen off would need a foreground service and a permanent notification.
- Sound in the browser runs about 0.2 s behind real time, roughly matching the video. On an
  iPhone the ring/silent switch mutes it.
- A live tab with sound on holds two of the six connections a browser allows per site, so a
  download can stall behind three or more of them.
- Mount the phone so the text on its screen reads the right way up. Turned the other way,
  the picture is upside down: there is no rotation setting.

## Project structure

```
app/src/main/java/dev/node01/afterlife/
├── NodeActivity.kt    window setup, permissions, start/stop, retry, status and address
├── Camcorder.kt       rear camera + microphone → H.264/AAC encoders, live JPEG + PCM
├── LoopRecorder.kt    5-minute MP4 clips, oldest-first pruning
├── HttpServer.kt      LAN server: live page, MJPEG, PCM audio, recordings
└── Hub.kt             bounded fan-out of live chunks to viewers
app/src/main/assets/
└── index.html         the live page: video, sound, link to recordings
```

The app icon is a vector, so the repository carries no binary image assets other than the
Gradle wrapper.

## Notes

Building against stock MIUI turned up a number of non-obvious platform behaviours — force-dark
silently inverting bright drawing, blocked sideloads, blocked synthetic input and window
relayouts surfacing the system bars. These are documented in
**[docs/ENGINEERING-NOTES.md](docs/ENGINEERING-NOTES.md)**.
