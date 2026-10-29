package dev.node01.afterlife

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The whole of NODE.
 *
 * Power model: NODE owns no timers, no handlers, no services and no wake locks
 * of its own. The only recurring work is driven by the system's own minute
 * pulse, [Intent.ACTION_TIME_TICK], which is delivered exclusively to
 * runtime-registered receivers. Registered in [onStart], dropped in [onStop] --
 * so a backgrounded NODE costs nothing at all.
 *
 * Battery is read on demand once per tick from the sticky
 * [Intent.ACTION_BATTERY_CHANGED] broadcast rather than by holding a live
 * receiver, which would otherwise wake us on every voltage and temperature
 * change for a number that only renders to whole percent.
 */
class NodeActivity : Activity() {

    private lateinit var clockView: ClockView
    private lateinit var calendarView: CalendarView
    private lateinit var powerView: TextView
    private lateinit var tempView: TextView
    private lateinit var deskPane: View
    private lateinit var focusPane: View
    private lateinit var focusTimeView: TextView
    private lateinit var focusAmPmView: TextView

    private val weather = WeatherClient()
    private var tempC: Double? = null
    private var tempFetchedAt = 0L

    /**
     * The drain anchor is persisted, not held in memory. Any process restart --
     * a reinstall, a reboot, MIUI reclaiming the app -- would otherwise discard
     * the measurement and leave the appliance with no estimate for another five
     * minutes. Wall-clock time is used rather than elapsedRealtime precisely so
     * it survives those restarts.
     */
    private val prefs by lazy { getSharedPreferences("node", MODE_PRIVATE) }

    /**
     * Modes reachable by swiping, in order. Adding a mode here is all that is
     * needed to put it in the rotation.
     */
    private val modes = listOf(Mode.DESK, Mode.FOCUS)

    /** Restored from prefs, so the appliance comes back the way it was left. */
    private var mode: Mode = Mode.DESK

    private var receiverRegistered = false
    private var quietHoursActive = false
    private var screenPolicyApplied = false
    private var brightnessBoosted = false

    /** True only while a touch that began in the bottom edge strip is live. */
    private var barsRequestedBySwipe = false

