# NODE Companion

**An old phone, rebuilt as a face on your desk.**

A Redmi Note 8 running stock MIUI — bootloader locked, no root, no custom recovery — turned
into a small desk companion. Two dot-matrix eyes, lit on a panel of faint dots like an LED
display, notice when you sit down, follow you,
doze when you leave and sleep when the lights go off. When Claude Code on your PC needs your
approval, they turn amber and chirp, so you can step away from the keyboard and still know.

<p align="left">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2011%2B%20(API%2030)-000000">
  <img alt="language" src="https://img.shields.io/badge/Kotlin-2.0.21-000000">
  <img alt="dependencies" src="https://img.shields.io/badge/runtime%20dependencies-zero-000000">
  <img alt="permissions" src="https://img.shields.io/badge/permissions-INTERNET%20CAMERA%20WAKE__LOCK-000000">
  <img alt="cloud" src="https://img.shields.io/badge/cloud%20AI-none-000000">
</p>

It installs as its own app, **NODE Companion** (`dev.node01.companion`), next to the NODE
webcam (`dev.node01.afterlife`, on `feature/webcam`). NODE began life as a desk clock; that
version lives on at `9a42e35`.

---

## What it does

| | |
|---|---|
| **Sees you** | The front camera's own face detection, done by the camera hardware. The eyes follow you, say hello with a double blink when you come back after a while, and look around the room when you have just left |
| **Sleeps** | Away 2 minutes: sleepy and dim. Away 10 minutes, or the room dark: eyes closed, backlight at the panel minimum |
| **Feels the desk** | Knock on the desk and it startles. Wave a hand over the top of the screen, or tap it, and it is pleased |
| **Claude Code** | Through Claude Code hooks on your PC. While Claude works the eyes narrow and look toward the PC. When Claude needs approval or has a question: amber, wide, a dot-matrix **!!** beside them, `NEEDS YOU · <project>`, one chirp. When it finishes: happy **^ ^** eyes, `DONE · <project>`, a softer chirp — held until you have actually looked |
| **Desk notes** | Send a short note from any browser, now or in a few minutes. It shows in amber; tap or wave to dismiss it |

Amber means one thing: something needs you. Everything else is white dots on black.

## Use it

1. Build and install (below), open **NODE Companion**, allow the camera.
2. **Tap the screen** to see its address for 30 seconds, such as
   `http://192.168.1.50:8081/k7m2x9qa4c/`.
3. Open that address on any phone or PC on the same Wi-Fi. The page sends desk notes, has a
   **Try it** row to test the Claude states, and shows the Claude Code hooks with the address
   already filled in.

The screen stays on because Android only lets the app in front use the camera. Leaving NODE,
or pressing the power button, stops everything; coming back resumes it.

## Claude Code

Add these hooks to `~/.claude/settings.json` on the PC, merged into any `hooks` you already
have. The phone's own page shows this block with your address filled in, and copies it.

```json
{
  "hooks": {
    "UserPromptSubmit":  [{ "hooks": [{ "type": "command", "async": true, "command": "curl.exe -s -m 2 -H Content-Type:application/json --data-binary '@-' http://192.168.1.50:8081/k7m2x9qa4c/claude" }] }],
    "PermissionRequest": [{ "hooks": [{ "type": "command", "async": true, "command": "curl.exe -s -m 2 -H Content-Type:application/json --data-binary '@-' http://192.168.1.50:8081/k7m2x9qa4c/claude" }] }],
    "Notification":      [{ "hooks": [{ "type": "command", "async": true, "command": "curl.exe -s -m 2 -H Content-Type:application/json --data-binary '@-' http://192.168.1.50:8081/k7m2x9qa4c/claude" }] }],
    "PostToolUse":       [{ "hooks": [{ "type": "command", "async": true, "command": "curl.exe -s -m 2 -H Content-Type:application/json --data-binary '@-' http://192.168.1.50:8081/k7m2x9qa4c/claude" }] }],
    "Stop":              [{ "hooks": [{ "type": "command", "async": true, "command": "curl.exe -s -m 2 -H Content-Type:application/json --data-binary '@-' http://192.168.1.50:8081/k7m2x9qa4c/claude" }] }],
    "SessionEnd":        [{ "hooks": [{ "type": "command", "async": true, "command": "curl.exe -s -m 2 -H Content-Type:application/json --data-binary '@-' http://192.168.1.50:8081/k7m2x9qa4c/claude" }] }]
  }
}
```

- Every hook runs **in the background** and curl gives up after 2 seconds, so a phone that is
  asleep, away or switched off never slows Claude down.
- `PermissionRequest` and the `permission_prompt`, `elicitation_dialog` and
  `agent_needs_input` notifications mean **needs you**. The next `PostToolUse` — Claude
  carrying on after you approve — clears it. `Stop` means **done**. `SessionEnd` forgets the
  session.
- Several sessions at once: the most urgent wins and the caption counts the rest (`+1`).
- Hooks are best-effort, so nothing is claimed forever: "working" with no event for 20
  minutes is dropped rather than shown as still busy.
- Give the phone a **DHCP reservation** in your router, or its address — and these hooks —
  will drift. Run `/hooks` in Claude Code to check they loaded.

## Privacy

