# NODE Companion

**An old phone, rebuilt as a face on your desk.**

A Redmi Note 8 running stock MIUI — bootloader locked, no root, no custom recovery — turned
into a small desk companion. Two dot-matrix eyes, cyan dots lit on a panel of faint ones like
an LED display, notice when you sit down, follow you, doze when you leave and sleep when the
lights go off. They wink, tilt, bounce, turn into hearts when you say thank you and go woozy
when you shake the phone. When Claude Code on your PC needs your
approval, they turn amber and chirp, so you can step away from the keyboard and still know.
Tap it or wave at it and it listens: ask the time, set a timer, have it remind you — in
English or Nepali, with no cloud AI.

<p align="left">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2011%2B%20(API%2030)-000000">
  <img alt="language" src="https://img.shields.io/badge/Kotlin-2.0.21-000000">
  <img alt="dependencies" src="https://img.shields.io/badge/runtime%20dependencies-zero-000000">
  <img alt="permissions" src="https://img.shields.io/badge/permissions-INTERNET%20CAMERA%20RECORD__AUDIO%20WAKE__LOCK-000000">
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
| **Feels the desk** | Knock on the desk and it startles. Shake the phone and it goes woozy: its eyes loll round, one up as the other drops, until it shakes it off |
| **Is pettable** | Stroke a finger across the screen and its eyes turn to beating hearts: "Hehe." A stroke never opens the microphone, so you can fuss over it without talking to it |
| **Plays** | Say hi and it winks; say "thank you" and its eyes turn to beating hearts. While you sit there it now and then winks, tilts its head or squints; with nobody there it looks around the room; asleep, Z's float up. Listening, it tilts its head. Every animation and expression can also be played from the page |
| **Listens** | Tap the screen or wave over its top edge, wait for the blip, and speak — English or Nepali. Nothing listens until you do. See [Voice](#voice) |
| **Talks** | A few short words of its own: "Welcome back!" ("Good morning!" once a morning), "Hey! Claude needs you.", "All done!", "Whoa!" at a knock, "Good night." when the lights go off. At most one unprompted line every 20 seconds, never in a dark room; `TALK` in `NodeActivity.kt` turns it off |
| **Claude Code** | Through Claude Code hooks on your PC. While Claude works the eyes narrow and look toward the PC. When Claude needs approval or has a question: amber, wide, an amber **!!** beside them, `NEEDS YOU`, one chirp. When it finishes: a happy bounce, then **^ ^** eyes, `DONE`, a softer chirp — held until you have actually looked |
| **Desk notes** | Send a short note from any browser, now or in a few minutes — or say "remind me in 10 minutes to stretch". It shows in amber and is read out; tap or wave to dismiss it |
| **Counts a timer down** | While a timer or focus session runs, the time left ticks in grey dot digits in the top-right corner — `24:59`, and whole minutes past an hour. Glance and see how long is left without asking |
| **Tells the time in the dark** | Knock the desk in a dark room and the time appears in dots where the eyes are, for six seconds, with the backlight barely lifted and not a sound |
| **Watches its own battery** | A phone that lives on a charger swells quietly. At **45°C** it goes amber with `BATTERY HOT · 47°C` until it is properly cool again (under 42°C, so it cannot flicker), and unplugged at **20%** it says `UNPLUGGED · 18%`. One chirp as either starts, not one per degree |
| **Says what you send** | `POST /say` with `text=…`, from the page or one line of curl in a script, and NODE reads it out once: "Build done." Grey under the eyes, nothing to dismiss, quiet in a dark room. Nepali text is spoken in Nepali |
| **Session %** | How much of your 5-hour Claude session limit is used, as `/usage` shows it: just the number, such as `42%`, in grey dot digits in the top-left corner. It comes from Claude Code's status line, which gets it only on Pro and Max plans and only runs in the terminal version (not the VS Code panel); the page has the lines to add to your status line script. They send the percentage and when the window resets, nothing else. After the reset it shows `0%`. At **80%** and **95%** it says so once, and you can ask "how much of my session is left?" |

Amber means one thing: something needs you. Everything else is cyan dots on black.

## Use it

1. Build and install (below), open **NODE Companion**, allow the camera and the microphone.
2. **Hold a finger on the screen** for a moment to see its address for 30 seconds, such as
   `http://192.168.1.50:8081/k7m2x9qa4c/`.
3. Open that address on any phone or PC on the same Wi-Fi. The page sends desk notes, has NODE
   say a line out loud, plays the face's animations, has a **Try it** row to test the Claude
   states, and shows the Claude Code hooks and the status line lines for the session %, with
   the address already filled in.

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
    "PostToolUseFailure":[{ "hooks": [{ "type": "command", "async": true, "command": "curl.exe -s -m 2 -H Content-Type:application/json --data-binary '@-' http://192.168.1.50:8081/k7m2x9qa4c/claude" }] }],
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
- `PostToolUseFailure` — a command that exited non-zero, an edit that would not apply —
  gets a **sad face for a moment**, and nothing else: no chirp, no words, and at most one
  every 30 seconds. Failures are ordinary work. Stopping a tool yourself (`is_interrupt`)
  is not one.
- Working for more than 20 minutes without a pause shows `STILL WORKING · 23m` in grey, so a
  long job is visibly alive rather than possibly stalled. An approval in the middle starts the
  count again.
- Several sessions at once: the most urgent wins and the caption counts the rest (`+1`).
- Hooks are best-effort, so nothing is claimed forever: "working" with no event for 20
  minutes is dropped rather than shown as still busy.
- Give the phone a **DHCP reservation** in your router, or its address — and these hooks —
  will drift. Run `/hooks` in Claude Code to check they loaded.

## Voice

**Tap the screen or wave over its top edge**, wait for the blip, and say one sentence. What it
hears appears under the eyes as you speak; the answer is shown and spoken. Tap again to stop
listening. There is no wake word and nothing listens in the background. (A hands-free mode,
with or without "Hey Buddy", is still in the code, switched off: `WAKE` in `NodeActivity.kt`.)

| Say | Or in Nepali | NODE |
|---|---|---|
| "What time is it?" | "कति बज्यो?" | Tells you |
| "What's the date?" | "आज कति गते?" | Gregorian and Bikram Sambat |
| "Set a timer for 5 minutes" | "पाँच मिनेटको टाइमर" | Rings and says so when it runs out |
| "Focus" / "Focus for 50 minutes" | "फोकस" | 25 minutes unless you say otherwise, then tells you to take a break |
| "Cancel the timer" | "टाइमर रद्द गर" | |
| "Remind me in 10 minutes to stretch" | "दस मिनेटपछि पानी पिउन सम्झाउनु" | Shows and reads it out then |
| "Take a note: buy milk" | "नोट दूध किन्नु" | A desk note, shown now |
| "What is Claude doing?" | "Claude के गर्दैछ?" | Working, needs you, or done — and where |
| "How much of my session is left?" | "सेसन कति बाँकी छ?" | The 5-hour window: the percentage used, and when it resets |
| "Go to sleep" / "Wake up" | "सुत" / "उठ" | Eyes closed and dim until a tap; alerts still wake it |
| "Speak Nepali" / "English" | "नेपालीमा बोल" / "अंग्रेजीमा बोल" | Which language it listens for first |
| "Help" | "मद्दत" | Lists these |
| "Hi" / "Hello" | "नमस्ते" | Says hi back, with a wink |
| "Good morning" / "Good afternoon" / "Good evening" | "शुभ प्रभात" / "शुभ दिन" / "शुभ साँझ" | Says it back: "Good morning!" |
| "Nice to meet you" / "Good to see you" | "भेटेर खुसी लाग्यो" | "Nice to see you too!" |
| "I'm back" | "म आएँ" | "Welcome back!" |
| "How are you?" / "What's up?" | "कस्तो छ?" | "I'm good, thanks! How are you?" — and "I'm fine" gets "Glad to hear it!" |
| "What's your name?" | "तिम्रो नाम के हो?" | "I'm NODE, your desk buddy." |
| "Bye" / "See you" | "बाइ" | "Bye! See you soon." |

It answers in the language you spoke. There is no AI behind it: a fixed set of things,
matched by keyword, so anything else gets "Sorry, I didn't catch that".

**Offline first, online if need be.** NODE asks the phone's speech recognizer to work offline,
which needs the Google app's offline model for the language (Google app → profile picture →
Settings → Voice → Offline speech recognition; the recognizer then fetches its own copy in the
background, on Wi-Fi and charging while the phone is idle). Until that model is there, NODE
uses Google's online recognition instead: the sentence goes to Google to be turned into text,
and the caption says `· online` while it listens. It tries offline again every ten minutes.
Spoken answers use a voice installed on the phone where there is one; Nepali, with none here,
uses Google's online voice. See [engineering notes](docs/ENGINEERING-NOTES.md).