    /**
     * Fires on every minute boundary, and whenever the clock or time zone is
     * changed out from under us.
     */
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.node_desk)

        clockView = findViewById(R.id.node_clock)
        calendarView = findViewById(R.id.node_calendar)
        powerView = findViewById(R.id.node_power)
        tempView = findViewById(R.id.node_temp)
        deskPane = findViewById(R.id.desk_pane)
        focusPane = findViewById(R.id.focus_pane)
        focusTimeView = findViewById(R.id.focus_time)
        focusAmPmView = findViewById(R.id.focus_ampm)

        mode = modes.getOrElse(prefs.getInt(KEY_MODE, 0)) { Mode.DESK }

        configureWindow()
        applyMode(mode)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            // These are all protected system broadcasts, so the export flag is
            // not strictly required -- passing it anyway keeps us correct if a
            // non-protected action is ever added to the filter above.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(timeReceiver, filter)
            }
            receiverRegistered = true
        }
        // Draw immediately rather than waiting up to 60s for the first tick.
        render()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(timeReceiver)
            receiverRegistered = false
        }
        // Do not leave delayed callbacks behind when the screen goes off.
        powerView.removeCallbacks(restoreDim)
        window.decorView.removeCallbacks(reHideBars)
        brightnessBoosted = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A transient swipe, or MIUI restoring its own chrome, can bring the
        // system bars back. Re-hide them whenever we regain focus.
        if (hasFocus) hideSystemBars()
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

    private fun applyMode(mode: Mode) {
        val focus = mode == Mode.FOCUS
        deskPane.visibility = if (focus) View.GONE else View.VISIBLE
        focusPane.visibility = if (focus) View.VISIBLE else View.GONE
        applyScreenPolicy(LocalDateTime.now().hour)
        Log.i(TAG, "mode=$mode brightness=${mode.brightness} keepOn=${mode.keepScreenOn}")
    }

    /** Steps [delta] places through [modes], wrapping, and persists the choice. */
    private fun cycleMode(delta: Int) {
        val next = modes[((modes.indexOf(mode) + delta) % modes.size + modes.size) % modes.size]
        if (next == mode) return
        mode = next
        prefs.edit().putInt(KEY_MODE, modes.indexOf(next)).apply()
        applyMode(next)
        render()
    }

    /**
     * Quiet hours. Outside them NODE holds the screen at the panel minimum;
     * inside them it releases the screen entirely so Android can deep-sleep.
     *
     * This is re-evaluated on every minute tick, so the transition happens on
     * its own without a timer or an alarm. Once the screen does go off the
     * activity is stopped, the tick receiver is dropped, and NODE costs nothing
     * until the device is woken again.
     */
    private fun applyScreenPolicy(hour: Int) {
        val quiet = isQuietHour(hour)
        if (quiet != quietHoursActive || !screenPolicyApplied) {
            quietHoursActive = quiet
            screenPolicyApplied = true
            Log.i(TAG, "screen policy: quiet=$quiet hour=$hour")
        }

        if (quiet) {
            // Releasing the flag lets the system screen timeout take over.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // A tap owns the backlight until it expires; do not stomp it here.
        if (brightnessBoosted) return

        // Window-scoped backlight override. Needs no permission and is reverted
        // by the system the moment NODE loses the foreground.
        //
        // Only write when the value actually changes: assigning window.attributes
        // forces a relayout, and a relayout surfaces the system bars on this
        // device. Switching mode must not cost a relayout.
        val target = if (quiet) 0f else mode.brightness
        if (window.attributes.screenBrightness != target) {
            window.attributes = window.attributes.apply {
                screenBrightness = target
            }
        }
    }

    /**
     * True near any edge the system claims for its own gestures. In landscape
     * this device puts the navigation bar on the *right* edge, not the bottom,
     * so guarding only the bottom left mode-switch swipes fighting it.
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

    /**
     * Horizontal fling switches mode. Flings that begin in the bottom edge strip
     * are ignored so they cannot fight the navigation-bar swipe, and a fling has
     * to be clearly more horizontal than vertical to count.
     */
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val start = e1 ?: return false
                // Never compete with a system edge gesture.
                if (isEdgeTouch(start.x, start.y)) return false

                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) < SWIPE_MIN_PX) return false
                if (abs(dx) < abs(dy) * SWIPE_DOMINANCE) return false

                // Swipe left goes forward, the way pages advance.
                cycleMode(if (dx < 0) 1 else -1)
                return true
            }

            /**
             * Undim on a tap only, never on a swipe. Undimming writes
             * window.attributes, which relayouts and surfaces the system bars,
             * so a mode swipe must not go anywhere near it.
             */
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                undimTemporarily()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Only a touch starting at a system edge may be reaching for the
            // navigation bar. Anything starting in the middle -- a tap on the
            // dial, or a mode swipe -- must never surface it. Undimming happens
            // in onSingleTapUp, so a swipe never touches window.attributes.
            barsRequestedBySwipe = isEdgeTouch(ev.x, ev.y)
            if (!barsRequestedBySwipe) hideSystemBars()
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Tap to read. Rather than picking an arbitrary brighter value, NODE drops
     * its brightness override entirely so the phone's own brightness applies,
     * then restores the dim after [UNDIM_MS].
     *
     * This is the one delayed callback in the app. It is user-initiated,
     * one-shot, cancelled on re-tap and torn down in [onStop] -- not a poll.
     */
    private fun undimTemporarily() {
        // Reassigning window attributes forces a relayout, and on this device a
        // relayout is what surfaces the system bars. Only touch them when the
        // brightness actually needs to change -- a second tap while already
        // undimmed just restarts the timer.
        if (!brightnessBoosted) {
            brightnessBoosted = true
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        powerView.removeCallbacks(restoreDim)
        powerView.postDelayed(restoreDim, UNDIM_MS)
    }

    private val restoreDim = Runnable {
        brightnessBoosted = false
        applyScreenPolicy(LocalDateTime.now().hour)
    }

    /** Quiet window wraps midnight, so this is an OR rather than a range. */
    private fun isQuietHour(hour: Int): Boolean =
        if (QUIET_START_HOUR > QUIET_END_HOUR) {
            hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR
        } else {
            hour in QUIET_START_HOUR until QUIET_END_HOUR
        }

    // -- render ------------------------------------------------------------

    /** One pass. Called on entry and once per minute thereafter. */
    private fun render() {
        val now = LocalDateTime.now()

        if (mode == Mode.FOCUS) {
            // Time and nothing else. Still minute-resolution -- no seconds, so
            // this remains one redraw per tick.
            focusTimeView.text = FOCUS_TIME_FORMAT.format(now)
            focusAmPmView.text = FOCUS_AMPM_FORMAT.format(now).uppercase(Locale.US)
        }

        // Both views no-op unless their own value actually changed, so the
        // calendar only really redraws at midnight.
        clockView.setTime(now.hour, now.minute)
        calendarView.setDate(now.toLocalDate())

        // Re-checked every tick so the quiet-hours boundary needs no alarm.
        applyScreenPolicy(now.hour)

        val battery = readBattery()
        powerView.text = renderPower(battery)

        maybeFetchWeather()
        renderTemperature()
    }

    /**
     * Piggybacks on the minute tick rather than owning a scheduler: once an
     * hour, and never while a request is already in flight.
     */
    private fun maybeFetchWeather() {
        val now = SystemClock.elapsedRealtime()
        if (tempFetchedAt != 0L && now - tempFetchedAt < WEATHER_INTERVAL_MS) return

        weather.fetch { celsius ->
            if (celsius == null) return@fetch
            // Back to the main thread; drop the result if we are on the way out.
            tempView.post {
                if (isDestroyed) return@post
                tempC = celsius
                tempFetchedAt = SystemClock.elapsedRealtime()
                renderTemperature()
            }
        }
        // Mark the attempt so a hard-down network retries hourly, not every minute.
        if (tempFetchedAt == 0L) tempFetchedAt = now
    }

    /**
     * A confident-looking number from hours ago is worse than no number, so a
     * stale reading is dimmed rather than left indistinguishable from a fresh one.
     */
    private fun renderTemperature() {
        val c = tempC
        if (c == null) {
            tempView.setText(R.string.temp_unknown)
            tempView.setTextColor(getColor(R.color.node_dim))
            return
        }
        tempView.text = getString(R.string.temp_fmt, Bs.neDigits(c.roundToInt()))
        val stale = SystemClock.elapsedRealtime() - tempFetchedAt > WEATHER_STALE_MS
        tempView.setTextColor(
            getColor(if (stale) R.color.node_dim else R.color.node_secondary)
        )
    }

    /**
     * Runtime remaining, from observed drain rather than the fuel gauge.
     *
     * The instantaneous CURRENT_NOW reading is useless here: sampled during
     * launch it showed ~1.17A against a true steady draw nearer 220mA, because
     * the CPU, GPU and the weather fetch were all active. Measuring the actual
     * percentage drop over wall-clock time captures what DESK mode really
     * costs, screen included.
     *
     * Returns minutes remaining, or null until the measurement is trustworthy
     * -- NODE shows a plain percentage rather than a number it cannot stand
     * behind.
     */
    private fun estimateMinutesLeft(percent: Int): Int? {
        val now = System.currentTimeMillis()
        val anchorMs = prefs.getLong(KEY_ANCHOR_MS, 0L)
        val anchorPct = prefs.getInt(KEY_ANCHOR_PCT, -1)

        // Re-anchor when there is nothing stored, the clock moved backwards,
        // the window is too old to describe current conditions, or the battery
        // went up (charged or re-seated).
        val unusable = anchorMs == 0L ||
            anchorPct < 0 ||
            now < anchorMs ||
            now - anchorMs > DRAIN_ANCHOR_MAX_AGE_MS
        if (unusable || percent > anchorPct) {
            anchorDrain(now, percent)
            return null
        }

        val elapsedMs = now - anchorMs
        val dropped = anchorPct - percent
        // Need both a real drop and enough time for it to mean anything.
        if (dropped < 1 || elapsedMs < DRAIN_MIN_SAMPLE_MS) return null

        val percentPerMs = dropped.toDouble() / elapsedMs
        if (percentPerMs <= 0.0) return null

        val minutes = (percent / percentPerMs / 60_000.0).roundToInt()
        return if (minutes in 1..(100 * 60)) minutes else null
    }

    private fun anchorDrain(atMs: Long, percent: Int) {
        prefs.edit()
            .putLong(KEY_ANCHOR_MS, atMs)
            .putInt(KEY_ANCHOR_PCT, percent)
            .apply()
    }

    private fun renderPower(battery: BatteryState): String {
        if (battery.percent < 0) return getString(R.string.power_unknown)
        val pct = Bs.neDigits(battery.percent)

        if (battery.charging) {
            // Discharge history is meaningless once the cable is in.
            prefs.edit().remove(KEY_ANCHOR_MS).remove(KEY_ANCHOR_PCT).apply()

            val bm = getSystemService(BatteryManager::class.java)
            val toFullMs = bm?.computeChargeTimeRemaining() ?: -1L
            if (toFullMs > 0L) {
                val m = (toFullMs / 60_000L).toInt()
                return getString(
                    R.string.power_to_full_fmt,
                    pct, Bs.neDigits(m / 60), Bs.neDigits2(m % 60),
                )
            }
            return getString(R.string.power_charging_fmt, pct)
        }

        val left = estimateMinutesLeft(battery.percent)
            ?: return getString(R.string.power_fmt, pct)

        // "४५मि बाँकी" reads better than "०घ ४५मि बाँकी".
        return if (left >= 60) {
            getString(
                R.string.power_left_fmt,
                pct, Bs.neDigits(left / 60), Bs.neDigits2(left % 60),
            )
        } else {
            getString(R.string.power_left_min_fmt, pct, Bs.neDigits(left))
        }
    }

    private data class BatteryState(val percent: Int, val charging: Boolean)

    /**
     * Reads the sticky battery broadcast. Passing a null receiver returns the
     * last broadcast Intent immediately without registering anything.
     */
    private fun readBattery(): BatteryState {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryState(percent = -1, charging = false)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        return BatteryState(percent, charging)
    }

    private companion object {
        const val TAG = "NODE"

        /** One network request per hour. Air temperature does not move faster. */
        const val WEATHER_INTERVAL_MS = 60L * 60L * 1000L

        /** Past this with no successful fetch, the reading is shown as stale. */
        const val WEATHER_STALE_MS = 3L * 60L * 60L * 1000L

        /**
         * Minimum observation window before a runtime estimate is shown. At
         * DESK mode's measured ~10%/hour a single percent takes ~6 minutes, so
         * this is about the earliest a figure means anything.
         */
        const val DRAIN_MIN_SAMPLE_MS = 5L * 60L * 1000L

        /** Past this the window no longer describes current conditions. */
        const val DRAIN_ANCHOR_MAX_AGE_MS = 24L * 60L * 60L * 1000L

        const val KEY_MODE = "mode_index"

        /** Minimum horizontal travel for a fling to count as a mode switch. */
        const val SWIPE_MIN_PX = 120f

        /** How much more horizontal than vertical a switching fling must be. */
        const val SWIPE_DOMINANCE = 1.5f

        // 12-hour, no leading zero: "9:05" reads better than "09:05" at this size.
        val FOCUS_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("h:mm", Locale.US)
        val FOCUS_AMPM_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("a", Locale.US)

        const val KEY_ANCHOR_MS = "drain_anchor_ms"
        const val KEY_ANCHOR_PCT = "drain_anchor_pct"

        /**
         * Quiet hours: screen released so the device deep-sleeps. Change these
         * two values to reshape the appliance's day. The window may wrap
         * midnight (start > end).
         */
        /** How long a tap releases the dim for. */
        const val UNDIM_MS = 30_000L

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

        const val QUIET_START_HOUR = 23
        const val QUIET_END_HOUR = 7

    }
}
