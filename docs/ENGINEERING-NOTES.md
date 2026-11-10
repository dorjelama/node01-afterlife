# Engineering notes

Hard-won findings from building NODE against stock MIUI on a Redmi Note 8. Most of these cost
real debugging time and are not obvious from the symptoms, so they are recorded here rather
than in the README.

---

## MIUI force-dark eats bright drawing

**Symptom.** The white clock hands rendered nearly invisible, while the calendar in the same
frame looked perfect. Measured: a solid `#FFFFFFFF` rect came out as `#1B`, and `#FF858585` in
the *same draw pass* was correct at `#82`. Brighter input produced dimmer output — `232 → 44`,
then `255 → 27` — so it was never gamma, alpha, or a colour-resource mistake. Logging the
resolved paint colours at runtime confirmed `hour=FFFFFFFF min=FFFFFFFF hub=FFFFFFFF`.

**Cause.** The device runs with system dark mode on (`ui_night_mode=2`) and Android's
force-dark pass inverts light app content in the render thread. `drawText` is treated
differently from fills and strokes, which is exactly why the calendar was unaffected and only
the dial broke.

**Fix.** Opt out in the theme. NODE is already a dark design:

```xml
<item name="android:forceDarkAllowed">false</item>
<item name="android:isLightTheme">false</item>
```

**Do not remove those two lines.** Verify the environment with:

```bash
adb shell 'cmd uimode night'      # "Night mode: yes" means force-dark is live
```

**Dead end worth recording.** `setLayerType(LAYER_TYPE_SOFTWARE, null)` appeared to fix it and
was wrong — it merely dodged the force-dark pass by accident, then regressed on the next build.
The diagnosis looked like a hardware-canvas colour fault for several rounds because of this.

---

## MIUI blocks `adb install`

**Symptom.** `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`.

**Not an AOSP permission problem** — verified on this device that `install_non_market_apps=1`
and user restrictions are `none`, yet installs were still refused. `pm install` from
`/data/local/tmp` and installer spoofing with `-i` are both rejected too. It is MIUI's own
PackageManager hook, gated behind *Install via USB*, which wants a signed-in Mi account.

**Fix, confirmed working.** *Settings → Additional settings → Developer options →
"Turn on MIUI optimization" → OFF*. `adb install` then succeeds directly, with no Mi account,
no root and no unlocked bootloader.

**Fallback.** Push to `/sdcard/Download/` and tap the APK in the device's file manager.

---

## MIUI blocks synthetic input

`adb shell input` fails with `SecurityException: INJECT_EVENTS`, even with MIUI optimization
off. UI interaction cannot be scripted over adb on this device — taps and swipes must be tested
by hand.

`adb shell input keyevent KEYCODE_WAKEUP` is worse: it exits cleanly and does nothing. The
screen stays asleep, so check `dumpsys power | grep mWakefulness` rather than trusting it.

---

## MIUI blocks `pm grant`

`adb shell pm grant <pkg> android.permission.CAMERA` fails with
`SecurityException: grantRuntimePermission: Neither user 2000 nor current process has
android.permission.GRANT_RUNTIME_PERMISSIONS`. MIUI gates it behind *USB debugging (Security
settings)*, which wants a Mi account. The permission has to be granted by tapping **Allow** on
the phone. A fresh install also waits behind the lock screen until someone unlocks it.

---

## Wi-Fi power save starves the LAN server

**Symptom.** With NODE in front and the screen on, the first request after a quiet spell took
1.5–4 s to connect, or timed out, while the very next one took 5 ms. Ping from the PC lost half
its packets, with `Destination host unreachable` reported by the PC itself: its ARP requests
were going unanswered.

**Cause.** The phone's Wi-Fi sleeps between beacons whenever it is idle, screen on or not.
Fine for a phone, fatal for a server that a Claude Code hook gives two seconds.

**Fix.** Hold a `WIFI_MODE_FULL_LOW_LATENCY` lock (needs only the normal `WAKE_LOCK`
permission) from `onStart` to `onStop`. Android honours it only for the foreground app with the
screen on, which is exactly when NODE serves. Measured after 20 s idle: connect fell from
timeouts to 3–120 ms. `adb shell dumpsys wifi | grep WifiLock` shows it held as `type=4`.

**Then use 2.4 GHz.** Even with the lock active (`dumpsys wifi` counted 28 minutes of low-latency
mode), on this router's 5 GHz network the phone stayed associated yet went unreachable for
~20 s at a time, three times a minute — no disconnect in its log, and the wired PC-to-router
path lossless. One minute of a request every 2 s: **20/30** delivered on 5 GHz
(`dorjelama_wfn_5`, 5805 MHz) against **29/30** on 2.4 GHz (`dorjelama_wfn_2`, 2417 MHz), the
single miss being the first request after switching. Keep the phone on 2.4 GHz and turn off
auto-connect for the 5 GHz network, or Android drifts back to it.

---

## Offline speech: the packs you download are not the ones used

**Symptom.** With *English (US)* and *English (UK)* downloaded under Google app → Settings →
Voice → Offline speech recognition, every `SpeechRecognizer` request with
`EXTRA_PREFER_OFFLINE` still failed within a tenth of a second with error **13**
(`ERROR_LANGUAGE_UNAVAILABLE`, named only from Android 12 but sent on 11 too) — and **12**
(`ERROR_LANGUAGE_NOT_SUPPORTED`) for `en-IN`. Each attempt made the recognizer beep.

