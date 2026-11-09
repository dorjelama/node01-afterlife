package dev.node01.afterlife

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * The phone's other senses: a knock on the desk (accelerometer), a hand waved
 * over the top of the screen (proximity) and the room lights going off
 * (ambient light).
 *
 * Sensor events arrive on a private thread; [onKnock], [onWave] and [onDark]
 * are delivered on the main thread. Every listener is registered in [start]
 * and removed in [stop]. A missing sensor just means that sense is absent.
 */
class Senses(
    private val context: Context,
    private val onKnock: () -> Unit,
    private val onWave: () -> Unit,
    private val onDark: (Boolean) -> Unit,
) : SensorEventListener {

    @Volatile
    private var running = false

    private val manager = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Everything below is touched only on [thread].
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var settled = 0
    private var lastKnockMs = 0L
    private var nearSinceMs = -1L
    private var proximityRange = 0f
    private var dark = false
    private var darkPending = false

    fun start() {
        if (running) return
        running = true
        val thread = HandlerThread("node-senses").apply { start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        settled = 0
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            proximityRange = it.maximumRange
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        manager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
    }

    fun stop() {
        running = false
        manager.unregisterListener(this)
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAcceleration(event.values)
            Sensor.TYPE_PROXIMITY -> onProximity(event.values[0])
            Sensor.TYPE_LIGHT -> onLight(event.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /**
     * A knock is a short, sharp jolt on top of gravity. A slow low-pass tracks
     * gravity; what is left over is the jolt. A jolt big enough to be the phone
     * being picked up or knocked over is not a knock.
     */
    private fun onAcceleration(v: FloatArray) {
        if (settled < SETTLE_SAMPLES) {
            // Seed gravity from the first readings so start-up is not a knock.
            gx = v[0]
            gy = v[1]
            gz = v[2]
            settled++
            return
        }
        gx += (v[0] - gx) * GRAVITY_ALPHA
        gy += (v[1] - gy) * GRAVITY_ALPHA
        gz += (v[2] - gz) * GRAVITY_ALPHA
        val dx = v[0] - gx
        val dy = v[1] - gy
        val dz = v[2] - gz
        val jolt = sqrt(dx * dx + dy * dy + dz * dz)
        val now = SystemClock.elapsedRealtime()
        if (jolt > KNOCK_MS2 && jolt < HANDLED_MS2 && now - lastKnockMs > KNOCK_REFRACTORY_MS) {
            lastKnockMs = now
            Log.d(TAG, "knock %.2f m/s²".format(jolt))
            deliver(onKnock)
        }
    }

    /** Near then far again, quickly: a wave. Held near is a cover, not a wave. */
    private fun onProximity(distance: Float) {
        val near = distance < proximityRange
        val now = SystemClock.elapsedRealtime()
        if (near) {
            if (nearSinceMs < 0) nearSinceMs = now
        } else {
            if (nearSinceMs >= 0 && now - nearSinceMs < WAVE_MS) deliver(onWave)
            nearSinceMs = -1L
        }
    }

    /**
     * Dark only after [DARK_HOLD_MS] below [DARK_LUX], so a hand or a shadow
     * does not put NODE to sleep. Light again at once above [LIGHT_LUX].
     */
    private fun onLight(lux: Float) {
        when {
            lux < DARK_LUX -> if (!dark && !darkPending) {
                darkPending = true
                handler?.postDelayed(goDark, DARK_HOLD_MS)
            }
            else -> {
                // Between the two thresholds the room stays as it was, but a
                // pending "dark" is off: it did not stay dark for long enough.
                darkPending = false
                handler?.removeCallbacks(goDark)
                if (lux >= LIGHT_LUX) setDark(false)
            }
        }
    }

    private val goDark = Runnable {
        darkPending = false
        setDark(true)
    }

    private fun setDark(value: Boolean) {
        if (dark == value) return
        dark = value
        Log.d(TAG, "room ${if (value) "dark" else "lit"}")
        context.mainExecutor.execute { if (running) onDark(value) }
    }

    private fun deliver(callback: () -> Unit) {
        context.mainExecutor.execute { if (running) callback() }
    }

    private companion object {
        const val TAG = "NODE"

        const val SETTLE_SAMPLES = 10
        const val GRAVITY_ALPHA = 0.1f

        /** Jolt over gravity, in m/s², that counts as a knock. Tune per desk. */
        const val KNOCK_MS2 = 1.2f

        /** Past this the phone is being handled, not knocked near. */
        const val HANDLED_MS2 = 8f
        const val KNOCK_REFRACTORY_MS = 600L

        const val WAVE_MS = 1_500L

        const val DARK_LUX = 3f
        const val LIGHT_LUX = 8f
        const val DARK_HOLD_MS = 30_000L
    }
}
