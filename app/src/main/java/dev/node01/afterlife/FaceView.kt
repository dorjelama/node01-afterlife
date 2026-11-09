package dev.node01.afterlife

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A dot-matrix face: the whole screen is a grid of faint unlit dots, and the
 * two pill-shaped eyes are the dots that light. Shapes are worked out as
 * smooth geometry, then each dot is simply on or off, so the eyes move in
 * crisp dot-sized steps like an LED panel.
 *
 * The face eases from where it is toward the [Look] it was last shown, and
 * redraws only while something is still moving: a settled face costs no
 * frames at all. The unlit grid is drawn once per size into a bitmap, so a
 * frame only draws the few hundred lit dots. Blinks and idle glances are
 * single delayed callbacks, started by [start] and removed by [stop].
 * [onDraw] allocates nothing.
 */
class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private fun color(id: Int) = resources.getColor(id, context.theme)

    private val bgColor = color(R.color.node_bg)
    private val eyeColor = color(R.color.node_eye)
    private val amberColor = color(R.color.node_accent)

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.node_dot_off) }

    /** The "!!" while Claude waits for approval: an amber dot-matrix glyph beside the right eye. */
    private val exclaimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = amberColor }

    /** The unlit panel, redrawn only when the size changes. */
    private var grid: Bitmap? = null
    private var pitch = 1f
    private var dotR = 1f
    private var x0 = 0f
    private var y0 = 0f

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
        // Closed eyes, asleep or happy, have nothing to blink.
        if (look?.expression?.params?.open == 0f) return
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
            exclaim = if (l.exclaim) 1f else 0f,
        )
        kick()
    }

    private fun kick() {
        if (animating) return
        animating = true
        lastFrameMs = 0L
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        grid?.recycle()
        grid = null
        if (w <= 0 || h <= 0) return
        pitch = h / ROWS
        dotR = pitch * DOT
        // A column through the middle of the screen, so the face sits symmetric on the grid.
        x0 = (w / 2f) % pitch
        y0 = (h / 2f) % pitch
        val panel = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(panel).apply {
            drawColor(bgColor)
            var y = y0
            while (y < h) {
                var x = x0
                while (x < w) {
                    drawCircle(x, y, dotR, offPaint)
                    x += pitch
                }
                y += pitch
            }
        }
        grid = panel
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameMs == 0L) FRAME_MS else min(now - lastFrameMs, MAX_STEP_MS)
        lastFrameMs = now
        current = current.approach(target, 1f - exp(-dtMs / EASE_MS).toFloat())
        if (current.distanceTo(target) < SETTLED) current = target

        val blinkT = (now - blinkStartMs) / BLINK_MS.toFloat()
        val blinking = blinkT in 0f..1f
        val closure = if (blinking) 1f - abs(blinkT * 2f - 1f) else 0f

        val hopT = (now - hopStartMs) / HOP_MS.toFloat()
        val hopping = hopT in 0f..1f
        val h = height.toFloat()
        val hop = if (hopping) -sin(PI * hopT).toFloat() * HOP * h else 0f

        grid?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(bgColor)
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
        val line = pitch * LINE_DOTS
        val cx = w / 2f + p.gazeX * h * GAZE_X
        // Centred on a row and, below, on a column: a shape centred between
        // dots comes out lopsided, with stray single dots at its tips.
        val cy = snap(h * FACE_Y + p.gazeY * h * GAZE_Y + hop, y0)
        val open = p.open * (1f - closure)

        onPaint.color = blend(eyeColor, amberColor, p.amber)

        for (side in SIDES) {
            // The eye on the side being looked toward grows a touch, as if nearer.
            val scale = 1f + side * p.gazeX * PERSPECTIVE
            val ew = baseW * p.width * scale
            val fullH = baseH * p.height * scale
            val eh = max(fullH * open, line)
            val ex = snap(cx + side * h * EYE_GAP / 2f, x0)
            val halfW = ew / 2f
            // Squarer than a pill, so the top and bottom rows are several dots wide.
            val r = min(ew, eh) * CORNER
            // As the eye closes, its centre line arches (happy) or sags (asleep).
            val bend = p.arc * fullH * BEND * (1f - open)

            val firstCol = ceil((ex - halfW - x0) / pitch).toInt()
            val lastCol = ((ex + halfW - x0) / pitch).toInt()
            val firstRow = ceil((cy - eh / 2f - abs(bend) - y0) / pitch).toInt()
            val lastRow = ((cy + eh / 2f + abs(bend) - y0) / pitch).toInt()
            for (col in firstCol..lastCol) {
                val x = x0 + col * pitch
                val u = ((x - ex) / halfW).coerceIn(-1f, 1f)
                val yc = cy - bend * (1f - u * u)
                val lidY = yc - eh / 2f + eh * p.lid
                val dx = max(abs(x - ex) - (halfW - r), 0f)
                for (row in firstRow..lastRow) {
                    val y = y0 + row * pitch
                    if (y < lidY) continue
                    val dy = max(abs(y - yc) - (eh / 2f - r), 0f)
                    if (dx * dx + dy * dy <= r * r) canvas.drawCircle(x, y, dotR, onPaint)
                }
            }
        }

        // "!!" in dots beside the right eye, top-aligned with where a fully
        // open eye would start, so a blink does not move it. Fades in.
        if (p.exclaim > 0.01f) {
            exclaimPaint.alpha = (255 * p.exclaim).toInt().coerceIn(0, 255)
            val ew = baseW * p.width * (1f + p.gazeX * PERSPECTIVE)
            val clear = cx + h * EYE_GAP / 2f + ew / 2f + h * EXCLAIM_GAP
            val col0 = ceil((clear - x0) / pitch).toInt()
            val row0 = ((cy - baseH * p.height / 2f - y0) / pitch).roundToInt()
            for (mark in 0..1) {
                for (c in 0 until BANG_WIDTH) {
                    for (rowInGlyph in 0 until BANG_HEIGHT) {
                        if (rowInGlyph == BANG_GAP_ROW) continue
                        val x = x0 + (col0 + mark * (BANG_WIDTH + 1) + c) * pitch
                        val y = y0 + (row0 + rowInGlyph) * pitch
                        canvas.drawCircle(x, y, dotR, exclaimPaint)
                    }
                }
            }
        }
    }

    /** The nearest dot centre to [v] on a grid starting at [origin]. */
    private fun snap(v: Float, origin: Float) = origin + ((v - origin) / pitch).roundToInt() * pitch

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        val SIDES = floatArrayOf(-1f, 1f)
        const val NEVER = -1_000_000L

        // The panel: rows of dots down the screen, and each dot's size within its cell.
        const val ROWS = 38f
        const val DOT = 0.36f

        // Geometry, as fractions of the view's height.
        const val EYE_W = 0.22f
        const val EYE_H = 0.40f
        const val EYE_GAP = 0.66f
        const val FACE_Y = 0.44f
        const val GAZE_X = 0.22f
        const val GAZE_Y = 0.10f
        const val PERSPECTIVE = 0.08f
        const val HOP = 0.06f
        const val EXCLAIM_GAP = 0.06f

        /** Corner rounding as a fraction of the eye's smaller side; 0.5 would be a pill. */
        const val CORNER = 0.38f

        /** A closed eye's line: just over one dot thick. */
        const val LINE_DOTS = 1.5f

        /** How far a closed eye's line arches or sags, as a fraction of the eye's height. */
        const val BEND = 0.25f

        /** "!" in dots: two wide, ten tall, its point under a one-dot gap. */
        const val BANG_WIDTH = 2
        const val BANG_HEIGHT = 10
        const val BANG_GAP_ROW = 7

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
