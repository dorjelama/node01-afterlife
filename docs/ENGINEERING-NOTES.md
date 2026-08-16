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

## Battery: measured, not estimated

**The fuel gauge is unusable for runtime estimates.**
`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` sampled six seconds after launch reported
**~1.17A** against a true steady draw nearer **220mA**, because the CPU, GPU and the weather
fetch were all active. That produced `0H 45M LEFT` at 42% when the truth was about four hours.

NODE instead measures the observed percentage drop over wall-clock time, which captures what
DESK mode really costs including the backlight. The anchor is persisted to `SharedPreferences`
— an in-memory anchor is discarded by any process restart, and `adb install -r` alone restarts
the app (`Killing ... due to installPackageLI`), which repeatedly reset the measurement during
development.

**Measured figures on this device**

| Condition | Result |
|---|---|
| Screen lit at `0.10` brightness | ~10.3 %/hour |
| Implied runtime from full | ~9–10 hours |
| Battery real capacity (charge counter) | ~2050 mAh against a 4000 mAh design rating |

The backlight is essentially the entire power budget on an IPS panel — black pixels save
nothing. Minimum brightness plus quiet hours roughly halves daily consumption, but an
always-lit LCD cannot reach multi-day standby. Only the screen genuinely being off does that.

---

## Bikram Sambat calendar data

BS month lengths are astronomically determined and vary 29–32 days, so they cannot be computed
— they must come from a table. NODE's is anchored on **BS 2000/01/01 = AD 1943-04-14**.

**Two public tables were compared and they disagree for BS 2081–2088.** One had obviously
synthetic data past 2080 — every year ending `30, 30, 30` — and was rejected. The other carries
realistic varying lengths across 1975–2099 and is the one used.

Verified against a live Nepali calendar at five points, including *past* the divergence:

| Check | Expected | Status |
|---|---|---|
| Epoch round-trip | AD 1943-04-14 = BS 2000/01/01 | ✅ |
| Reference day | AD 2026-08-16 = 31 Shrawan 2083 | ✅ |
| Nepali New Year 2083 | AD 2026-04-14 = BS 2083/01/01 | ✅ |
| Ashoj 2083 length | 31 days, 17 Sep – 17 Oct 2026 | ✅ |
| Nepali New Year 2084 | AD 2027-04-14, Baishakh = 31 days | ✅ |

Dates outside BS 2000–2099 fall back to the Gregorian calendar rather than rendering an empty
pane.

---

## Devanagari rendering

`Typeface.MONOSPACE` carries no Devanagari glyphs; Android's font fallback supplies them, and
it works without bundling a font. Letter-spacing must be eased wherever Devanagari appears —
0.08 on the calendar title, 0.05 and 0.06 on the temperature and power lines — because wide
tracking pulls matras away from their base glyphs in a connected script.

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
