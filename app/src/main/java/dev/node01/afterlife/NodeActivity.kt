package dev.node01.afterlife

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * The whole of NODE Companion: a face on the desk that notices you, tells you
 * when Claude Code on the PC needs you or has finished, and answers a few
 * spoken questions after a tap or a wave.
 *
 * Everything starts in [onStart] and stops in [onStop] -- the camera, the
 * sensors, the LAN server, speech and the face's own timers. Nothing runs in
 * the background. Android only lets the app in front use the camera, which
 * is why the screen is held on.
 *
 * All state lives on the main thread. Every input -- a face, a knock, a hook
 * event, a spoken sentence, the half-second heartbeat -- ends in
 * [reevaluate], which asks [Mood] what to show.
 */
class NodeActivity : Activity() {

    private lateinit var face: FaceView
    private lateinit var captionView: TextView
    private lateinit var statusView: TextView

    private val prefs by lazy { getSharedPreferences("node", MODE_PRIVATE) }

    /** Random per install, persisted, so the address stays put. */
    private val urlKey by lazy {
        prefs.getString(KEY_URL_KEY, null) ?: newUrlKey().also {
            prefs.edit().putString(KEY_URL_KEY, it).apply()
        }
    }

    private val page by lazy { assets.open("index.html").use { it.readBytes() } }

    private val sessions = ClaudeSessions()
    private val inbox by lazy { Inbox.decode(prefs.getString(KEY_INBOX, null)) }

    /** Claude's 5-hour session usage, last sent by the PC's status line; kept across restarts. */
    private var usage: SessionUsage? = null
    private val chirp = Chirp()
    private val chatter = Chatter()

    private var presence: Presence? = null
    private var senses: Senses? = null
    private var server: HttpServer? = null
    private var listener: Listener? = null
    private var speaker: Speaker? = null

    // The voice assistant.
    private var listening = false

    /** The sentence being listened to goes to Google to be recognized: shown under the eyes. */
    private var listeningOnline = false

    /**
     * Hands-free: a background session is listening for "Hey NODE", with no
     * caption and no beep. [woke] once the name has been heard in it, and the
     * rest of the sentence is being shown as a command.
     */
    private var wakeSession = false
    private var woke = false

    /** No background listening before this: after a network failure, or right after NODE spoke. */
    private var wakeNotBeforeMs = 0L
    private var heard = ""
    private var replyText: String? = null
    private var replyUntilMs = 0L
    private var voiceAsleep = false

    /** The language NODE listens for first and last answered in. */
    private var voiceLang = Lang.EN