- **Nothing the camera sees leaves the phone.** Frames are handed back to the camera unread;
  only the rectangle of the largest face is used, as a gaze direction. Nothing is stored,
  served or logged, and the server has no camera route.
- **No microphone.** The permission is not even requested.
- **No cloud AI.** Nothing is sent anywhere but your own LAN.
- A hook event contains your prompt and the tools' input and output. NODE keeps only the event
  name, session id, project folder and notification type, reads past the rest without keeping
  it, and never logs it — but the whole event still crosses your Wi-Fi as **plain HTTP**.
  Use it on a network you trust.
- The random key in the address is the only thing between the network and NODE. Anything
  without it gets a 404. Never port-forward 8081.

## Leaving it running

- **Set NODE Companion as the default Home app and the screen lock to None.** After a reboot
  or a crash it then comes straight back without anyone touching it.
- **Keep it on the charger, with airflow,** and check the battery for swelling now and then.
  The front camera runs at its smallest size and 15 fps, far lighter than the webcam's 1080p
  recording, but an old battery held at 100% around the clock is still the real hazard.

## Design principles

**Nothing runs in the background.** The camera, sensors, server, Wi-Fi lock and the face's own
timers start in `onStart` and stop in `onStop`. A backgrounded NODE costs nothing.

**Answer at once.** Left alone, the phone's Wi-Fi dozes between packets and the first request
after a quiet spell took up to three seconds to connect — longer than a hook waits. NODE holds
a low-latency Wi-Fi lock, which Android honours only while the app is in front with the screen
on.

**A still face costs nothing.** The eyes redraw only while they move, and then only the few
hundred lit dots: the unlit grid is drawn once per screen size. Blinks and glances are single
delayed callbacks, not a frame loop.

**Zero runtime dependencies.** Camera2's hardware face detection, `SensorManager`,
`AudioTrack` and `java.net` are all in the platform. The two chirps are synthesized at start-up,
so the repo carries no sound files. JUnit is test-only and never reaches the APK.

**One pure function decides.** `Mood.decide` turns what NODE senses into what it shows, and is
unit-tested like any other function. The activity only gathers inputs and draws the answer.

**Honest status.** Amber only when something really needs you. If the camera cannot see, the
screen says why.

## Requirements

- JDK 17 or newer
- Android SDK with platform 34 and build-tools 34.0.0
- A device on Android 11+ (API 30) with a front camera that has hardware face detection.
  Check with `adb shell dumpsys media.camera` — the front camera's
  `android.statistics.info.availableFaceDetectModes` must include `1` or `2`

## Build

```bash
git clone <this-repo>
cd node01-afterlife
git switch feature/companion
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
```

Then open NODE Companion and tap **Allow** for the camera. On MIUI, `adb shell pm grant` is
refused without a Mi account, so the tap is needed.

> **On MIUI**, sideloading is refused with `INSTALL_FAILED_USER_RESTRICTED` until you turn
> **Developer options → Turn on MIUI optimization → OFF**. See
> [engineering notes](docs/ENGINEERING-NOTES.md).

## Configure

Everything configurable is a constant — there is no settings screen, by design.

| What | Where |
|---|---|
| Which side the PC is on, timings for sleepy / asleep / greeting / startle | `Mood.kt` |
| Dot grid and dot size, eye size and spacing, how far they move, blink and glance timing | `FaceView.kt` |
| Expression shapes | `Eyes.kt` |
| Gaze direction flips for another phone or mount | `Presence.kt` (`GAZE_FLIP_X/Y`) |
| Knock sensitivity, wave timing, how dark is dark | `Senses.kt` |
| Chirp pitches and loudness | `Chirp.kt` |
| How long "working" and "done" last | `ClaudeSessions.kt` |
| Port, body limits | `HttpServer.kt` |
| Brightness levels, tap-to-undim time | `NodeActivity.kt` |
| Colours and type sizes | `res/values/colors.xml`, `res/values/dimens.xml` |

## Limits

- It cannot see in a dark room, so in the dark it sleeps rather than pretending to follow you.
- Everything stops whenever NODE is not in front — the power button included.
- Only one Claude Code PC is expected; sessions from several PCs work, but share one face.
- No reminders at a set clock time yet: a note is due now or in up to 24 hours from sending.

## Project structure

```
app/src/main/java/dev/node01/afterlife/
├── NodeActivity.kt    window, permissions, start/stop, gathers inputs, applies the look
├── Mood.kt            the personality: inputs → expression, gaze, caption, brightness
├── FaceView.kt        the dot-matrix panel: draws and animates the eyes
├── Eyes.kt            expression shapes and easing
├── Presence.kt        front camera face detection → gaze
├── Senses.kt          knock, wave, dark room
├── ClaudeSessions.kt  Claude Code sessions from hook events
├── JsonFields.kt      reads four fields from a hook event, tolerating truncation
├── Inbox.kt           desk notes
├── Chirp.kt           the two sounds
└── HttpServer.kt      LAN server: page, hook events, notes
app/src/main/assets/
└── index.html         the page: desk notes, Claude Code setup, test buttons
```

## Notes

Building against stock MIUI turned up a number of non-obvious platform behaviours. These are
documented in **[docs/ENGINEERING-NOTES.md](docs/ENGINEERING-NOTES.md)**.