## Privacy

- **Nothing the camera sees leaves the phone.** Frames are handed back to the camera unread;
  only the rectangle of the largest face is used, as a gaze direction. Nothing is stored,
  served or logged, and the server has no camera route.
- **The microphone is open only when you ask.** After a tap or a wave, for one sentence. There
  is no wake word and nothing listens in the background. Recognition is offline when the
  phone has the model and online through Google when it does not, and the screen says
  which; see [Voice](#voice).
- **No cloud AI.** The voice assistant is a fixed set of commands, not an AI. Apart from online
  speech when the offline model is missing, nothing is sent anywhere but your own LAN.
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

**A still face costs nothing.** The eyes redraw only while they move. A frame decides which
of the panel's ~3,000 dots light, draws those in one call over the unlit grid (drawn once per
screen size), and softens the same on/off map, a pixel per dot, into the glow. Animations are a
function of their own progress, and blinks, glances and fidgets are single delayed callbacks,
not a frame loop.

**Zero runtime dependencies.** Camera2's hardware face detection, `SensorManager`,
`SpeechRecognizer`, `TextToSpeech`, `AudioTrack` and `java.net` are all in the platform. The
chirps are synthesized at start-up, so the repo carries no sound files. JUnit is test-only and
never reaches the APK.

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
| Dot grid and dot size, eye size, spacing and glow, how far they move, blink, glance and fidget timing, how each animation moves | `FaceView.kt` |
| Expression shapes, and the list of animations | `Eyes.kt` |
| Gaze direction flips for another phone or mount | `Presence.kt` (`GAZE_FLIP_X/Y`) |
| Knock and shake sensitivity, wave timing, how dark is dark | `Senses.kt` |
| Chirp pitches and loudness | `Chirp.kt` |
| Voice commands, their wording and replies | `Assistant.kt` |
| What it says unprompted, and how often | `Chatter.kt` |
| Voice pitch and speed | `Speaker.kt` |
| How long "working" and "done" last | `ClaudeSessions.kt` |
| Port, body limits | `HttpServer.kt` |
| Brightness levels, tap-to-undim time | `NodeActivity.kt` |
| Colours and type sizes | `res/values/colors.xml`, `res/values/dimens.xml` |