    /**
     * Without it the phone's Wi-Fi dozes between packets, and the first
     * request after a quiet spell took up to three seconds to connect --
     * longer than a hook waits. The system honours a low-latency lock only
     * while the app is in front with the screen on, which NODE always is.
     */
    private val wifiLock by lazy {
        applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "node:lan")
            ?.apply { setReferenceCounted(false) }
    }

    // What NODE knows, in elapsedRealtime milliseconds.
    private var faceAtMs = 0L
    private var presentSinceMs = 0L
    private var lastAbsenceMs = 0L
    private var faceX = 0f
    private var faceY = 0f
    private var faceLogMs = 0L
    private var dark = false
    private var knockAtMs = NEVER

    /** The last finger down or up on the screen, to tell a tap's jolt from a knock on the desk. */
    private var touchAtMs = NEVER
    private var petAtMs = NEVER
    private var undimUntilMs = 0L
    private var tappedUrl = ""
    private var shownNote: Inbox.Note? = null
    private var lastLook: Look? = null

    /** What the status line would say if something were wrong; null if all is well. */
    private var problem: String? = null

    /** True only while a touch that began at a screen edge is live. */
    private var barsRequestedBySwipe = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.node_face)
        face = findViewById(R.id.face)
        captionView = findViewById(R.id.caption)
        statusView = findViewById(R.id.status)
        configureWindow()
    }

    override fun onStart() {
        super.onStart()
        val now = SystemClock.elapsedRealtime()
        // Nobody seen yet: treat the start as the moment you were last here.
        faceAtMs = now - Mood.FACE_GRACE_MS - 1
        presentSinceMs = now
        lastAbsenceMs = 0L
        lastLook = null

        face.start()
        chirp.start()
        startSenses()
        wifiLock?.acquire()
        startServer()
        voiceLang = runCatching { Lang.valueOf(prefs.getString(KEY_VOICE_LANG, null) ?: "") }.getOrDefault(Lang.EN)
        usage = SessionUsage.decode(prefs.getString(KEY_USAGE, null))
        speaker = Speaker(this)
        listener = Listener(this, voiceEvents)
        if (granted(Manifest.permission.CAMERA)) {
            startPresence()
        } else {
            problem = getString(R.string.status_no_permission)
        }
        val missing = PERMISSIONS.filterNot(::granted)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        reevaluate()
        heartbeat.run()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQUEST_PERMISSIONS && granted(Manifest.permission.CAMERA)) {
            problem = null
            startPresence()
        }
        reevaluate()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        server?.stop()
        server = null
        wifiLock?.release()
        stopPresence()
        senses?.stop()
        senses = null
        listener?.destroy()
        listener = null
        listening = false
        wakeSession = false
        woke = false
        speaker?.shutdown()
        speaker = null
        chirp.stop()
        face.stop()
        // Do not leave delayed callbacks behind when the screen goes off.
        statusView.removeCallbacks(heartbeat)
        statusView.removeCallbacks(retryCamera)
        window.decorView.removeCallbacks(reHideBars)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A transient swipe, or MIUI restoring its own chrome, can bring the
        // system bars back. Re-hide them whenever we regain focus.
        if (hasFocus) hideSystemBars()
    }

    // -- what NODE shows ---------------------------------------------------

    /** Time-based moods -- a knock wearing off, sleep coming on -- need a pulse. */
    private val heartbeat = object : Runnable {
        override fun run() {
            reevaluate()
            statusView.removeCallbacks(this)
            statusView.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun reevaluate() {
        val now = SystemClock.elapsedRealtime()
        val present = now - faceAtMs <= Mood.FACE_GRACE_MS
        val presentFor = if (present) now - presentSinceMs else 0L
        // You are looking: what NODE has been showing you counts as seen.
        if (present) sessions.acknowledge(now, presentSinceMs)

        val note = inbox.due(System.currentTimeMillis())
        if (note != null && note != shownNote) announce(note)
        shownNote = note
        if (replyText != null && now > replyUntilMs) replyText = null

        val look = Mood.decide(
            Mood.Inputs(
                present = present,
                presentForMs = presentFor,
                awayForMs = if (present) 0L else now - faceAtMs,
                lastAbsenceMs = lastAbsenceMs,
                faceX = faceX,
                faceY = faceY,
                dark = dark,
                knockAgoMs = now - knockAtMs,
                petAgoMs = now - petAtMs,
                claude = sessions.summary(now),
                note = note?.text,
                listening = listening,
                heard = heard,
                reply = replyText,
                voiceAsleep = voiceAsleep,
            ),
        )
        if (look != lastLook) {
            if (look.expression != lastLook?.expression || look.caption != lastLook?.caption) {
                Log.d(TAG, "look ${look.expression} ${look.light} caption=${look.caption != null}")
            }
            lastLook = look
            face.show(look)
            showCaption(look)
        }
        face.showUsage(usage?.shownAt(System.currentTimeMillis()))
        val undimmed = now < undimUntilMs
        setBrightness(
            when {
                undimmed -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                look.light == Light.FULL -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                look.light == Light.DOZE -> DOZE_BRIGHTNESS
                else -> MIN_BRIGHTNESS
            },
        )
        keepWakeListening(now)
        showStatus(undimmed)
    }

    /**
     * Listens for "Hey NODE" while you are at the desk: you were seen in the
     * last [WAKE_PRESENT_MS], the room is lit, NODE is not asleep, listening for
     * a command or speaking. Starts a background session when one should run
     * and none is; stops one that should not. Each session ends by itself after
     * a sentence or a silence, and the next reevaluate starts another.
     */
    /**
     * Listen again as soon as the recognizer is free, rather than on the next
     * heartbeat: every moment between sessions is a moment "Hey NODE" is missed.
     */
    private fun restartWakeSoon() {
        statusView.postDelayed({ reevaluate() }, maxOf(0L, wakeNotBeforeMs - SystemClock.elapsedRealtime()) + 20L)
    }

    /** No background listening for [ms]; one running now stops, unless it has already heard its name. */
    private fun pauseWake(ms: Long) {
        wakeNotBeforeMs = maxOf(wakeNotBeforeMs, SystemClock.elapsedRealtime() + ms)
        if (wakeSession && !woke) {
            listener?.cancel()
            wakeSession = false
        }
    }

    private fun keepWakeListening(now: Long) {
        val sp = speaker
        if (sp != null && sp.busy) wakeNotBeforeMs = maxOf(wakeNotBeforeMs, now + AFTER_SPEAKING_MS)
        val wanted = WAKE && canListen() && !dark && !voiceAsleep && !listening &&
            now - faceAtMs <= WAKE_PRESENT_MS && sp != null && !sp.busy
        // The recognizer's beep is a notification sound: keep it quiet while hands-free
        // listening is on, and put the volume back when it is not.
        if (wanted) listener?.hush() else if (!wakeSession) listener?.unhush()
        when {
            wakeSession && !wanted && !woke -> {
                listener?.cancel()
                wakeSession = false
            }
            !wakeSession && wanted && now >= wakeNotBeforeMs -> {
                wakeSession = true
                woke = false
                listeningOnline = false
                listener?.listen(voiceLang, quiet = true)
            }
        }
    }

    private fun showCaption(look: Look) {
        val text = when (val c = look.caption) {
            null -> null
            is Caption.Note -> c.text
            is Caption.NeedsYou -> getString(R.string.caption_needs_you) + more(c.others)
            is Caption.Done -> getString(R.string.caption_done) + more(c.others)
            is Caption.Heard -> c.text.ifBlank { Assistant.listening(voiceLang) } + if (listeningOnline) " · online" else ""
            is Caption.Reply -> c.text
        }
        captionView.text = text
        // Amber only for what needs you; what you said and what NODE answers stay grey.
        val alert = look.caption is Caption.Note || look.caption is Caption.NeedsYou
        captionView.setTextColor(getColor(if (alert) R.color.node_accent else R.color.node_secondary))
        captionView.visibility = if (text == null) View.INVISIBLE else View.VISIBLE
    }

    /**
     * A note, reminder or timer just came due: chirp, then read it out. Desk
     * notes stay quiet in a dark room like every other alert; a reminder or
     * timer you set by voice sounds regardless.
     */
    private fun announce(note: Inbox.Note) {
        if (note.kind == Inbox.Kind.NOTE && dark) return
        pauseWake(SPEAK_AFTER_CHIRP_MS + SPEECH_START_MS)
        chirp.attention()
        statusView.postDelayed({ speaker?.say(note.text, Assistant.langOf(note.text)) }, SPEAK_AFTER_CHIRP_MS)
    }

    private fun more(others: Int) = if (others > 0) getString(R.string.caption_more_fmt, others) else ""

    /**
     * A problem always; otherwise the address, for a while after a long press;
     * otherwise, while it is listening for its name, a dim line saying so.
     */
    private fun showStatus(undimmed: Boolean) {
        val text = problem ?: if (undimmed) tappedUrl else if (wakeSession && !woke) {
            getString(R.string.status_wake) + if (listeningOnline) getString(R.string.status_online) else ""
        } else {
            null
        }
        if (statusView.text.toString() != text.orEmpty()) statusView.text = text
        statusView.visibility = if (text == null) View.INVISIBLE else View.VISIBLE
    }

    // -- inputs ------------------------------------------------------------

    private fun onFace(x: Float, y: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - faceAtMs > Mood.FACE_GRACE_MS) {
            // Arrived. Remember how long you were gone, and say hello if it was a while.
            lastAbsenceMs = now - faceAtMs
            presentSinceMs = now
            if (lastAbsenceMs >= Mood.SEARCH_MS) {
                face.doubleBlink()
                // "Good morning" once a morning; otherwise a plain hello.
                val morning = LocalDateTime.now().hour in 4..11
                if (!(morning && chat(Moment.MORNING))) chat(Moment.HELLO)
            }
        }
        faceAtMs = now
        faceX = x
        faceY = y
        // Enough to check the eyes point the right way, not a running log of you.
        if (now - faceLogMs >= 1_000L) {
            faceLogMs = now
            Log.d(TAG, "face %.2f %.2f".format(x, y))
        }
        reevaluate()
    }

    /**
     * A jolt from the accelerometer. A tap on the screen jolts the phone too, so
     * wait a moment and drop the knock if a touch came with it -- otherwise
     * every tap startles the face and says "whoa" over the start of listening.
     */
    private fun onKnock() {
        val at = SystemClock.elapsedRealtime()
        statusView.postDelayed({
            if (kotlin.math.abs(touchAtMs - at) > TAP_JOLT_MS && !listening) {
                knockAtMs = at
                chat(Moment.KNOCK)
                reevaluate()
            }
        }, TAP_JOLT_MS)
    }

    private fun onShake() {
        face.play(Act.DIZZY)
        chat(Moment.SHAKE)
    }

    /**
     * A short spoken line for [moment], after any chirp has finished. Silent
     * when talk is off, the room is dark, NODE is listening, or it spoke too
     * recently ([Chatter] decides). Small talk is dropped while something is
     * being said; an urgent line waits its turn. True if it spoke.
     */
    private fun chat(moment: Moment): Boolean {
        val sp = speaker ?: return false
        if (!TALK || dark || listening) return false
        if (sp.busy && !moment.urgent) return false
        // Nepali small talk needs a Nepali voice; English is installed on the phone.
        val lang = if (sp.canSpeak(voiceLang)) voiceLang else Lang.EN
        if (!sp.canSpeak(lang)) return false
        val words = chatter.line(moment, lang, SystemClock.elapsedRealtime()) ?: return false
        Log.d(TAG, "chat: $moment")
        pauseWake(SPEAK_AFTER_CHIRP_MS + SPEECH_START_MS)
        statusView.postDelayed({ speaker?.say(words, lang, after = moment.urgent) }, SPEAK_AFTER_CHIRP_MS)
        return true
    }

    /** From the page: an [Act] to play, or an [Expression] to hold for a few seconds. Anything else is ignored. */
    private fun onFacePlay(name: String) {
        val key = name.trim().uppercase()
        Act.entries.firstOrNull { it.name == key }?.let { return face.play(it) }
        Expression.entries.firstOrNull { it.name == key }?.let { face.preview(it, PREVIEW_MS) }
    }

    /**
     * A wave or a tap. In order: dismiss a due note, wake from "go to sleep",
     * stop listening, or start listening. Without a microphone it just
     * pleases the face, as before voice.
     */
    private fun onPoke() {
        when {
            inbox.dismiss(System.currentTimeMillis()) -> {
                saveInbox()
                speaker?.stop()
            }
            voiceAsleep -> voiceAsleep = false
            listening -> {
                listener?.cancel()
                listening = false
            }
            canListen() -> startListening()
            else -> {
                petAtMs = SystemClock.elapsedRealtime()
                face.play(Act.JOY)
                chat(Moment.PET)
            }
        }
        reevaluate()
    }

    // -- voice -------------------------------------------------------------

    private fun canListen() = granted(Manifest.permission.RECORD_AUDIO) && listener?.available == true

    private fun startListening() {
        // A background session holds the recognizer: end it before starting this one.
        if (wakeSession) {
            listener?.cancel()
            wakeSession = false
            woke = false
        }
        speaker?.stop()
        replyText = null
        heard = ""
        listeningOnline = false
        listening = true
        chirp.listen()
        listener?.listen(voiceLang)
    }

    private val voiceEvents = object : Listener.Events {
        override fun onListening(online: Boolean) {
            listeningOnline = online
            lastLook = null // the caption's " · online" is not part of the look, so force a redraw
            reevaluate()
        }

        override fun onHeard(partial: String) {
            if (wakeSession && !woke) {
                // Overheard, not addressed to NODE: never shown.
                val rest = Assistant.wake(partial) ?: return
                // Its name: from here on this is a command, shown as you speak.
                woke = true
                listening = true
                heard = rest
            } else {
                heard = if (woke) Assistant.wake(partial) ?: partial else partial
            }
            reevaluate()
        }

        override fun onFinal(text: String) {
            val background = wakeSession
            val wasWoken = woke
            listening = false
            wakeSession = false
            woke = false
            if (!background) {
                if (text.isBlank()) reevaluate() else answer(text)
                return
            }
            // Starting the next session inside this callback finds the recognizer busy.
            pauseWake(WAKE_GAP_MS)
            val rest = Assistant.wake(text)
            when {
                // Not for NODE. Listen again as soon as the recognizer is free.
                rest == null && !wasWoken -> {
                    // TEMPORARY, to tune the wake words: the first two words only, never the sentence.
                    if (text.isNotBlank()) Log.d(TAG, "wake: no name in \"${text.split(' ').take(2).joinToString(" ")} …\"")
                    restartWakeSoon()
                }
                // "Hey NODE" and nothing more: now listen for the command, as a tap would.
                rest == null || rest.isBlank() -> {
                    pauseWake(AFTER_SPEAKING_MS)
                    statusView.postDelayed({
                        startListening()
                        reevaluate()
                    }, WAKE_GAP_MS)
                }
                else -> answer(rest)
            }
        }

        override fun onFailed(reason: String, silence: Boolean, busy: Boolean) {
            val background = wakeSession && !woke
            listening = false
            wakeSession = false
            woke = false
            if (background) {
                // Silence is the usual end of a background session. Anything else --
                // no network, say -- waits a while rather than retrying in a loop.
                pauseWake(
                    when {
                        silence -> WAKE_GAP_MS
                        busy -> WAKE_BUSY_MS
                        else -> WAKE_BACKOFF_MS
                    },
                )
                if (!silence) Log.d(TAG, "wake: $reason, pausing")
                if (silence || busy) restartWakeSoon()
            } else if (!silence) {
                // Saying nothing is not an error worth reporting; anything else is shown.
                say(reason.uppercase(), voiceLang, speak = false)
            }
            reevaluate()
        }
    }

    /** Acts on a spoken sentence, then answers it on screen and aloud. */
    private fun answer(text: String) {
        val command = Assistant.parse(text)
        var lang = Assistant.langOf(text)
        val wall = System.currentTimeMillis()
        val now = SystemClock.elapsedRealtime()
        val facts = Assistant.Facts(
            now = LocalDateTime.now(),
            claude = sessions.summary(now),
            timerRunning = inbox.timerRunning(wall),
            noteDue = inbox.due(wall) != null,
            address = wifiAddress()?.let { urlText() },
        )
        when (command) {
            is Command.Timer -> command.ms?.let { inbox.add(Assistant.timeUp(lang), it, wall, Inbox.Kind.TIMER) }
            is Command.Focus -> inbox.add(Assistant.focusDone(lang), command.ms, wall, Inbox.Kind.TIMER)
            Command.CancelTimer -> inbox.cancelTimers(wall)
            is Command.Remind -> inbox.add(command.text, command.ms, wall, Inbox.Kind.REMINDER)
            is Command.Note -> inbox.add(command.text, 0L, wall, Inbox.Kind.NOTE)?.let {
                // Shown at once; the spoken "noted" is announcement enough.
                shownNote = inbox.due(wall)
            }
            Command.Dismiss -> inbox.dismiss(wall)
            Command.Sleep -> voiceAsleep = true
            Command.Wake -> voiceAsleep = false
            Command.Thanks -> {
                petAtMs = now
                face.play(Act.LOVE)
            }
            Command.Address -> {
                tappedUrl = urlText()
                undimUntilMs = now + UNDIM_MS
            }
            is Command.Language -> lang = command.lang
            else -> Unit
        }
        saveInbox()
        Log.d(TAG, "voice: ${command.javaClass.simpleName} ($lang)")
        say(Assistant.reply(command, lang, facts), lang, speak = true)
        voiceLang = lang
        prefs.edit().putString(KEY_VOICE_LANG, lang.name).apply()
        reevaluate()
    }

    /** Shows [words] under the eyes for a while, and speaks them if a voice for [lang] is installed. */
    private fun say(words: String, lang: Lang, speak: Boolean) {
        replyText = words
        replyUntilMs = SystemClock.elapsedRealtime() + REPLY_MS
        if (speak) {
            pauseWake(SPEECH_START_MS)
            speaker?.say(words, lang)
        }
    }

    private fun onDark(value: Boolean) {
        // Lights off while you are here: say good night, then go quiet with the room.
        val present = SystemClock.elapsedRealtime() - faceAtMs <= Mood.FACE_GRACE_MS
        if (value && !dark && present) chat(Moment.GOODNIGHT)
        dark = value
        reevaluate()
    }

    /** On the main thread. Only four fields of the hook's JSON ever arrive here. */
    private fun onClaude(fields: Map<String, String>) {
        val now = SystemClock.elapsedRealtime()
        val entered = sessions.onEvent(fields, now)
        Log.d(TAG, "claude ${fields[ClaudeSessions.EVENT]} -> ${entered ?: "no change"}")
        if (!dark) {
            when (entered) {
                ClaudeSessions.State.NEEDS_YOU -> {
                    chirp.attention()
                    chat(Moment.NEEDS_YOU)
                }
                ClaudeSessions.State.DONE -> {
                    chirp.done()
                    face.play(Act.JOY)
                    chat(Moment.DONE)
                }
                else -> Unit
            }
        }
        reevaluate()
    }

    private fun onNote(text: String, inMinutes: Int) {
        if (inbox.add(text, inMinutes * 60_000L, System.currentTimeMillis(), Inbox.Kind.NOTE) != null) saveInbox()
        reevaluate()
    }

    private fun saveInbox() {
        prefs.edit().putString(KEY_INBOX, inbox.encode()).apply()
    }

    /** From the PC's status line: only the percentage and when it resets. */
    private fun onUsage(next: SessionUsage) {
        if (next == usage) return
        usage = next
        prefs.edit().putString(KEY_USAGE, next.encode()).apply()
        reevaluate()
    }

    // -- camera, sensors, server ---------------------------------------------

    private fun startPresence() {
        if (presence != null) return
        val p = Presence(this, displayDegrees(), ::onFace, ::reevaluate, ::restartPresence)
        presence = p
        try {
            p.start()
        } catch (e: Exception) {
            restartPresence(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopPresence() {
        presence?.stop()
        presence = null
    }

    /**
     * The camera failed: another app took it, a media service restarted. An
     * appliance does not sit on an error, so tear down and try again shortly.
     * Until then NODE cannot see you, and says so.
     */
    private fun restartPresence(reason: String) {
        Log.e(TAG, reason)
        problem = getString(R.string.status_error_fmt, reason)
        stopPresence()
        statusView.removeCallbacks(retryCamera)
        statusView.postDelayed(retryCamera, RETRY_MS)
        reevaluate()
    }

    private val retryCamera = Runnable {
        problem = null
        startPresence()
        reevaluate()
    }

    private fun startSenses() {
        if (senses != null) return
        senses = Senses(this, ::onKnock, ::onPoke, ::onDark, ::onShake).also { it.start() }
    }

    /** Independent of the camera: a busy port must not blind the face. */
    private fun startServer() {
        if (server != null) return
        // Requests arrive on the server's threads. Each is handed to the main
        // thread, and dropped there if this server has since been stopped.
        lateinit var http: HttpServer
        http = HttpServer(
            urlKey,
            page,
            onClaude = { fields -> mainExecutor.execute { if (server === http) onClaude(fields) } },
            onNote = { text, minutes -> mainExecutor.execute { if (server === http) onNote(text, minutes) } },
            onFace = { play -> mainExecutor.execute { if (server === http) onFacePlay(play) } },
            onUsage = { u -> mainExecutor.execute { if (server === http) onUsage(u) } },
        )
        server = http
        try {
            http.start()
        } catch (e: Exception) {
            val reason = "port ${HttpServer.PORT}: ${e.message}"
            Log.e(TAG, reason)
            problem = getString(R.string.status_error_fmt, reason)
        }
    }

    private fun displayDegrees(): Int = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun urlText(): String {
        val address = wifiAddress() ?: return getString(R.string.url_no_wifi)
        return getString(R.string.url_fmt, address, HttpServer.PORT, urlKey)
    }

    /**
     * java.net needs no permission, unlike WifiManager or ConnectivityManager.
     * Interfaces can vanish mid-query, which throws; that reads as no Wi-Fi.
     */
    private fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    private fun newUrlKey(): String {
        val random = SecureRandom()
        return String(CharArray(URL_KEY_LENGTH) { URL_KEY_ALPHABET[random.nextInt(URL_KEY_ALPHABET.length)] })
    }

    // -- window ------------------------------------------------------------

    private fun configureWindow() {
        // Draw behind the bars and the waterdrop notch so the black field runs
        // edge to edge instead of being letterboxed.
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Hold the screen for as long as NODE is in front: the camera stops the
        // moment it is not.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // A tap, or a brightness change, can surface the transient bars, and
        // that is not a focus change, so onWindowFocusChanged never fires and
        // they simply stay up. Watch the insets instead and put them away again
        // -- after a delay only if a deliberate edge swipe asked for them.
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            if (insets.isVisible(WindowInsets.Type.systemBars())) {
                v.removeCallbacks(reHideBars)
                if (barsRequestedBySwipe) {
                    v.postDelayed(reHideBars, BARS_REHIDE_MS)
                } else {
                    v.post(reHideBars)
                }
            }
            v.onApplyWindowInsets(insets)
        }

        hideSystemBars()
    }

    private val reHideBars = Runnable { hideSystemBars() }

    private fun hideSystemBars() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Window-scoped backlight override. Needs no permission and is reverted by
     * the system the moment NODE loses the foreground.
     *
     * Only written when the value actually changes: assigning window.attributes
     * forces a relayout, and a relayout surfaces the system bars on this device.
     */
    private fun setBrightness(value: Float) {
        if (window.attributes.screenBrightness != value) {
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
    }

    /**
     * True near any edge the system claims for its own gestures. In landscape
     * this device puts the navigation bar on the *right* edge, not the bottom.
     */
    private fun isEdgeTouch(x: Float, y: Float): Boolean {
        val w = window.decorView.width
        val h = window.decorView.height
        if (w == 0 || h == 0) return false
        return x <= w * EDGE_ZONE ||
            x >= w * (1f - EDGE_ZONE) ||
            y >= h * (1f - EDGE_ZONE) ||
            y <= h * EDGE_ZONE
    }

    // -- touch -------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchAtMs = SystemClock.elapsedRealtime()
                // Only a touch starting at a system edge may be reaching for the
                // navigation bar. Anything starting in the middle must never
                // surface it.
                barsRequestedBySwipe = isEdgeTouch(ev.x, ev.y)
                if (!barsRequestedBySwipe) hideSystemBars()
            }
            // In the middle: a tap talks to NODE; a long press shows its address.
            MotionEvent.ACTION_UP -> if (!barsRequestedBySwipe) {
                touchAtMs = SystemClock.elapsedRealtime()
                if (ev.eventTime - ev.downTime >= LONG_PRESS_MS) {
                    // Re-read every time: after a reboot NODE can start before Wi-Fi does.
                    tappedUrl = urlText()
                    undimUntilMs = SystemClock.elapsedRealtime() + UNDIM_MS
                    reevaluate()
                } else {
                    onPoke()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        const val TAG = "NODE"

        val PERMISSIONS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        const val REQUEST_PERMISSIONS = 1

        const val KEY_URL_KEY = "url_key"
        const val KEY_INBOX = "inbox"
        const val KEY_VOICE_LANG = "voice_lang"
        const val KEY_USAGE = "session_usage"

        /** How long a spoken answer stays under the eyes. */
        const val REPLY_MS = 8_000L

        /** How long an expression asked for from the page is held. */
        const val PREVIEW_MS = 3_000L

        /** Let the chirp finish before reading a due note aloud. */
        const val SPEAK_AFTER_CHIRP_MS = 450L

        /** Listen for "Hey NODE" while you are at the desk. False: only a tap or a wave starts listening. */
        const val WAKE = true

        /**
         * Seen this recently counts as at the desk, for listening. The camera only
         * sees a face turned toward the phone, and you mostly look at the monitor.
         */
        const val WAKE_PRESENT_MS = 2 * 60_000L

        /** After a background session fails for a reason other than silence. */
        const val WAKE_BACKOFF_MS = 30_000L

        /** So the microphone does not catch the tail of NODE's own voice. */
        const val AFTER_SPEAKING_MS = 700L

        /** Between one background session and the next. */
        const val WAKE_GAP_MS = 300L

        /** After the recognizer said it was still busy. */
        const val WAKE_BUSY_MS = 1_000L

        /** Time for speech to start, so "is it speaking" is true by the time it is checked. */
        const val SPEECH_START_MS = 1_500L

        /** A knock this close to a touch is the tap itself jolting the phone. */
        const val TAP_JOLT_MS = 350L

        /** Held this long, a touch shows the address instead of listening. */
        const val LONG_PRESS_MS = 600L

        /** Small talk -- "hi", "whoa", "Claude needs you". False keeps NODE to chirps and answers. */
        const val TALK = true

        /**
         * 32 symbols, without the easily confused 0/o and 1/l since the key is
         * typed from the phone's screen. Ten of them is 50 bits.
         */
        const val URL_KEY_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        const val URL_KEY_LENGTH = 10

        const val NEVER = -1_000_000_000L

        /** Away a while: dim. On an IPS panel the backlight is the whole power cost. */
        const val DOZE_BRIGHTNESS = 0.05f

        /** Asleep: the panel minimum. The closed eyes are still just visible. */
        const val MIN_BRIGHTNESS = 0.01f

        /** How long a long press shows the address and lifts the dim. */
        const val UNDIM_MS = 30_000L

        const val HEARTBEAT_MS = 500L

        /** Wait before reopening a failed camera; long enough not to thrash. */
        const val RETRY_MS = 5_000L

        /**
         * Grace period before the system bars are hidden again after they
         * appear. Long enough to deliberately reach Home or Recents, short
         * enough that a stray tap does not leave them sitting there.
         */
        const val BARS_REHIDE_MS = 3_000L

        /**
         * Fraction of each edge reserved for system gestures. In landscape this
         * device places the navigation bar on the right edge, so all four are
         * guarded rather than just the bottom.
         */
        const val EDGE_ZONE = 0.10f
    }
}
