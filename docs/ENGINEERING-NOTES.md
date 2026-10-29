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