## Limits

- It cannot see in a dark room, so in the dark it sleeps rather than pretending to follow you.
- Everything stops whenever NODE is not in front — the power button included.
- Only one Claude Code PC is expected; sessions from several PCs work, but share one face.
- No reminders at a set clock time yet: a note is due now or in up to 24 hours from sending.
- No wake word: you tap or wave to talk to it, by design.
- Voice understands a fixed set of phrasings and greetings, not free conversation.

## Project structure

```
app/src/main/java/dev/node01/afterlife/
├── NodeActivity.kt    window, permissions, start/stop, gathers inputs, applies the look
├── Mood.kt            the personality: inputs → expression, gaze, caption, brightness
├── FaceView.kt        the dot-matrix panel: draws and animates the eyes
├── Eyes.kt            expression shapes, easing, the animations
├── Presence.kt        front camera face detection → gaze
├── Senses.kt          knock, shake, wave, dark room
├── ClaudeSessions.kt  Claude Code sessions from hook events
├── JsonFields.kt      reads four fields from a hook event, tolerating truncation
├── Assistant.kt       voice commands: what was said → what to do → what to say, EN + NE
├── Listener.kt        one sentence through the phone's speech recognizer, offline preferred
├── Speaker.kt         spoken answers, with installed voices only
├── Chatter.kt         its small talk, and the rules that keep it from chattering
├── BsDate.kt          Bikram Sambat calendar
├── Inbox.kt           desk notes, reminders and timers
├── Chirp.kt           the sounds
└── HttpServer.kt      LAN server: page, hook events, notes
app/src/main/assets/
└── index.html         the page: desk notes, Claude Code setup, test buttons
```

## Notes

Building against stock MIUI turned up a number of non-obvious platform behaviours. These are
documented in **[docs/ENGINEERING-NOTES.md](docs/ENGINEERING-NOTES.md)**.
