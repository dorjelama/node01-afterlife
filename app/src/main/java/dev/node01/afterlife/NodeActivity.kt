package dev.node01.afterlife

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.hardware.display.DisplayManager
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

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
    private lateinit var taskList: View
    private lateinit var taskRows: LinearLayout

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
    private val tasks by lazy { Tasks.decode(prefs.getString(KEY_TASKS, null)) }

    /** The aquarium NODE looks after; null until one is set up, on the page or by saying a job was done. */
    private var tank: Tank? = null

    /** The rear camera, during a look at the tank; the front one is closed meanwhile. */
    private var tankCam: TankCam? = null

    /** What the rear camera last made of the tank, for the model's notes; null before the first look. */
    private var tankSeen: String? = null

    /** The camera's word that the water looks greener than last week; read by the page's thread too. */
    @Volatile
    private var tankGreener = false

    /** Looks in a row with the fish unusually still: one alone could be a fish hiding behind a plant. */
    private var stillLooks = 0

    /** The face is up (not the webcam, not stopped): what a tank look hands the front camera back to. */
    private var faceOn = false

    /** The task list is open over the face until this moment; every touch on it moves it on. */
    private var tasksUntilMs = 0L

    /** Hand tracking, while that mode is on; and when a hand was last seen, to turn it off when none has been. */
    private var hands: HandTracker? = null
    private var handSeenMs = 0L

    /** The camera activities allowed on the page, and the object detector that looks for them while any are. */
    private val activities by lazy { Activities().apply { watched = Activities.decode(prefs.getString(KEY_WATCH, null)) } }
    private var objects: ObjectWatcher? = null

    /** The corner readouts -- usage, the time, the PC's load -- shown, or swiped away; kept across restarts. */
    private var detailsShown = true

    /** The page's camera view: [Presence] fills it, only while the server has a viewer on it. */
    private val camView = Hub(CAM_VIEW_QUEUE)

    /** Webcam mode: the rear camera, recording loop clips and streaming, in place of the face. */
    private var webcam = false
    private var camcorder: Camcorder? = null
    private val webcamPage by lazy { assets.open("webcam.html").use { it.readBytes() } }
    private val recorder by lazy {
        LoopRecorder(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(filesDir, "clips"))
    }

    // A couple of frames, or half a second of sound, per viewer before dropping.
    private val webcamVideo = Hub(capacity = 2)
    private val webcamAudio = Hub(capacity = 25)

    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }
    private val admin by lazy { ComponentName(this, NodeAdmin::class.java) }

    /** The PC's load as it last sent it, and when, in elapsedRealtime: shown only while it keeps coming. */
    private var pc: PcStats? = null
    private var pcAtMs = NEVER

    /** Claude's 5-hour session usage, last sent by the PC's status line; kept across restarts. */
    private var usage: SessionUsage? = null

    /** The highest usage mark already spoken, so each is said once per window. */
    private var usageWarnedAt = 0
    private val chirp = Chirp()
    private val chatter = Chatter()

    private var presence: Presence? = null
    private var senses: Senses? = null
    private var server: HttpServer? = null
    private var listener: Listener? = null
    private var speaker: Speaker? = null

    /** NODE's model on the server, while it answers; null while the server is away. See [Rephraser]. */
    private var rephraser: Rephraser? = null
    /** The model or a lookup is at work: the thinking face is up, and the model takes no new sentence. */
    private var thinking = false

    // The voice assistant.
    private var listening = false

    /** The sentence being listened to goes to Google to be recognized: shown under the eyes. */
    private var listeningOnline = false

    /**
     * Hands-free: a background session is listening, with no caption and no
     * beep. [woke] once its name has been heard in it, and the rest of the
     * sentence is being shown as a command. Without the name, a sentence is
     * acted on only if it is a command or a greeting and you are facing NODE.
     */
    private var wakeSession = false
    private var woke = false

    /** No background listening before this: after a network failure, or right after NODE spoke. */
    private var wakeNotBeforeMs = 0L
    private var heard = ""
    private var replyText: String? = null
    private var replyUntilMs = 0L
    private var voiceAsleep = false

    /** Told "be quiet": Claude's chirps and every unprompted line stay silent until "you can talk". */
    private var quiet = false

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

    /** The phone's own battery, from the sticky broadcast; null until the first one. */
    private var battery: Battery? = null
    private var knockAtMs = NEVER
    private var wincedAtMs = NEVER

    /** A knock in the dark shows the time until this moment. */
    private var clockUntilMs = 0L

    /** The last finger down or up on the screen, to tell a tap's jolt from a knock on the desk. */
    private var touchAtMs = NEVER
    private var petAtMs = NEVER
    private var undimUntilMs = 0L
    private var tappedUrl = ""
    private var shownNote: Inbox.Note? = null
    private var lastLook: Look? = null

    /** What the status line would say if something were wrong; null if all is well. */
    private var problem: String? = null

    /** Why the LAN server is not up, until a retry gets it up. Kept apart so the camera recovering does not hide it. */
    private var serverProblem: String? = null

    /** True only while a touch that began at a screen edge is live. */
    private var barsRequestedBySwipe = false

    /** How far the live touch has travelled, to tell a stroke from a tap. */
    private var strokedPx = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** Where the live touch began, to tell a swipe's direction. */
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.node_face)
        face = findViewById(R.id.face)
        captionView = findViewById(R.id.caption)
        statusView = findViewById(R.id.status)
        taskList = findViewById(R.id.tasks)
        taskRows = findViewById(R.id.task_rows)
        // A tap beside the list, not on it, closes it.
        taskList.setOnClickListener { closeTasks() }
        configureWindow()
        kiosk()
        loadRephraser()
        tank = Tank.decode(prefs.getString(KEY_TANK, null))
    }

    /**
     * Finds NODE's model on the server, off the main thread: it takes seconds. While the server is away
     * -- the laptop off, or a call just failed -- it is tried again every [MODEL_RETRY_MS].
     */
    private fun loadRephraser() {
        thread(name = "node-model", isDaemon = true) {
            val model = Rephraser()
            var up = false
            while (!isDestroyed) {
                if (!up || !model.reachable) {
                    val warm = SystemClock.elapsedRealtime()
                    // A fixed sentence, not something said, so its rewrite can be logged.
                    val rewrite = model.rephrase(Rephraser.WARM_UP)
                    val now = model.reachable
                    if (now != up) {
                        Log.d(TAG, "model: " + if (now) "warm-up \"${Rephraser.WARM_UP}\" -> \"$rewrite\" in " +
                            "${SystemClock.elapsedRealtime() - warm} ms" else "server not answering")
                    }
                    up = now
                    mainExecutor.execute { rephraser = model.takeIf { now } }
                }
                Thread.sleep(MODEL_RETRY_MS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(batteryWatch, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        getSystemService(DisplayManager::class.java).registerDisplayListener(rotationWatch, null)
        wifiLock?.acquire()
        startServer()
        val missing = PERMISSIONS.filterNot(::granted)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        webcam = prefs.getBoolean(KEY_WEBCAM, false)
        if (webcam) startWebcam() else startFace()
        heartbeat.run()
    }

    override fun onResume() {
        super.onResume()
        // Only as device owner: otherwise this would ask to pin the screen.
        if (dpm.isLockTaskPermitted(packageName)) startLockTask()
    }

    /** Everything the companion runs that the webcam must not: the front camera, the microphone, sounds. */
    private fun startFace() {
        face.visibility = View.VISIBLE
        val now = SystemClock.elapsedRealtime()
        // Nobody seen yet: treat the start as the moment you were last here.
        faceAtMs = now - Mood.FACE_GRACE_MS - 1
        presentSinceMs = now
        lastAbsenceMs = 0L
        lastLook = null

        face.start()
        chirp.start()
        startSenses()
        voiceLang = runCatching { Lang.valueOf(prefs.getString(KEY_VOICE_LANG, null) ?: "") }.getOrDefault(Lang.EN)
        usage = SessionUsage.decode(prefs.getString(KEY_USAGE, null))
        detailsShown = prefs.getBoolean(KEY_DETAILS, true)
        // Where it already stood at startup is not news: note it without saying it.
        usageWarnedAt = usage?.let { Assistant.usagePassed(it.shownAt(System.currentTimeMillis())) } ?: 0
        speaker = Speaker(this)
        listener = Listener(this, voiceEvents)
        if (granted(Manifest.permission.CAMERA)) {
            startPresence()
        } else {
            problem = getString(R.string.status_no_permission)
        }
        faceOn = true
        statusView.postDelayed(lookAtTank, TANK_FIRST_MS)
        reevaluate()
    }

    /**
     * Every [TANK_EVERY_MS], with a tank set up: the front camera hands over to the rear one for a look at the tank
     * ([TankCam], a few seconds), then takes back over. Put off a minute while you are talking to NODE or it is busy.
     */
    private val lookAtTank = object : Runnable {
        override fun run() {
            val free = tank != null && tankCam == null && presence != null && !listening && !thinking && hands == null &&
                speaker?.busy != true
            statusView.postDelayed(this, if (free) TANK_EVERY_MS else TANK_RETRY_MS)
            if (!free) return
            val now = SystemClock.elapsedRealtime()
            // Still here through the look, rather than gone and back: the front camera is blind for a few seconds.
            if (now - faceAtMs <= Mood.FACE_GRACE_MS) faceAtMs = now + TANK_LOOK_MS
            statusView.removeCallbacks(retryCamera)
            stopPresence()
            val cam = TankCam(this@NodeActivity, displayDegrees(), ::onTankLook)
            tankCam = cam
            // The front camera lets go a moment after it is closed.
            statusView.postDelayed({ if (tankCam === cam) cam.start() }, CAMERA_HANDOVER_MS)
        }
    }

    /** A look at the tank is over: the front camera back, then the look measured and kept, off the main thread. */
    private fun onTankLook(look: TankCam.Look?) {
        tankCam = null
        if (!faceOn) return
        startPresence()
        val dir = getExternalFilesDir(null)
        if (look == null || look.frames.isEmpty() || dir == null) {
            Log.d(TAG, "tank: no look")
            return
        }
        thread(name = "node-tank-look", isDaemon = true) {
            val now = LocalDateTime.now()
            val stats = TankLook.Stats(TankLook.brightness(look.frames.first()), TankLook.green(look.argb), TankLook.motion(look.frames))
            val past = File(dir, TANK_STATS).takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() }.orEmpty()
                .mapNotNull { TankLook.decode(it.drop(STAMP_CHARS)) }
            val greener = TankLook.greener(stats, past)
            val still = TankLook.still(stats, past)
            AskedLog.add(dir, now, stats.encode(), TANK_STATS)
            savePhoto(look.jpeg, now)
            Log.d(TAG, "tank: look ${stats.encode()} greener=$greener still=$still")
            mainExecutor.execute {
                tankSeen = TankLook.describe(stats, greener, still)
                tankGreener = greener
                stillLooks = if (still) stillLooks + 1 else 0
                if (stillLooks == STILL_LOOKS) {
                    inbox.add(TANK_STILL_NOTE, 0L, System.currentTimeMillis(), Inbox.Kind.NOTE)
                    saveInbox()
                    tankLog("the fish looked unusually still")
                    reevaluate()
                }
            }
        }
    }

    /** The look's picture into the tank folder, as a week-long time-lapse; older pictures go. Blocking. */
    private fun savePhoto(jpeg: ByteArray, now: LocalDateTime) {
        val dir = getExternalFilesDir(TANK_PHOTOS) ?: return
        runCatching {
            File(dir, now.format(PHOTO_NAME) + ".jpg").writeBytes(jpeg)
            val cutoff = System.currentTimeMillis() - TANK_PHOTO_DAYS * Tank.DAY_MS
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }.onFailure { Log.e(TAG, "tank: photo not kept: ${it.message}") }
    }

    /** The newest picture of the tank, for the page; on a server thread. */
    private fun tankPhoto(): ByteArray? = getExternalFilesDir(TANK_PHOTOS)?.listFiles { f -> f.name.endsWith(".jpg") }
        ?.maxByOrNull { it.name }?.let { runCatching { it.readBytes() }.getOrNull() }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQUEST_PERMISSIONS && webcam) {
            problem = null
            startWebcam()
        } else if (requestCode == REQUEST_PERMISSIONS && granted(Manifest.permission.CAMERA)) {
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
        unregisterReceiver(batteryWatch)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(rotationWatch)
        battery = null
        // Finalizes the clip in progress, so leaving NODE never costs footage.
        stopWebcam()
        stopFace()
        // Do not leave delayed callbacks behind when the screen goes off.
        statusView.removeCallbacks(heartbeat)
        statusView.removeCallbacks(retryServer)
        serverProblem = null
        window.decorView.removeCallbacks(reHideBars)
        super.onStop()
    }

    private fun stopFace() {
        faceOn = false
        statusView.removeCallbacks(lookAtTank)
        tankCam?.cancel()
        tankCam = null
        stopHands()
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
        statusView.removeCallbacks(retryCamera)
        statusView.removeCallbacks(ringAgain)
    }

    // -- webcam mode ---------------------------------------------------------

    /**
     * The phone is the companion or the webcam, never both: Android gives the
     * microphone to one user at a time, and the rear camera's 1080p encoder
     * leaves no room for the face. Stopping one before starting the other is
     * what hands the hardware over. Remembered, so a power cut brings back
     * whichever it was.
     */
    private fun setWebcam(on: Boolean) {
        if (on == webcam) return
        webcam = on
        prefs.edit().putBoolean(KEY_WEBCAM, on).apply()
        problem = null
        if (on) {
            stopFace()
            startWebcam()
        } else {
            stopWebcam()
            startFace()
        }
    }

    private fun startWebcam() {
        face.visibility = View.INVISIBLE
        captionView.visibility = View.INVISIBLE
        closeTasks()
        if (camcorder == null) {
            if (PERMISSIONS.all(::granted)) {
                val cam = Camcorder(this, recorder, webcamVideo, webcamAudio, ::restartWebcam)
                camcorder = cam
                try {
                    cam.start()
                } catch (e: Exception) {
                    restartWebcam(e.message ?: e.javaClass.simpleName)
                }
            } else {
                problem = getString(R.string.status_no_permission)
            }
        }
        reevaluate()
    }

    private fun stopWebcam() {
        camcorder?.stop()
        camcorder = null
        statusView.removeCallbacks(retryWebcam)
    }

    /** As [restartPresence]: the recognizer may also still be letting go of the microphone. */
    private fun restartWebcam(reason: String) {
        Log.e(TAG, reason)
        stopWebcam()
        problem = getString(R.string.status_error_fmt, reason)
        statusView.postDelayed(retryWebcam, RETRY_MS)
        reevaluate()
    }

    private val retryWebcam = Runnable {
        problem = null
        if (webcam) startWebcam()
    }

    /** Webcam mode's whole screen: one dim dot, or what is wrong, or the address for a while after a tap. */
    private fun showWebcam(now: Long) {
        val undimmed = now < undimUntilMs
        setBrightness(if (undimmed) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else MIN_BRIGHTNESS)
        val text = problem ?: serverProblem ?: if (undimmed) tappedUrl else "●"
        if (statusView.text.toString() != text) statusView.text = text
        statusView.visibility = View.VISIBLE
    }

    private fun onMode(to: String) {
        when (to) {
            "webcam" -> setWebcam(true)
            "face" -> setWebcam(false)
            "release" -> release()
        }
    }

    // -- kiosk ---------------------------------------------------------------

    /**
     * Once NODE is the device owner (`adb shell dpm set-device-owner`, on a
     * phone with no accounts) there is no Android left to see: no lock screen,
     * no launcher but this, and in [onResume] a locked task with no bars, Home
     * or Recents. Anywhere else this does nothing.
     */
    private fun kiosk() {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        dpm.setLockTaskPackages(admin, arrayOf(packageName))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        dpm.setKeyguardDisabled(admin, true)
        // AC, USB and wireless: the screen never sleeps on the charger, even before NODE is drawn.
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7")
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(this, NodeActivity::class.java))
    }

    /** The way out that needs no cable: gives the phone back to Android. */
    private fun release() {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        runCatching { stopLockTask() }
        dpm.setKeyguardDisabled(admin, false)
        dpm.clearPackagePersistentPreferredActivities(admin, packageName)
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(packageName)
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
        // Notes and alerts wait for the face to come back, rather than sound in a recording.
        if (webcam) return showWebcam(now)
        val present = now - faceAtMs <= Mood.FACE_GRACE_MS
        val presentFor = if (present) now - presentSinceMs else 0L
        // You are looking: what NODE has been showing you counts as seen.
        if (present) sessions.acknowledge(now, presentSinceMs)

        var note = inbox.due(System.currentTimeMillis())
        // A nudge nobody is there for is no nudge: let it come round again,
        // silently, rather than pile up for whenever you walk back in.
        if (note != null && note.repeatMs > 0 && !present) {
            inbox.dismiss(System.currentTimeMillis())
            saveInbox()
            note = inbox.due(System.currentTimeMillis())
        }
        // An overdue aquarium job, once a day, as a note: only while you are here to see it, and never over another.
        val wall = System.currentTimeMillis()
        if (note == null && present && !dark) tank?.toRaise(wall)?.let { job ->
            tank = tank?.raised(job, wall)
            saveTank()
            inbox.add(Assistant.tankDue(job, voiceLang), 0L, wall, Inbox.Kind.NOTE)
            saveInbox()
            tankLog("reminded: " + Tank.JOB_NAMES.getValue(job).lowercase())
            note = inbox.due(wall)
        }
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
                thinking = thinking,
                reply = replyText,
                voiceAsleep = voiceAsleep,
                battery = battery,
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
        face.showUsage(usage?.shownAt(System.currentTimeMillis())?.takeIf { detailsShown })
        face.showTimer(inbox.timerLeft(System.currentTimeMillis())?.let { Inbox.countdown(it) })
        val nightClock = dark && now < clockUntilMs
        val time = LocalDateTime.now().format(CLOCK_FORMAT)
        face.showClock(if (nightClock) time else null)
        // The corner time steps aside while the large one shows in the dark.
        face.showTime(if (nightClock || !detailsShown) null else time)
        // A script that has stopped sending leaves nothing behind on the face.
        face.showPc(pc?.takeIf { detailsShown && now - pcAtMs <= PC_FRESH_MS })
        // Nobody's hand for a while: back to the face, and the model unloaded, so the phone stays cool.
        if (hands != null && now - handSeenMs > HANDS_IDLE_MS) stopHands()
        if (taskList.visibility == View.VISIBLE && now >= tasksUntilMs) closeTasks()
        val undimmed = now < undimUntilMs
        setBrightness(
            when {
                undimmed -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                look.light == Light.FULL -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                // Enough to read by in a dark room, and no more.
                nightClock -> DOZE_BRIGHTNESS
                look.light == Light.DOZE -> DOZE_BRIGHTNESS
                else -> MIN_BRIGHTNESS
            },
        )
        keepWakeListening(now)
        showStatus(undimmed)
    }

    /**
     * Listen again as soon as the recognizer is free, rather than on the next
     * heartbeat: every moment between sessions is a moment a command is missed.
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

    /**
     * Listens hands-free while you are at the desk: you were seen in the
     * last [WAKE_PRESENT_MS], the room is lit, NODE is not asleep, listening for
     * a command or speaking. Starts a background session when one should run
     * and none is; stops one that should not. Each session ends by itself after
     * a sentence or a silence, and the next reevaluate starts another.
     *
     * Does nothing while [WAKE] is off, which is how NODE ships: you tap or wave.
     */
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
            is Caption.Working -> getString(R.string.caption_working_fmt, c.minutes)
            is Caption.BatteryWarning ->
                getString(if (c.hot) R.string.caption_battery_hot_fmt else R.string.caption_battery_low_fmt, c.number)
        }
        captionView.text = text
        // Amber only for what needs you; what you said and what NODE answers stay grey.
        val alert = look.caption is Caption.Note || look.caption is Caption.NeedsYou ||
            look.caption is Caption.BatteryWarning
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
        alert(note.text, Assistant.langOf(note.text))
        // An alarm is the one alert that will not be slept through.
        if (note.kind == Inbox.Kind.ALARM) statusView.postDelayed(ringAgain, ALARM_REPEAT_MS)
    }

    /** Keeps an alarm ringing until it is dismissed, which ends this by itself. */
    private val ringAgain = object : Runnable {
        override fun run() {
            if (inbox.due(System.currentTimeMillis())?.kind != Inbox.Kind.ALARM) return
            chirp.attention()
            statusView.postDelayed(this, ALARM_REPEAT_MS)
        }
    }

    /** One chirp for attention, then [words] once it has finished. */
    private fun alert(words: String, lang: Lang) {
        pauseWake(SPEAK_AFTER_CHIRP_MS + SPEECH_START_MS)
        chirp.attention()
        statusView.postDelayed({ speaker?.say(words, lang) }, SPEAK_AFTER_CHIRP_MS)
    }

    private fun more(others: Int) = if (others > 0) getString(R.string.caption_more_fmt, others) else ""

    /**
     * A problem always; otherwise the address, for a while after a long press;
     * otherwise, while a hands-free session is listening, a dim line saying so.
     */
    private fun showStatus(undimmed: Boolean) {
        val text = problem ?: serverProblem ?: if (undimmed) tappedUrl else if (wakeSession && !woke) {
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
                // "Good morning" once a morning, and then the day's one line;
                // otherwise a plain hello.
                val morning = LocalDateTime.now().hour in 4..11
                if (morning && chat(Moment.MORNING)) brief() else chat(Moment.HELLO)
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
                // A knock in a dark room is asking the time. Answer it in dots,
                // not out loud: chat() is silent in the dark anyway.
                if (dark) clockUntilMs = SystemClock.elapsedRealtime() + NIGHT_CLOCK_MS
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
     * when talk is off, NODE is listening, the room is dark (urgent lines
     * excepted: Claude needing you or finishing speaks at night), or it spoke too
     * recently ([Chatter] decides). Small talk is dropped while something is
     * being said; an urgent line waits its turn. [instead], when given, is said
     * in place of the moment's own line, in English. True if it spoke.
     */
    private fun chat(moment: Moment, instead: String? = null): Boolean {
        val sp = speaker ?: return false
        if (!TALK || quiet || listening || (dark && !moment.urgent)) return false
        if (sp.busy && !moment.urgent) return false
        // Nepali small talk needs a Nepali voice; English is installed on the phone.
        val lang = if (instead == null && sp.canSpeak(voiceLang)) voiceLang else Lang.EN
        if (!sp.canSpeak(lang)) return false
        val line = chatter.line(moment, lang, SystemClock.elapsedRealtime()) ?: return false
        val words = instead ?: line
        Log.d(TAG, "chat: $moment${if (instead != null) ", in its own words" else ""}")
        pauseWake(SPEAK_AFTER_CHIRP_MS + SPEECH_START_MS)
        statusView.postDelayed({ speaker?.say(words, lang, after = moment.urgent) }, SPEAK_AFTER_CHIRP_MS)
        return true
    }

    /**
     * The morning's one line, behind "Good morning". The weather is fetched on
     * a thread of its own with a short timeout, and the briefing is spoken
     * whether or not it arrives -- a morning hello must never wait on a
     * network. With the model, in English, the weather, what is due today and
     * the open tasks become one friendly sentence, with the thinking face up
     * while it is written. Dropped entirely if the room went dark or you
     * started talking in the meantime.
     */
    private fun brief() {
        val waiting = inbox.size
        val model = rephraser?.takeIf { it.reachable && voiceLang == Lang.EN && !thinking }
        // Gathered here: the inbox and the tasks are main-thread only.
        val day = if (model != null) dayNotes() else null
        if (model != null) thinking = true
        thread(name = "node-weather", isDaemon = true) {
            val weather = Weather.fetch()
            val friendly = model?.brief(listOfNotNull(day, weather?.let { "Weather: " + Weather.describe(it, false, Lang.EN) }).joinToString(" "))
            // Not before the "Good morning" posted with this delay: it would cut the briefing off.
            statusView.postDelayed({
                if (model != null) thinking = false
                val sp = speaker ?: return@postDelayed
                if (!TALK || dark || listening) return@postDelayed reevaluate()
                val lang = if (sp.canSpeak(voiceLang)) voiceLang else Lang.EN
                val words = friendly?.takeIf { lang == Lang.EN }
                    ?: Assistant.briefing(LocalDateTime.now(), waiting, lang, weather?.let { Weather.describe(it.now, lang) })
                replyText = words
                replyUntilMs = SystemClock.elapsedRealtime() + REPLY_MS
                pauseWake(SPEECH_START_MS)
                // Behind the "Good morning" that is very likely still playing.
                sp.say(words, lang, after = true)
                reevaluate()
            }, SPEAK_AFTER_CHIRP_MS)
        }
    }

    /** Today, for the model's morning greeting: the time, what is due before midnight, and the open tasks. */
    private fun dayNotes(): String {
        val now = LocalDateTime.now()
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val due = inbox.all.filter { it.kind != Inbox.Kind.TIMER }
            .map { it to LocalDateTime.ofInstant(Instant.ofEpochMilli(it.dueMs), ZoneId.systemDefault()) }
            .filter { (_, at) -> at.toLocalDate() == now.toLocalDate() }
            .joinToString("; ") { (note, at) -> "${note.text} at ${at.format(clock)}" }
        val open = tasks.all.map { it.title }
        return listOfNotNull(
            "It is ${now.format(clock)} on ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}.",
            due.ifEmpty { null }?.let { "You have today: $it." },
            open.takeIf { it.isNotEmpty() }?.let { "Your open tasks: ${it.take(BRIEF_TASKS).joinToString(", ")}" +
                (if (it.size > BRIEF_TASKS) " and ${it.size - BRIEF_TASKS} more." else ".") },
        ).joinToString(" ")
    }

    /** From the page: an [Act] to play, or an [Expression] to hold for a few seconds. Anything else is ignored. */
    private fun onFacePlay(name: String) {
        val key = name.trim().uppercase()
        Act.entries.firstOrNull { it.name == key }?.let { return face.play(it) }
        Expression.entries.firstOrNull { it.name == key }?.let { face.preview(it, PREVIEW_MS) }
    }

    /**
     * Stroked, rather than tapped: hearts and a pleased line. Nothing else --
     * a stroke never starts listening, so you can fuss over NODE without
     * opening the microphone. Ignored while it is already listening.
     */
    private fun pet() {
        if (listening) return
        petAtMs = SystemClock.elapsedRealtime()
        face.play(Act.LOVE)
        chat(Moment.PET)
        reevaluate()
    }

    /**
     * A wave or a tap. In order: dismiss a due note, wake from "go to sleep",
     * stop listening, or start listening for one sentence -- the only way to
     * talk to NODE. Without a microphone it just pleases the face.
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
                // A cancelled session reports nothing more, so end it here, or hands-free never restarts.
                wakeSession = false
                woke = false
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
            // No name needed while you face it: a command or a greeting is answered as it is.
            val facing = SystemClock.elapsedRealtime() - faceAtMs <= FACING_MS
            val said = Assistant.handsFree(text, facing)
            when {
                said != null -> answer(said)
                // Its name was heard as you spoke, then lost from the final text: listen for the command.
                wasWoken -> {
                    pauseWake(AFTER_SPEAKING_MS)
                    statusView.postDelayed({
                        startListening()
                        reevaluate()
                    }, WAKE_GAP_MS)
                }
                // Not for NODE. Listen again as soon as the recognizer is free.
                else -> restartWakeSoon()
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

    /**
     * Acts on a spoken sentence, then answers it on screen and aloud. Two
     * requests in one sentence are both done and answered together. The news
     * and the weather are looked up first. An English sentence the keywords
     * miss goes once to NODE's own model, if it has one: see [think].
     */
    private fun answer(text: String, rephrased: Boolean = false) {
        // "Set a timer for 10 minutes and remind me at 5 to call mum": both, answered as one.
        val said = (if (rephrased) null else Assistant.split(text)) ?: listOf(text)
        val commands = said.map { Assistant.parse(it) }
        val command = commands.first()
        var lang = Assistant.langOf(text)
        // Not after a call that just failed: keywords only until the server answers again.
        val model = rephraser?.takeIf { it.reachable }
        if (said.size == 1 && command == Command.Unknown && !rephrased && lang == Lang.EN && model != null && !thinking) {
            return think(text, model)
        }
        if (command is Command.News || command is Command.Weather) return lookUp(command, lang)
        val wall = System.currentTimeMillis()
        val now = SystemClock.elapsedRealtime()
        val facts = Assistant.Facts(
            now = LocalDateTime.now(),
            claude = sessions.summary(now),
            timerRunning = inbox.cancellable(wall),
            noteDue = inbox.due(wall) != null,
            address = wifiAddress()?.let { urlText() },
            usagePercent = usage?.shownAt(wall),
            usageResetsInMs = usage?.resetsAtMs?.minus(wall)?.takeIf { it > 0 },
            stopwatchMs = prefs.getLong(KEY_STOPWATCH, 0L).takeIf { it > 0 }?.let { wall - it }?.takeIf { it >= 0 },
            tank = tank,
        )
        if (said.size == 1 && command == Command.TankStatus && lang == Lang.EN && model != null && !thinking) {
            return tankStatus(text, model, facts)
        }
        val replies = commands.map { c ->
            when (c) {
                is Command.Timer -> c.ms?.let { inbox.add(Assistant.timeUp(lang), it, wall, Inbox.Kind.TIMER) }
                is Command.Focus -> inbox.add(Assistant.focusDone(lang), c.ms, wall, Inbox.Kind.TIMER)
                Command.CancelTimer -> inbox.cancelTimers(wall)
                is Command.Remind -> inbox.add(c.text, c.ms, wall, Inbox.Kind.REMINDER)
                is Command.RemindAt -> {
                    val due = Assistant.dueAt(facts.now, c.hour, c.minute, c.exact)
                    inbox.add(c.text, Duration.between(facts.now, due).toMillis(), wall, Inbox.Kind.REMINDER)
                }
                is Command.Alarm -> {
                    val due = Assistant.dueAt(facts.now, c.hour, c.minute, c.exact)
                    inbox.add(Assistant.wakeUp(lang), Duration.between(facts.now, due).toMillis(), wall, Inbox.Kind.ALARM)
                }
                // Wall-clock, and in prefs, so it keeps counting through a screen off.
                Command.StopwatchStart -> prefs.edit().putLong(KEY_STOPWATCH, wall).apply()
                is Command.Volume -> adjustVolume(c.up)
                is Command.Quiet -> quiet = c.on
                Command.Sing -> {
                    face.play(Act.JOY)
                    chirp.sing()
                }
                is Command.RemindEvery ->
                    inbox.add(c.text, c.everyMs, wall, Inbox.Kind.REMINDER, repeatMs = c.everyMs)
                is Command.Note -> inbox.add(c.text, 0L, wall, Inbox.Kind.NOTE)?.let {
                    // Shown at once; the spoken "noted" is announcement enough.
                    shownNote = inbox.due(wall)
                }
                Command.Dismiss -> inbox.dismiss(wall)
                is Command.TankDone -> tankDone(c.job, wall)
                Command.Sleep -> voiceAsleep = true
                Command.Wake -> voiceAsleep = false
                Command.Thanks -> {
                    petAtMs = now
                    face.play(Act.LOVE)
                }
                is Command.Greet -> face.play(
                    when (c.kind) {
                        Greeting.HOW_ARE_YOU, Greeting.FINE, Greeting.NICE, Greeting.BACK -> Act.JOY
                        else -> Act.WINK
                    },
                )
                Command.Address -> {
                    tappedUrl = urlText()
                    undimUntilMs = now + UNDIM_MS
                }
                is Command.Language -> lang = c.lang
                else -> Unit
            }
            Assistant.reply(c, lang, facts)
        }
        saveInbox()
        Log.d(TAG, "voice: ${commands.joinToString { it.javaClass.simpleName }} ($lang)")
        // Only the sentences it could not place, so the keywords can be tuned
        // against what the recognizer really returns. What it understood -- a
        // note, a reminder -- is never written down.
        if (command == Command.Unknown) Log.d(TAG, "voice: no match for \"$text\"")
        say(replies.joinToString(" "), lang, speak = true)
        voiceLang = lang
        prefs.edit().putString(KEY_VOICE_LANG, lang.name).apply()
        reevaluate()
    }

    /**
     * A sentence the keywords missed, on the model's thread with the thinking
     * face up. Rewritten into a command NODE knows, it comes back to [answer];
     * a question is looked up -- the news for what is happening now, Wikipedia
     * otherwise -- and answered from what was found; anything else said to
     * NODE gets a short reply. A question that leans on the one before, "how
     * old is he?", is first said again in full, for the search.
     */
    private fun think(text: String, model: Rephraser) {
        thinking = true
        reevaluate()
        thread(name = "node-rephrase", isDaemon = true) {
            val started = SystemClock.elapsedRealtime()
            val leans = model.inConversation && Lookup.refersBack(text)
            // "When was she born?" just after an answer is a question, whatever the model would make of it:
            // it once made it the date. Skipping it saves seconds too.
            val rewrite = if (leans && Lookup.isQuestion(text)) "ASK" else model.rephrase(text)
            val command = rewrite != null && !Rephraser.asks(rewrite) && Assistant.parse(rewrite) != Command.Unknown
            // A rewrite that is no command is taken as a question: the model sometimes rewords one. And one it
            // calls small talk is looked up anyway if it is worded as a question: "who wrote harry potter".
            val asks = !command && (rewrite != null || Lookup.isQuestion(text))
            val rewritten = SystemClock.elapsedRealtime()
            var search: String? = null
            var notes: String? = null
            val reply = when {
                command -> null
                // Not a command, not a question: small talk.
                !asks -> model.chat(text)
                else -> {
                    // A question is searched as it was said: having the model repeat it would cost seconds.
                    val q = (if (leans) model.standalone(text) else null) ?: text
                    search = q
                    // Only the search goes online: the answer is written here, on the phone.
                    notes = if (Lookup.wantsNews(q)) Lookup.newsNotes(q) else Lookup.notes(q) ?: Lookup.newsNotes(q)
                    notes?.let { model.answer(text, it, leans) }
                }
            }
            val outcome = when {
                command -> "rewrite"
                !asks -> if (reply != null) "chat" else "no reply"
                reply != null -> "answer"
                notes != null -> "notes without an answer"
                else -> "nothing found"
            }
            val tookMs = SystemClock.elapsedRealtime() - started
            // Logcat gets how long; what was said and made of it goes to the week-long asked.log.
            Log.d(TAG, "model: $outcome in $tookMs ms (rewrite ${rewritten - started}, rest ${SystemClock.elapsedRealtime() - rewritten})")
            val asked = if (search != null && search != text) "ASK \"$search\"" else "ASK"
            val made = when {
                command -> "\"$rewrite\""
                !asks -> "CHAT -> " + (reply?.let { "\"$it\"" } ?: "nothing")
                reply != null -> "$asked -> \"$reply\""
                else -> "$asked -> $outcome"
            }
            getExternalFilesDir(null)?.let { AskedLog.add(it, LocalDateTime.now(), "\"$text\" -> $made (${tookMs / 100 / 10.0} s)") }
            mainExecutor.execute {
                thinking = false
                if (isDestroyed) return@execute
                if (command) return@execute answer(rewrite!!, rephrased = true)
                // Small talk the model had nothing for: "didn't catch that", as before.
                if (!asks && reply == null) return@execute answer(text, rephrased = true)
                if (reply != null) face.play(Act.NOD)
                say(reply ?: Assistant.NOT_FOUND, Lang.EN, speak = true)
                reevaluate()
            }
        }
    }

    /** The news or the weather, fetched on a thread of its own with the thinking face up, then read out. */
    /**
     * "How is the tank?", with the model: one or two sentences written from [Tank.notes], with the thinking face
     * up. Without an answer, the fixed one.
     */
    private fun tankStatus(question: String, model: Rephraser, facts: Assistant.Facts) {
        val notes = facts.tank?.notes(facts.nowMs, camera = tankSeen, greener = tankGreener) ?: return say(Assistant.reply(Command.TankStatus, Lang.EN, facts), Lang.EN, speak = true)
        thinking = true
        reevaluate()
        thread(name = "node-tank", isDaemon = true) {
            val words = model.answer(question, notes)
            mainExecutor.execute {
                thinking = false
                if (isDestroyed) return@execute
                say(words ?: Assistant.reply(Command.TankStatus, Lang.EN, facts), Lang.EN, speak = true)
                reevaluate()
            }
        }
    }

    /** An aquarium job done, said or ticked on the page: logged, and its reminder cleared if it is showing. */
    private fun tankDone(job: Tank.Job, wall: Long) {
        tank = (tank ?: Tank.start(wall)).done(job, wall)
        saveTank()
        val showing = inbox.due(wall)?.text
        if (showing == Assistant.tankDue(job, Lang.EN) || showing == Assistant.tankDue(job, Lang.NE)) {
            inbox.dismiss(wall)
            saveInbox()
        }
        tankLog(Tank.JOB_NAMES.getValue(job).lowercase() + " done")
    }

    private fun saveTank() {
        prefs.edit().putString(KEY_TANK, tank?.encode()).apply()
    }

    /** A line in the week-long tank.log, off the main thread. */
    private fun tankLog(line: String) {
        val dir = getExternalFilesDir(null) ?: return
        thread(name = "node-tank-log", isDaemon = true) { AskedLog.add(dir, LocalDateTime.now(), line, TANK_LOG) }
    }

    /** From the page: the tank's details and how often each job comes round. Sets the tank up if there was none. */
    private fun onTankSetUp(form: Map<String, String>) {
        tank = (tank ?: Tank.start(System.currentTimeMillis())).setUp(form)
        saveTank()
        Log.d(TAG, "tank: set up")
    }

    /** For the page, on a server thread: the tank, its jobs and the last week's log, as JSON; read from prefs. */
    private fun tankJson(): String {
        val t = Tank.decode(prefs.getString(KEY_TANK, null)) ?: return JSONObject().put("tank", JSONObject.NULL).toString()
        val now = System.currentTimeMillis()
        val jobs = JSONArray(
            Tank.Job.entries.map { job ->
                JSONObject().put("job", job.name).put("name", Tank.JOB_NAMES.getValue(job)).put("every", t.every(job))
                    .put("last", t.lastMs[job] ?: JSONObject.NULL).put("due", t.dueMs(job))
            },
        )
        val (risk, why) = t.risk(now, tankGreener)
        val log = getExternalFilesDir(null)?.let { File(it, TANK_LOG) }?.takeIf { it.isFile }
            ?.let { runCatching { it.readLines() }.getOrNull() }.orEmpty().takeLast(MAX_TANK_LINES)
        return JSONObject().put(
            "tank",
            JSONObject().put("name", t.name).put("litres", t.litres).put("kind", t.kind).put("livestock", t.livestock)
                .put("jobs", jobs).put("risk", risk.name.lowercase()).put("why", JSONArray(why)),
        ).put("log", JSONArray(log)).toString()
    }

    private fun lookUp(command: Command, lang: Lang) {
        thinking = true
        reevaluate()
        thread(name = "node-lookup", isDaemon = true) {
            val found = when (command) {
                is Command.News -> Lookup.headlines(command.topic)?.let { Assistant.headlines(it) }
                is Command.Weather -> Weather.fetch()?.let { Weather.describe(it, command.tomorrow, lang) }
                else -> null
            }
            Log.d(TAG, "voice: ${command.javaClass.simpleName} ${if (found != null) "found" else "not found"} ($lang)")
            mainExecutor.execute {
                thinking = false
                if (isDestroyed) return@execute
                // What a failed lookup says needs no facts.
                val words = found ?: Assistant.reply(command, lang, Assistant.Facts(LocalDateTime.now(), null, false, false, null))
                // Headlines are English whatever was asked in.
                say(words, if (command is Command.News) Lang.EN else lang, speak = true)
                reevaluate()
            }
        }
    }

    /**
     * One step of the phone's own media volume, which is what the speech
     * engine plays through. Nothing to store: the phone remembers it.
     */
    private fun adjustVolume(up: Boolean) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
            .onFailure { Log.w(TAG, "volume: ${it.message}") }
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

    /** On the main thread. Only a handful of fields of the hook's JSON ever arrive here. */
    private fun onClaude(fields: Map<String, String>) {
        val now = SystemClock.elapsedRealtime()
        val entered = sessions.onEvent(fields, now)
        Log.d(TAG, "claude ${fields[ClaudeSessions.EVENT]} -> ${entered ?: "no change"}")
        // Dark room or not: work happens at night, and Claude finishing or waiting is worth a sound.
        if (fields[ClaudeSessions.EVENT] == "PostToolUseFailure") winced(fields, now)
        when (entered) {
            ClaudeSessions.State.NEEDS_YOU -> {
                if (!quiet) chirp.attention()
                // A permission request says what it is for: "Claude wants to delete the build folder."
                val asks = if (fields[ClaudeSessions.EVENT] != "PermissionRequest") null else Approval.describe(
                    fields[ClaudeSessions.TOOL_NAME],
                    fields[ClaudeSessions.TOOL_INPUT],
                    fields[ClaudeSessions.CWD]?.let { ClaudeSessions.projectOf(it) }.orEmpty(),
                )
                chat(Moment.NEEDS_YOU, asks)
            }
            ClaudeSessions.State.DONE -> {
                if (!quiet) chirp.done()
                face.play(Act.JOY)
                chat(Moment.DONE)
            }
            else -> Unit
        }
        reevaluate()
    }

    /**
     * A tool call failed: a brief sad face, and nothing more. Failures are
     * ordinary work -- a grep that finds nothing, a test that is meant to fail
     * -- so this never chirps or speaks, and says nothing at all more than once
     * every [WINCE_GAP_MS]. Stopping a tool yourself is not a failure.
     */
    private fun winced(fields: Map<String, String>, now: Long) {
        if (fields[ClaudeSessions.IS_INTERRUPT] == "true") return
        if (now - wincedAtMs < WINCE_GAP_MS) return
        wincedAtMs = now
        face.preview(Expression.SAD, WINCE_MS)
    }

    private fun onNote(text: String, inMinutes: Int) {
        if (inbox.add(text, inMinutes * 60_000L, System.currentTimeMillis(), Inbox.Kind.NOTE) != null) saveInbox()
        reevaluate()
    }

    /**
     * From the PC: one line to read out and show under the eyes. Unlike a desk
     * note it waits for nothing and leaves nothing behind -- no amber, nothing
     * to dismiss. Quiet in a dark room, and never over a sentence being heard.
     */
    private fun onSay(text: String) {
        val words = Inbox.clean(text) ?: return
        replyText = words
        replyUntilMs = SystemClock.elapsedRealtime() + REPLY_MS
        if (TALK && !dark && !listening) alert(words, Assistant.langOf(words))
        reevaluate()
    }

    private fun saveInbox() {
        prefs.edit().putString(KEY_INBOX, inbox.encode()).apply()
    }

    /**
     * From the page: kept until you mark it done. One chirp so you know it
     * arrived, and nothing more -- no words, no amber; a task waits for you to
     * ask for it. Quiet in a dark room and over a sentence being heard.
     */
    private fun onTask(title: String, description: String) {
        tasks.add(title, description, System.currentTimeMillis()) ?: return
        saveTasks()
        if (!dark && !listening) chirp.attention()
        if (taskList.visibility == View.VISIBLE) fillTasks()
    }

    private fun saveTasks() {
        prefs.edit().putString(KEY_TASKS, tasks.encode()).apply()
    }

    /**
     * A sideways swipe: hand tracking on or off. On, your hand shows in white
     * dots in place of the eyes whenever the camera sees it; with no hand in
     * view the face carries on. The model loads only now, and is closed again
     * when the mode goes off.
     */
    private fun toggleHands() {
        if (hands != null) return stopHands()
        val tracker = try {
            HandTracker(this, ::onHands)
        } catch (e: Exception) {
            Log.e(TAG, "hands: ${e.message}")
            return say(getString(R.string.mode_hands_failed), voiceLang, speak = false)
        }
        hands = tracker
        presence?.hands = tracker
        handSeenMs = SystemClock.elapsedRealtime()
        // A moment of text, so turning it on is visible before any hand is.
        replyText = getString(R.string.mode_hands)
        replyUntilMs = handSeenMs + MODE_CAPTION_MS
        reevaluate()
    }

    private fun stopHands() {
        presence?.hands = null
        hands?.close()
        hands = null
        face.showHands(null, 1f)
    }

    private fun onHands(points: FloatArray, aspect: Float) {
        if (hands == null) return
        if (points.isNotEmpty()) handSeenMs = SystemClock.elapsedRealtime()
        face.showHands(points, aspect)
    }

    /** From the page: the camera activities NODE may notice, by name. Kept across restarts. */
    private fun onWatch(watch: String) {
        activities.watched = Activities.decode(watch)
        prefs.edit().putString(KEY_WATCH, Activities.encode(activities.watched)).apply()
        Log.d(TAG, "activity: watching ${activities.watched.ifEmpty { null } ?: "nothing"}")
        watchObjects()
    }

    /** The object detector runs, on a frame a second, only while the camera does and some activity is allowed. */
    private fun watchObjects() {
        val want = presence != null && activities.watched.isNotEmpty()
        if (want == (objects != null)) return
        if (want) {
            objects = runCatching { ObjectWatcher(this, ::onSeen) }.onFailure { Log.e(TAG, "objects: ${it.message}") }.getOrNull()
        } else {
            presence?.objects = null
            objects?.close()
            objects = null
        }
        presence?.objects = objects
    }

    /** What a frame held: an allowed activity that has just happened gets a reaction and a line in the log. */
    private fun onSeen(found: List<Activities.Seen>) {
        if (objects == null) return
        val now = SystemClock.elapsedRealtime()
        // Only while you are there: a phone left lying in view is nobody on it. A cup at your mouth can hide your face.
        if (now - faceAtMs > ACTIVITY_PRESENT_MS) return
        val (activity, count) = activities.see(found, now, LocalDate.now()) ?: return
        Log.d(TAG, "activity: ${activity.name}, $count today")
        if (!dark) {
            face.play(
                when (activity) {
                    Doing.DRINK -> Act.JOY
                    Doing.PHONE -> Act.WINK
                    Doing.READING -> Act.LOVE
                },
            )
            chirp.done()
        }
        val line = "${activity.title} ($count today)"
        getExternalFilesDir(null)?.let { dir ->
            thread(name = "node-activity", isDaemon = true) { AskedLog.add(dir, LocalDateTime.now(), line, ACTIVITY_LOG) }
        }
    }

    /** For the page, on a server thread: the activities allowed, and today's lines of the log. */
    private fun activityJson(): String {
        val today = LocalDate.now().toString()
        val log = getExternalFilesDir(null)?.let { File(it, ACTIVITY_LOG) }?.takeIf { it.isFile }
            ?.let { runCatching { it.readLines() }.getOrNull() }.orEmpty()
            .filter { it.startsWith(today) }.takeLast(MAX_ACTIVITY_LINES)
        val watched = Activities.decode(prefs.getString(KEY_WATCH, null)).map { it.name }
        return JSONObject().put("watch", JSONArray(watched)).put("log", JSONArray(log)).toString()
    }

    /** An up or down swipe: the corner readouts shown, or hidden for a bare face. */
    private fun toggleDetails() {
        detailsShown = !detailsShown
        prefs.edit().putBoolean(KEY_DETAILS, detailsShown).apply()
        reevaluate()
    }

    /** From the PC, every few seconds: shown by the next heartbeat. */
    private fun onPc(stats: PcStats) {
        pc = stats
        pcAtMs = SystemClock.elapsedRealtime()
    }

    /** Your tasks over the face, title by title, with the address below as before. */
    private fun openTasks() {
        fillTasks()
        taskList.visibility = View.VISIBLE
        tasksUntilMs = SystemClock.elapsedRealtime() + UNDIM_MS
    }

    private fun closeTasks() {
        taskList.visibility = View.GONE
        taskRows.removeAllViews()
    }

    /** A title per task; tapping one shows what it says about itself and a way to mark it done. */
    private fun fillTasks() {
        taskRows.removeAllViews()
        val heading = if (tasks.size == 0) R.string.tasks_none else R.string.tasks_heading
        taskRows.addView(taskText(getString(heading), R.color.node_dim, R.dimen.status))
        for (task in tasks.all) {
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            if (task.description.isNotEmpty()) details.addView(taskText(task.description, R.color.node_dim, R.dimen.status))
            details.addView(
                taskText(getString(R.string.task_done), R.color.node_secondary, R.dimen.status).apply {
                    setOnClickListener {
                        tasks.complete(task.id)
                        saveTasks()
                        chirp.done()
                        fillTasks()
                    }
                },
            )
            taskRows.addView(
                taskText(task.title, R.color.node_secondary, R.dimen.caption).apply {
                    setOnClickListener {
                        details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                },
            )
            taskRows.addView(details)
        }
    }

    /** A line of the task list, styled like the caption and status under the eyes. */
    private fun taskText(words: String, colour: Int, size: Int) = TextView(this).apply {
        text = words
        setTextColor(getColor(colour))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.04f
        includeFontPadding = false
        val gap = resources.getDimensionPixelSize(R.dimen.text_gap) / 2
        setPadding(0, gap, 0, gap)
    }

    /** From the PC's status line: only the percentage and when it resets. */
    private fun onUsage(next: SessionUsage) {
        if (next == usage) return
        usage = next
        prefs.edit().putString(KEY_USAGE, next.encode()).apply()
        warnUsage(next.shownAt(System.currentTimeMillis()))
        reevaluate()
    }

    /**
     * Speaks up once as the session window passes each of
     * [Assistant.USAGE_WARN_AT]. A drop means the window reset, so the next one
     * is worth saying again. Quiet in a dark room, and never over a sentence
     * being heard.
     */
    private fun warnUsage(percent: Int) {
        val passed = Assistant.usagePassed(percent)
        if (passed <= usageWarnedAt) {
            usageWarnedAt = passed
            return
        }
        usageWarnedAt = passed
        if (dark || listening) return
        val words = Assistant.usageWarning(passed, voiceLang)
        replyText = words
        replyUntilMs = SystemClock.elapsedRealtime() + REPLY_MS
        if (TALK) alert(words, voiceLang) else chirp.attention()
    }

    // -- camera, sensors, server ---------------------------------------------

    private fun startPresence() {
        // Not while the rear camera looks at the tank: this phone cannot run both. The look hands back when done.
        if (presence != null || tankCam != null) return
        presenceDegrees = displayDegrees()
        val p = Presence(this, presenceDegrees, ::onFace, ::reevaluate, ::restartPresence, camView)
        p.hands = hands
        presence = p
        watchObjects()
        try {
            p.start()
        } catch (e: Exception) {
            restartPresence(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopPresence() {
        presence?.stop()
        presence = null
        watchObjects()
    }

    /** The display rotation [presence] was started with, to turn its frames upright. */
    private var presenceDegrees = 0

    /**
     * Turned end over end, from one landscape side to the other, the screen
     * follows without restarting anything -- so the camera must be told
     * which way is now up, or the eyes would look away from you.
     */
    private val rotationWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (presence == null || displayDegrees() == presenceDegrees) return
            Log.d(TAG, "rotated to ${displayDegrees()} degrees: camera restarted")
            stopPresence()
            startPresence()
        }
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

    /**
     * The battery broadcast is sticky, so registering it reports the current
     * state at once. A phone that lives on a charger can cook quietly for
     * months; this is the one thing NODE says about its own health.
     */
    private val batteryWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val next = Battery.of(
                tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
                level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                wasHot = battery?.hot == true,
            )
            if (next == battery) return
            val wasWrong = battery?.wrong == true
            battery = next
            // One chirp as it goes wrong, not one per degree.
            if (next.wrong && !wasWrong && !dark) chirp.attention()
            reevaluate()
        }
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
            onSay = { text -> mainExecutor.execute { if (server === http) onSay(text) } },
            onUsage = { u -> mainExecutor.execute { if (server === http) onUsage(u) } },
            onTask = { title, desc -> mainExecutor.execute { if (server === http) onTask(title, desc) } },
            onPc = { stats -> mainExecutor.execute { if (server === http) onPc(stats) } },
            cam = camView,
            webcamPage = webcamPage,
            recorder = recorder,
            video = webcamVideo,
            audio = webcamAudio,
            onMode = { to -> mainExecutor.execute { if (server === http) onMode(to) } },
            activity = ::activityJson,
            onWatch = { watch -> mainExecutor.execute { if (server === http) onWatch(watch) } },
            tank = ::tankJson,
            onTank = { form -> mainExecutor.execute { if (server === http) onTankSetUp(form) } },
            onTankDone = { job ->
                mainExecutor.execute {
                    if (server !== http) return@execute
                    val done = Tank.Job.entries.firstOrNull { it.name == job } ?: return@execute
                    tankDone(done, System.currentTimeMillis())
                    face.play(Act.JOY)
                    reevaluate()
                }
            },
            tankPhoto = ::tankPhoto,
        )
        server = http
        try {
            http.start()
            serverProblem = null
        } catch (e: Exception) {
            // The port is still held -- by a server that has not quite let go, or
            // by something else. Do not give up: an appliance retries until it is up.
            server = null
            val reason = "port ${HttpServer.PORT}: ${e.message}"
            Log.e(TAG, reason)
            serverProblem = getString(R.string.status_error_fmt, reason)
            statusView.removeCallbacks(retryServer)
            statusView.postDelayed(retryServer, SERVER_RETRY_MS)
        }
    }

    private val retryServer = Runnable {
        startServer()
        reevaluate()
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
                strokedPx = 0f
                lastX = ev.x
                lastY = ev.y
                downX = ev.x
                downY = ev.y
                // Only a touch starting at a system edge may be reaching for the
                // navigation bar. Anything starting in the middle must never
                // surface it.
                barsRequestedBySwipe = isEdgeTouch(ev.x, ev.y)
                if (!barsRequestedBySwipe) hideSystemBars()
                // Reading the list keeps it open, and lit.
                if (taskList.visibility == View.VISIBLE) {
                    tasksUntilMs = touchAtMs + UNDIM_MS
                    undimUntilMs = tasksUntilMs
                }
            }
            MotionEvent.ACTION_MOVE -> {
                strokedPx += kotlin.math.hypot(ev.x - lastX, ev.y - lastY)
                lastX = ev.x
                lastY = ev.y
            }
            // In the middle: a tap talks to NODE, a straight swipe changes mode
            // -- sideways for hand tracking, up or down for the corner readouts --
            // rubbing back and forth pets it, and a long press shows your tasks
            // and its address.
            MotionEvent.ACTION_UP -> if (!barsRequestedBySwipe) {
                touchAtMs = SystemClock.elapsedRealtime()
                val density = resources.displayMetrics.density
                val strokeDots = strokedPx / density
                val dx = (ev.x - downX) / density
                val dy = (ev.y - downY) / density
                val swipeDots = kotlin.math.hypot(dx, dy)
                when {
                    // The way back that needs no Wi-Fi: hold to be the face again; a tap shows the address.
                    webcam -> if (ev.eventTime - ev.downTime >= LONG_PRESS_MS) {
                        setWebcam(false)
                    } else {
                        tappedUrl = urlText()
                        undimUntilMs = SystemClock.elapsedRealtime() + UNDIM_MS
                        reevaluate()
                    }
                    // The open list takes its own taps and scrolls.
                    taskList.visibility == View.VISIBLE -> Unit
                    swipeDots >= SWIPE_DP && strokeDots <= swipeDots * SWIPE_STRAIGHT ->
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) toggleHands() else toggleDetails()
                    strokeDots >= STROKE_DP -> pet()
                    // Re-read every time: after a reboot NODE can start before Wi-Fi does.
                    ev.eventTime - ev.downTime >= LONG_PRESS_MS -> {
                        tappedUrl = urlText()
                        undimUntilMs = SystemClock.elapsedRealtime() + UNDIM_MS
                        openTasks()
                        reevaluate()
                    }
                    else -> onPoke()
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
        const val KEY_WEBCAM = "webcam"
        const val KEY_INBOX = "inbox"
        const val KEY_TASKS = "tasks"
        const val KEY_DETAILS = "details_shown"
        const val KEY_VOICE_LANG = "voice_lang"
        const val KEY_USAGE = "session_usage"
        const val KEY_STOPWATCH = "stopwatch_started"
        const val KEY_WATCH = "watch"

        /** The camera activities noticed, a week of them, beside asked.log; the page shows today's. */
        const val ACTIVITY_LOG = "activity.log"
        const val MAX_ACTIVITY_LINES = 50
        const val KEY_TANK = "tank"
        /** The aquarium's week: jobs done, reminders, and later what the camera made of it. */
        const val TANK_LOG = "tank.log"
        const val MAX_TANK_LINES = 30
        /** Each look's brightness, green and movement, a week of them, for [TankLook] to compare against. */
        const val TANK_STATS = "tank-stats.log"
        /** The looks' pictures, under the app's files: a week-long time-lapse of the tank. */
        const val TANK_PHOTOS = "tank"
        const val TANK_PHOTO_DAYS = 7L
        val PHOTO_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        const val TANK_FIRST_MS = 60_000L
        const val TANK_EVERY_MS = 10 * 60_000L
        const val TANK_RETRY_MS = 60_000L
        /** About how long a look keeps the front camera closed, with room to spare. */
        const val TANK_LOOK_MS = 8_000L
        const val CAMERA_HANDOVER_MS = 400L
        const val STILL_LOOKS = 2
        const val TANK_STILL_NOTE = "The fish look unusually still. Check on them?"
        /** `2026-11-26 10:42:05  ` before each log line's own words. */
        const val STAMP_CHARS = 21

        /** An activity counts while your face was seen this recently: a cup at your mouth can hide it. */
        const val ACTIVITY_PRESENT_MS = 10_000L

        /** Open tasks named in the morning greeting; the rest are counted. */
        const val BRIEF_TASKS = 3

        /** How long a spoken answer stays under the eyes. */
        const val REPLY_MS = 8_000L

        /** How long an expression asked for from the page is held. */
        const val PREVIEW_MS = 3_000L

        /** How long the sad face lasts after a tool call failed. */
        const val WINCE_MS = 1_200L

        /** At most one sad face this often: a run of failures is one wince, not a sulk. */
        const val WINCE_GAP_MS = 30_000L

        /** Let the chirp finish before reading a due note aloud. */
        const val SPEAK_AFTER_CHIRP_MS = 450L

        /** How often an alarm chirps again until it is tapped off. */
        const val ALARM_REPEAT_MS = 10_000L

        /**
         * Listen hands-free while you are at the desk, with or without "Hey Buddy".
         * Off: NODE listens only after a tap or a wave, for one sentence.
         */
        const val WAKE = false

        /**
         * Seen this recently counts as at the desk, for listening. The camera only
         * sees a face turned toward the phone, and you mostly look at the monitor.
         */
        const val WAKE_PRESENT_MS = 2 * 60_000L

        /** Seen this recently as a sentence ends, you were facing NODE: no name needed. */
        const val FACING_MS = 5_000L

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

        /** How long a knock in the dark shows the time. Long enough to read, short enough to sleep through. */
        const val NIGHT_CLOCK_MS = 6_000L

        /** The night clock: 12-hour, no am/pm, since you can see out of the window. */
        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.US)

        /** Held this long, a touch shows the address instead of listening. */
        const val LONG_PRESS_MS = 600L

        /**
         * A touch that travels this far, in dp, was a stroke and not a tap.
         * Well past the slop a finger wanders while tapping.
         */
        const val STROKE_DP = 150f

        /**
         * A touch that ends this far, in dp, from where it began, by a path no
         * more than [SWIPE_STRAIGHT] times that long, was a swipe: rubbing back
         * and forth travels far but ends near where it started.
         */
        const val SWIPE_DP = 120f
        const val SWIPE_STRAIGHT = 1.4f

        /** No hand seen for this long and hand tracking turns itself off. */
        const val HANDS_IDLE_MS = 60_000L
        /** How often the model's server is tried while it is away; when it answers, it is left alone. */
        const val MODEL_RETRY_MS = 60_000L

        /** How long turning hand tracking on says so under the eyes. */
        const val MODE_CAPTION_MS = 1_500L

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

        /** How long a long press shows your tasks and the address, and lifts the dim. */
        const val UNDIM_MS = 30_000L

        /** The PC sends its load every 5 seconds: three missed and the readout goes. */
        const val PC_FRESH_MS = 15_000L

        /** Camera view frames a slow viewer may fall behind by before frames are dropped for it. */
        const val CAM_VIEW_QUEUE = 2

        const val HEARTBEAT_MS = 500L

        /** Wait before reopening a failed camera; long enough not to thrash. */
        const val RETRY_MS = 5_000L

        /** Wait before trying the LAN port again: it is usually free within moments. */
        const val SERVER_RETRY_MS = 2_000L

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