**Cause.** The Google app's log, with tags in `com.google.android.googlequicksearchbox`, shows
the request going to its newer on-device engine, SODA, which keeps its own copy of each model:

```
I/fohv: Offline recognizer - start listening
I/fohv: Initialize Soda [locale: …]
I/SodaLPDirGenerator: Found matching installed packs: …
W/SodaLPDirGenerator: Returning no LP, as MDD has not downloaded this pack yet.
E/fohv: Failed to get language pack of required locale
```

MDD is the Google app's background downloader. It fetches the SODA pack on its own schedule —
typically on Wi-Fi, charging and idle — and NODE, holding the screen on, keeps the phone from
ever being idle. Its jobs are anonymous (`BackgroundTasksJobService`, WorkManager), so there
is no single job to run by hand.

**What to do.** Leave NODE, screen off, on charger and Wi-Fi for a while (overnight), so MDD
can run; turning on Gboard's offline or faster voice typing may fetch the same model sooner.
Read the log above again to see whether the pack has arrived. Do not drop
`EXTRA_PREFER_OFFLINE` to make it work: the recognizer would then send speech to Google.

---

## Front camera facts (Redmi Note 8)

From `adb shell dumpsys media.camera`, camera `1`, facing front:

| Key | Value |
|---|---|
| `availableFaceDetectModes` | `[0 1]`: OFF and SIMPLE, no FULL. Up to 10 faces |
| `sensor.orientation` | 270 |
| `supportedHardwareLevel` | 3 (LEVEL_3) |
| `aeAvailableTargetFpsRanges` | `[15 15] [20 20] [7 24] [24 24]` |
| Smallest YUV sizes | 320×240, 176×144, 160×120 |

The companion runs it at 320×240 and a fixed 15 fps: enough for SIMPLE face detection, which
the camera computes on the full sensor regardless of the stream size. With the activity locked
to landscape the display rotation is 90°, so sensor orientation plus display rotation is 360°:
no turn, only the front-camera mirror.

---

## The eyes' glow is affordable

The glow is a `BlurMaskFilter` on the same path that is filled, which hardware-accelerated
canvases support from API 28. The path changes every frame of an animation, so the blur is
redone every frame too. Measured with `adb shell dumpsys gfxinfo dev.node01.companion` over
670 frames of hearts, spirals, bouncing and glancing at 2340x1080: median 8 ms, 99th percentile
15 ms, 0.75% janky frames; GPU time 9 ms median and 17 ms at the 99th percentile. Both eyes
(and both hearts) go in **one** path, so there is one blur per frame, not two. A settled face
draws no frames at all, so none of this costs anything at rest.

---

## Window relayout surfaces the system bars

**Symptom.** Tapping the clock or calendar made the back/home/recents bar appear and stay.

**Cause.** The tap handler changes brightness by reassigning `window.attributes`. That forces a
window relayout, and *the relayout* is what surfaced the bars — the tap was never requesting
them.

**Fix.** Three parts, all in `NodeActivity`:

1. Only write brightness when it actually changes, so a repeat tap causes no relayout.
2. Treat a touch as a possible bar request only if it *starts* in the bottom 12% of the screen.
3. On an inset-visible event with no bottom-edge touch, re-hide on the next frame; with one,
   allow 3s so Home and Recents are reachable.

Note that `onWindowFocusChanged` alone is not enough — transient bars are not a focus change,
so nothing re-hides them.

---

## USB enumeration failure

The phone enumerated as `USB\VID_0000&PID_0002`, "Device Descriptor Request Failed" — it never
identified itself. This is below the driver layer: Windows had previously enumerated
`VID_18D1&PID_4EE2` (MTP+ADB) on this machine, so drivers were never the problem.

Causes, in likelihood order: debris in the USB-C port, a damaged cable, a charge-only cable, or
a bad port. A true charge-only cable usually produces *no* device entry at all, so a
`VID_0000` entry that fails mid-enumeration points at a marginal physical connection.

Reseating the cable resolved it. **USB is far more reliable than wireless debugging here** —
MIUI switched wireless debugging off twice unprompted mid-session, each time silently, leaving
a stale mDNS entry advertising a dead port. After an `adb kill-server`, mDNS returning nothing
is the tell.

---

## Windows / Git Bash gotchas

- **Device paths get mangled.** MINGW rewrites `/sdcard/...` into
  `C:/Program Files/Git/sdcard/...`. Export `MSYS_NO_PATHCONV=1` for any `adb push`/`shell`
  using an absolute device path.
- That same variable stops conversion of *local* paths, so `adb pull` destinations must then be
  written Windows-style. Both forms in one command line is normal.
- `adb` needs its `.exe` suffix in Git Bash, and `./gradlew` needs the `./`.
- Single-quote `shell` arguments containing `$` or a pipe, or bash expands them locally first.
- **XML comments cannot contain `--`.** This broke the build twice, once from ASCII art of a
  clock hand (`o----`).
- If the project lives in OneDrive, exclude `build/` and `.gradle/` from sync — it locks files
  mid-build and produces confusing intermittent failures.
