package dev.node01.afterlife

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * The whole of NODE: a Wi-Fi camera.
 *
 * While this activity is in the foreground the rear camera records loop clips
 * to the phone and serves a live view, with sound, to any browser on the LAN.
 * Leaving it stops everything -- nothing runs in the background. Android only
 * lets the app in front use the camera and microphone, which is why the screen
 * is held on, at the panel minimum, instead of being released.
 */
class NodeActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var urlView: TextView

    private val prefs by lazy { getSharedPreferences("node", MODE_PRIVATE) }

    /** Random per install, persisted, so the address on the screen stays put. */
    private val urlKey by lazy {
        prefs.getString(KEY_URL_KEY, null) ?: newUrlKey().also {
            prefs.edit().putString(KEY_URL_KEY, it).apply()
        }
    }

    private val page by lazy { assets.open("index.html").use { it.readBytes() } }

    private val recorder by lazy {
        LoopRecorder(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(filesDir, "clips"))
    }

    // A couple of frames, or half a second of sound, per viewer before dropping.
    private val jpegHub = Hub(capacity = 2)
    private val pcmHub = Hub(capacity = 25)

    private var camcorder: Camcorder? = null
    private var server: HttpServer? = null

    /** True only while a touch that began at a screen edge is live. */
    private var barsRequestedBySwipe = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.node_cam)
        statusView = findViewById(R.id.cam_status)
        urlView = findViewById(R.id.cam_url)
        configureWindow()
    }

    override fun onStart() {
        super.onStart()
        setBrightness(BRIGHTNESS)
        if (PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            statusView.setText(R.string.status_no_permission)
            requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS)
        }
        startServer()
        showUrl()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQUEST_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startCamera()
        }
    }

    override fun onStop() {
        server?.stop()
        server = null
        // Finalizes the clip in progress, so leaving NODE never costs footage.
        stopCamera()
        // Do not leave delayed callbacks behind when the screen goes off.
        urlView.removeCallbacks(restoreDim)
        urlView.removeCallbacks(retryCamera)
        window.decorView.removeCallbacks(reHideBars)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A transient swipe, or MIUI restoring its own chrome, can bring the
        // system bars back. Re-hide them whenever we regain focus.
        if (hasFocus) hideSystemBars()
    }

    // -- camera ------------------------------------------------------------

    private fun startCamera() {
        if (camcorder != null) return
        statusView.setText(R.string.status_rec)
        val cam = Camcorder(this, recorder, jpegHub, pcmHub, ::restartCamera)
        camcorder = cam
        try {
            cam.start()
        } catch (e: Exception) {
            restartCamera(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopCamera() {
        camcorder?.stop()
        camcorder = null
    }

    /**
     * The camera, an encoder or the microphone failed: another app took the
     * camera, a media service restarted, the phone overheated. An appliance
     * does not sit on an error, so tear down and try again shortly.
     */
    private fun restartCamera(reason: String) {
        showError(reason)
        stopCamera()
        urlView.removeCallbacks(retryCamera)
        urlView.postDelayed(retryCamera, RETRY_MS)
    }

    private val retryCamera = Runnable { startCamera() }

    /**
     * Independent of the camera: a busy port must not stop the recording, and
     * a failed camera must not hide the clips already made.
     */
    private fun startServer() {
        if (server != null) return
        val http = HttpServer(urlKey, page, recorder, jpegHub, pcmHub)
        server = http
        try {
            http.start()
        } catch (e: Exception) {
            showError("port ${HttpServer.PORT}: ${e.message}")
        }
    }

    private fun showError(reason: String) {
        Log.e(TAG, reason)
        statusView.text = getString(R.string.status_error_fmt, reason)
    }

    /** Re-read on every tap too: after a reboot NODE can start before Wi-Fi does. */
    private fun showUrl() {
        val address = wifiAddress()
        urlView.text = if (address == null) {
            getString(R.string.url_no_wifi)
        } else {
            getString(R.string.url_fmt, address, HttpServer.PORT, urlKey)
        }
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

        // Hold the screen for as long as NODE is in front: the camera and the
        // microphone stop the moment it is not.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // A tap can surface the transient bars, and that is not a focus change,
        // so onWindowFocusChanged never fires and they simply stay up. Watch the
        // insets instead and put them away again shortly after they appear --
        // long enough that a deliberate swipe can still reach Home or Recents.
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            if (insets.isVisible(WindowInsets.Type.systemBars())) {
                v.removeCallbacks(reHideBars)
                if (barsRequestedBySwipe) {
                    // Deliberate swipe from the bottom edge: leave them up long
                    // enough to actually press Home or Recents.
                    v.postDelayed(reHideBars, BARS_REHIDE_MS)
                } else {
                    // Nobody asked for these -- a window relayout surfaced them.
                    // Put them straight back.
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
                // Only a touch starting at a system edge may be reaching for the
                // navigation bar. Anything starting in the middle must never
                // surface it.
                barsRequestedBySwipe = isEdgeTouch(ev.x, ev.y)
                if (!barsRequestedBySwipe) hideSystemBars()
            }
            // A tap in the middle wakes the screen to read the address.
            MotionEvent.ACTION_UP -> if (!barsRequestedBySwipe) undimTemporarily()
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Tap to read. Rather than picking an arbitrary brighter value, NODE drops
     * its brightness override entirely so the phone's own brightness applies,
     * then restores the dim after [UNDIM_MS].
     *
     * Like the camera retry, this delayed callback is one-shot, restarted
     * rather than stacked, and torn down in [onStop] -- not a poll.
     */
    private fun undimTemporarily() {
        showUrl()
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        urlView.removeCallbacks(restoreDim)
        urlView.postDelayed(restoreDim, UNDIM_MS)
    }

    private val restoreDim = Runnable { setBrightness(BRIGHTNESS) }

    private companion object {
        const val TAG = "NODE"

        val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        const val REQUEST_PERMISSIONS = 1

        const val KEY_URL_KEY = "url_key"

        /**
         * 32 symbols, without the easily confused 0/o and 1/l since the key is
         * typed from the phone's screen. Ten of them is 50 bits.
         */
        const val URL_KEY_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        const val URL_KEY_LENGTH = 10

        /**
         * Panel minimum. On an IPS panel the backlight is the screen's whole
         * power cost, and nobody needs to read it while it films them.
         */
        const val BRIGHTNESS = 0.01f

        /** How long a tap releases the dim for. */
        const val UNDIM_MS = 30_000L

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
