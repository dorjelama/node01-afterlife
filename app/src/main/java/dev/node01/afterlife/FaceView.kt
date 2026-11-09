package dev.node01.afterlife

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Two rounded "pill" eyes on black.
 *
 * The face eases from where it is toward the [Look] it was last shown, and
 * redraws only while something is still moving: a settled face costs no
 * frames at all. Blinks and idle glances are single delayed callbacks,
 * started by [start] and removed by [stop]. Every Paint is allocated up front;
 * [onDraw] allocates nothing.
 */
class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val eyeColor = resources.getColor(R.color.node_eye, context.theme)
    private val amberColor = resources.getColor(R.color.node_accent, context.theme)

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Lids are drawn in the background colour over the eye. */
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_bg, context.theme)
    }

    private val eye = RectF()
    private val lid = RectF()

    /** Starts asleep, so the first look shown is a waking up. */
    private var current = Expression.CLOSED.params
    private var target = current
    private var look: Look? = null

    private var wanderX = 0f
    private var wanderY = 0f
    private var glancing = false

    private var blinkStartMs = NEVER
    private var hopStartMs = NEVER
    private var lastFrameMs = 0L
    private var animating = false
    private var running = false

    fun start() {
        if (running) return
        running = true
        postDelayed(blinkTick, nextBlinkMs())
        postDelayed(idleTick, nextIdleMs())
    }

    fun stop() {
        running = false
        removeCallbacks(blinkTick)
        removeCallbacks(idleTick)
        removeCallbacks(endGlance)
        removeCallbacks(secondBlink)
    }

    fun show(next: Look) {
        val previous = look
        look = next
        if (next.startled && previous?.startled != true) hopStartMs = SystemClock.uptimeMillis()
        if (next.gaze !is Gaze.Mostly) glancing = false
        retarget()
    }

    /** A hello: two quick blinks. */
    fun doubleBlink() {
        blink()
        removeCallbacks(secondBlink)
        postDelayed(secondBlink, DOUBLE_BLINK_GAP_MS)
    }

    private fun blink() {
        if (look?.expression == Expression.CLOSED) return
        blinkStartMs = SystemClock.uptimeMillis()
        kick()
    }

    private val secondBlink = Runnable { blink() }

    private val blinkTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (Random.nextFloat() < DOUBLE_BLINK_CHANCE) doubleBlink() else blink()
            postDelayed(this, nextBlinkMs())
        }
    }

    /** Picks a new spot to look at while wandering, or glances away while focused. */
    private val idleTick = object : Runnable {
        override fun run() {
            if (!running) return
            when (look?.gaze) {
                Gaze.Wander -> {
                    // Now and then, back to the middle, so it does not look lost.
                    if (Random.nextFloat() < 0.3f) {
                        wanderX = 0f
                        wanderY = 0f
                    } else {
                        wanderX = (Random.nextFloat() * 2f - 1f) * WANDER_X
                        wanderY = (Random.nextFloat() * 2f - 1f) * WANDER_Y
                    }
                    retarget()
                }
                is Gaze.Mostly -> if (Random.nextFloat() < GLANCE_CHANCE) {
                    glancing = true
                    retarget()
                    postDelayed(endGlance, GLANCE_MS)
                }
                else -> Unit
            }
            postDelayed(this, nextIdleMs())
        }
    }

    private val endGlance = Runnable {
        glancing = false
        retarget()
    }

    private fun retarget() {
        val l = look ?: return
        val (gx, gy) = when (val g = l.gaze) {
            is Gaze.At -> g.x to g.y
            Gaze.Wander -> wanderX to wanderY
            is Gaze.Mostly -> if (glancing) g.glanceX to g.glanceY else g.x to g.y
        }
        target = l.expression.params.copy(
            gazeX = gx.coerceIn(-1f, 1f),
            gazeY = gy.coerceIn(-1f, 1f),
            amber = if (l.amber) 1f else 0f,
        )
        kick()
    }

    private fun kick() {
        if (animating) return
        animating = true
        lastFrameMs = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameMs == 0L) FRAME_MS else min(now - lastFrameMs, MAX_STEP_MS)
        lastFrameMs = now
        current = current.approach(target, 1f - exp(-dtMs / EASE_MS).toFloat())
        if (current.distanceTo(target) < SETTLED) current = target

        val blinkT = (now - blinkStartMs) / BLINK_MS.toFloat()
        val blinking = blinkT in 0f..1f
        val closure = if (blinking) 1f - kotlin.math.abs(blinkT * 2f - 1f) else 0f

        val hopT = (now - hopStartMs) / HOP_MS.toFloat()
        val hopping = hopT in 0f..1f
        val h = height.toFloat()
        val hop = if (hopping) -sin(PI * hopT).toFloat() * HOP * h else 0f

        drawEyes(canvas, closure, hop)

        if (blinking || hopping || current != target) {
            postInvalidateOnAnimation()
        } else {
            animating = false
        }
    }

    private fun drawEyes(canvas: Canvas, closure: Float, hop: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = current
        val baseW = h * EYE_W
        val baseH = h * EYE_H
        val line = h * LINE
        val cx = w / 2f + p.gazeX * h * GAZE_X
        val cy = h * FACE_Y + p.gazeY * h * GAZE_Y + hop
        val open = p.open * (1f - closure)

        eyePaint.color = blend(eyeColor, amberColor, p.amber)
        for (side in SIDES) {
            // The eye on the side being looked toward grows a touch, as if nearer.
            val scale = 1f + side * p.gazeX * PERSPECTIVE
            val ew = baseW * p.width * scale
            val eh = max(baseH * p.height * scale * open, line)
            val ex = cx + side * h * EYE_GAP / 2f
            eye.set(ex - ew / 2f, cy - eh / 2f, ex + ew / 2f, cy + eh / 2f)
            val r = min(ew, eh) * CORNER
            canvas.drawRoundRect(eye, r, r, eyePaint)

            if (p.lid > 0.01f) {
                canvas.drawRect(eye.left - 2f, eye.top - 2f, eye.right + 2f, eye.top + eh * p.lid, lidPaint)
            }
            if (p.smile > 0.01f) {
                // An oval rising into the eye from below leaves an arch.
                val top = eye.bottom - eh * p.smile
                lid.set(ex - ew * 0.9f, top, ex + ew * 0.9f, top + eh * 1.6f)
                canvas.drawOval(lid, lidPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        val SIDES = floatArrayOf(-1f, 1f)
        const val NEVER = -1_000_000L

        // Geometry, as fractions of the view's height.
        const val EYE_W = 0.22f
        const val EYE_H = 0.40f
        const val EYE_GAP = 0.66f
        const val FACE_Y = 0.44f
        const val LINE = 0.022f
        const val CORNER = 0.42f
        const val GAZE_X = 0.22f
        const val GAZE_Y = 0.10f
        const val PERSPECTIVE = 0.08f
        const val HOP = 0.06f

        // Motion.
        const val EASE_MS = 90.0
        const val SETTLED = 0.002f
        const val FRAME_MS = 16L
        const val MAX_STEP_MS = 100L
        const val BLINK_MS = 150L
        const val HOP_MS = 380L
        const val DOUBLE_BLINK_GAP_MS = 260L
        const val DOUBLE_BLINK_CHANCE = 0.15f

        // Idle behaviour.
        const val WANDER_X = 0.7f
        const val WANDER_Y = 0.4f
        const val GLANCE_CHANCE = 0.25f
        const val GLANCE_MS = 1_200L

        fun nextBlinkMs() = Random.nextLong(2_500L, 6_000L)
        fun nextIdleMs() = Random.nextLong(1_500L, 4_000L)

        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
