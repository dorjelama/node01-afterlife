package dev.node01.afterlife

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A dot-matrix face: the whole screen is a grid of faint unlit dots, and the
 * eyes are the dots that light, over a soft glow.
 *
 * Every shape -- an open eye, a lidded one, the "^" of a happy one, a heart,
 * the "!!" -- is built each frame as a smooth path from the current
 * [EyeParams], then laid on the grid: a dot lights when its centre falls
 * inside. Each eye is centred on a dot and the two sit whole dots apart, so
 * they come out exact mirror images and move in crisp dot-sized steps, like
 * an LED panel.
 *
 * The face eases from where it is toward the [Look] it was last shown, and
 * redraws only while something is still moving: a settled face costs no
 * frames at all. The unlit grid is drawn once per size into a bitmap. One-shot
 * [Act]s are layered on at draw time as a function of their own progress.
 * Blinks, idle glances and fidgets are single delayed callbacks, started by
 * [start] and removed by [stop]. [onDraw] reuses its paths, paints and buffers.
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

    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.node_dot_off) }
    private val dotPaint = roundDots()
    private val zPaint = roundDots()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Not anti-aliased: a dot is lit or it is not. */
    private val maskPaint = Paint().apply { isAntiAlias = false }

    /** Everything lit this frame, as smooth shapes in screen pixels. */
    private val shapes = Path()

    private val tops = FloatArray(SAMPLES + 1)
    private val bottoms = FloatArray(SAMPLES + 1)

    // The panel, rebuilt only when the size changes.
    private var grid: Bitmap? = null
    private var pitch = 1f
    private var dotR = 1f
    private var x0 = 0f
    private var y0 = 0f
    private var cols = 0
    private var rows = 0

    /** [shapes] laid on the grid, one pixel per dot, read back to find which dots light. */
    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var maskPixels: ByteBuffer? = null
    private var maskRowBytes = 0
    private var lit = FloatArray(0)
    private val zDots = FloatArray(2 * Z_MAX_DOTS)

    /** Starts asleep, so the first look shown is a waking up. */
    private var current = Expression.CLOSED.params
    private var target = current
    private var look: Look? = null

    /** Shown in place of the mood's expression: asked for over the LAN. */
    private var preview: Expression? = null

    /** A brief idle fidget, dropped as soon as the mood changes. */
    private var fidget: Expression? = null

    private var wanderX = 0f
    private var wanderY = 0f
    private var glancing = false

    private var blinkStartMs = NEVER
    private var hopStartMs = NEVER
    private var act: Act? = null
    private var actStartMs = NEVER
    private var winkSide = 1f

    // Per eye, left then right, for acts that move the eyes apart from each other.
    private val eyeDx = FloatArray(2)
    private val eyeDy = FloatArray(2)
    private val eyeSize = FloatArray(2)
    private val eyeLid = FloatArray(2)

    private var dbgMs = 0L
    private var lastFrameMs = 0L
    private var lastCx = 0f
    private var stretch = 0f
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
        removeCallbacks(endPreview)
        removeCallbacks(endFidget)
    }

    fun show(next: Look) {
        val previous = look
        look = next
        if (next.startled && previous?.startled != true) hopStartMs = SystemClock.uptimeMillis()
        if (next.gaze !is Gaze.Mostly) glancing = false
        if (next.expression != previous?.expression) {
            removeCallbacks(endFidget)
            fidget = null
        }
        retarget()
    }

    /** Plays [a] over whatever the face is doing; a new act cuts off the last. */
    fun play(a: Act) {
        act = a
        actStartMs = SystemClock.uptimeMillis()
        if (a == Act.WINK) winkSide = if (Random.nextBoolean()) 1f else -1f
        kick()
    }

    /** Holds [e] for [ms] whatever the mood says, then gives the face back to it. */
    fun preview(e: Expression, ms: Long) {
        preview = e
        removeCallbacks(endPreview)
        postDelayed(endPreview, ms)
        retarget()
    }

    /** A hello: two quick blinks. */
    fun doubleBlink() {
        blink()
        removeCallbacks(secondBlink)
        postDelayed(secondBlink, DOUBLE_BLINK_GAP_MS)
    }

    private fun shown() = preview ?: fidget ?: look?.expression

    private fun blink() {
        // Closed eyes, asleep or happy, have nothing to blink; acts that
        // reshape the eyes would only be interrupted by one.
        if (shown()?.params?.open == 0f) return
        if (act == Act.LOVE || act == Act.DIZZY || act == Act.JOY || act == Act.WINK) return
        blinkStartMs = SystemClock.uptimeMillis()
        kick()
    }

    private val secondBlink = Runnable { blink() }

    private val endPreview = Runnable {
        preview = null
        retarget()
    }

    private val endFidget = Runnable {
        fidget = null
        retarget()
    }

    private val blinkTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (Random.nextFloat() < DOUBLE_BLINK_CHANCE) doubleBlink() else blink()
            postDelayed(this, nextBlinkMs())
        }
    }

    /** Picks a new spot to look at while wandering, glances away while focused, fidgets while idle. */
    private val idleTick = object : Runnable {
        override fun run() {
            if (!running) return
            val l = look
            when {
                l == null || act != null || preview != null -> Unit
                shown() == Expression.CLOSED -> if (Random.nextFloat() < ZZZ_CHANCE) play(Act.ZZZ)
                l.gaze == Gaze.Wander -> if (Random.nextFloat() < SCAN_CHANCE) {
                    play(Act.SCAN)
                } else {
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
                l.gaze is Gaze.Mostly -> if (Random.nextFloat() < GLANCE_CHANCE) {
                    glancing = true
                    retarget()
                    postDelayed(endGlance, GLANCE_MS)
                }
                l.expression == Expression.NEUTRAL && !l.amber && fidget == null ->
                    if (Random.nextFloat() < FIDGET_CHANCE) fidget()
            }
            postDelayed(this, nextIdleMs())
        }
    }

    /** Something small to do while you sit there, so the face feels alive. */
    private fun fidget() {
        when (Random.nextInt(4)) {
            0 -> play(Act.WINK)
            1 -> holdFidget(Expression.CURIOUS, 1_600L)
            2 -> holdFidget(Expression.SQUINT, 1_100L)
            else -> doubleBlink()
        }
    }

    private fun holdFidget(e: Expression, ms: Long) {
        fidget = e
        removeCallbacks(endFidget)
        postDelayed(endFidget, ms)
        retarget()
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
        target = (shown() ?: l.expression).params.copy(
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
        mask?.recycle()
        grid = null
        mask = null
        maskCanvas = null
        if (w <= 0 || h <= 0) return
        pitch = h / ROWS
        dotR = pitch * DOT
        // A column and a row through the middle of the screen, so a centred face sits symmetric on the grid.
        x0 = (w / 2f) % pitch
        y0 = (h / 2f) % pitch
        cols = ceil((w - x0) / pitch).toInt()
        rows = ceil((h - y0) / pitch).toInt()

        val panel = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(panel).apply {
            drawColor(bgColor)
            for (r in 0 until rows) for (c in 0 until cols) drawCircle(x0 + c * pitch, y0 + r * pitch, dotR, offPaint)
        }
        grid = panel

        // One mask pixel per dot, its centre on the dot's centre.
        val m = Bitmap.createBitmap(cols, rows, Bitmap.Config.ALPHA_8)
        maskCanvas = Canvas(m).apply {
            concat(
                Matrix().apply {
                    setTranslate(-x0, -y0)
                    postScale(1f / pitch, 1f / pitch)
                    postTranslate(0.5f, 0.5f)
                },
            )
        }
        mask = m
        maskRowBytes = m.rowBytes
        maskPixels = ByteBuffer.allocate(m.byteCount)
        lit = FloatArray(2 * cols * rows)

        dotPaint.strokeWidth = 2f * dotR
        zPaint.strokeWidth = 2f * dotR
        glow.maskFilter = BlurMaskFilter(h * GLOW, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val panel = grid
        val m = mask
        val mc = maskCanvas
        val pixels = maskPixels
        if (panel == null || m == null || mc == null || pixels == null) {
            canvas.drawColor(bgColor)
            return
        }
        val now = SystemClock.uptimeMillis()
        val fresh = lastFrameMs == 0L
        val dtMs = if (fresh) FRAME_MS else max(min(now - lastFrameMs, MAX_STEP_MS), 1L)
        lastFrameMs = now
        current = current.approach(target, 1f - exp(-dtMs / EASE_MS).toFloat())
        if (current.distanceTo(target) < SETTLED) current = target
        val p = current

        val blinkT = (now - blinkStartMs) / BLINK_MS.toFloat()
        val blinking = blinkT in 0f..1f
        val closure = if (blinking) envelope(blinkT, 0.4f, 0.6f) else 0f

        val hopT = (now - hopStartMs) / HOP_MS.toFloat()
        val hopping = hopT in 0f..1f
        var lift = if (hopping) sin(PI * hopT).toFloat() * HOP else 0f

        var playing = act
        val t = if (playing == null) 0f else (now - actStartMs) / playing.ms.toFloat()
        if (t >= 1f) {
            act = null
            playing = null
        }

        // What this frame draws: the eased expression, with any act layered on.
        var open = p.open
        var arc = p.arc
        var lid = p.lid
        var slant = p.slant
        var squint = p.squint
        var tilt = p.tilt
        var wid = p.width
        var hei = p.height
        var gx = p.gazeX
        var gy = p.gazeY
        var shake = 0f
        var eyeScale = 1f
        var heart = 0f
        var wink = 0f
        eyeDx.fill(0f)
        eyeDy.fill(0f)
        eyeSize.fill(1f)
        eyeLid.fill(0f)
        when (playing) {
            Act.JOY -> {
                val e = envelope(t, 0.12f, 0.2f)
                val k = 1f - e
                open *= k
                arc += (1f - arc) * e
                lid *= k
                slant *= k
                squint *= k
                tilt *= k
                wid += (1f - wid) * e
                hei += (1f - hei) * e
                lift += abs(sin(PI * JOY_BOUNCES * t)).toFloat() * JOY_HOP * e
            }
            Act.WINK -> wink = envelope(t, 0.2f, 0.3f)
            Act.NO -> shake = sin(2.0 * PI * NO_SHAKES * t).toFloat() * NO_SHAKE * (1f - t)
            Act.SCAN -> {
                val e = envelope(t, 0.12f, 0.18f)
                val aim = -SCAN_X + 2f * SCAN_X * smooth((t - 0.4f) / 0.2f)
                gx += (aim - gx) * e
                gy += (SCAN_Y - gy) * e
            }
            Act.LOVE -> {
                val e = envelope(t, 0.12f, 0.14f)
                eyeScale = 1f - e
                heart = backOut(e) * heartbeat(t)
            }
            Act.DIZZY -> {
                // Woozy: the head lolls round and round, tipping one eye up as
                // the other drops and swapping their sizes, under heavy, uneven
                // lids. Then a quick shake of the head to snap out of it.
                val e = envelope(t, 0.1f, 0.3f)
                val spin = (2.0 * PI * DIZZY_TURNS * t).toFloat()
                for (i in 0..1) {
                    val side = SIDES[i]
                    eyeDx[i] = cos(spin) * DIZZY_X * e
                    eyeDy[i] = side * sin(spin) * DIZZY_Y * e
                    eyeSize[i] = 1f + side * sin(spin) * DIZZY_SIZE * e
                    eyeLid[i] = (if (side < 0f) DIZZY_LID_LEFT else DIZZY_LID_RIGHT) * e
                }
                slant += (DIZZY_SLANT - slant) * e
                val s = (t - DIZZY_SHAKE_AT) / (1f - DIZZY_SHAKE_AT)
                if (s in 0f..1f) shake = sin(2.0 * PI * 2.0 * s).toFloat() * NO_SHAKE * (1f - s)
            }
            Act.ZZZ, null -> Unit
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val rawCx = w / 2f + (gx * GAZE_X + shake) * h
        // Snapped to a dot: a shape centred between dots comes out lopsided.
        val cx = snap(rawCx, x0)
        val cy = snap(h * FACE_Y + gy * GAZE_Y * h - lift * h, y0)
        // Centre to each eye in whole dots, so the two are mirror images.
        val half = whole(h * EYE_GAP / 2f)

        // Moving fast sideways stretches the eyes along the way they go.
        val speed = if (fresh) 0f else abs(rawCx - lastCx) / h / dtMs
        lastCx = rawCx
        stretch += (min(speed * MOTION_STRETCH, MAX_STRETCH) - stretch) * 0.5f
        if (stretch < 0.001f) stretch = 0f

        val baseW = h * EYE_W
        val baseH = h * EYE_H
        shapes.rewind()
        for (i in 0..1) {
            val side = SIDES[i]
            val ex = cx + side * half + whole(eyeDx[i] * h)
            // A tilted head lifts and enlarges one eye.
            val ey = cy - whole(side * tilt * TILT_RISE * h) + whole(eyeDy[i] * h)
            val scale = (1f + side * tilt * TILT) * eyeSize[i]
            if (eyeScale > 0.02f) {
                val winking = side == winkSide
                val shut = if (winking) max(closure, wink) else closure
                val s = scale * eyeScale
                // A winking eye closes into a "^".
                val bendTo = if (winking) arc + (1f - arc) * wink else arc
                addEye(
                    ex = ex,
                    ey = ey,
                    ew = baseW * wid * s * (1f + stretch) * (1f + BLINK_WIDEN * shut),
                    fullH = baseH * hei * s * (1f - stretch * 0.6f),
                    open = open * (1f - shut),
                    lid = lid + eyeLid[i],
                    slant = slant,
                    // The other eye smiles along with a wink.
                    squint = if (winking) squint else squint + max(WINK_SQUINT - squint, 0f) * wink,
                    arc = bendTo,
                    side = side,
                    // A bent line is drawn thicker, so a "^" stays solid as it curves.
                    line = pitch * (LINE_DOTS + abs(bendTo) * BENT_LINE_DOTS) * eyeScale,
                )
            }
            if (heart > 0.01f) addHeart(ex, ey, baseW * HEART * heart * scale)
        }
        // The right eye's outermost column, and the top row of a fully open eye.
        val rightEdge = cx + half + floor(baseW * p.width / 2f / pitch) * pitch
        if (p.exclaim > 0.5f) addBangs(rightEdge, cy - floor(baseH * p.height / 2f / pitch) * pitch)

        // Which dots light: the shapes laid on the grid.
        m.eraseColor(0)
        mc.drawPath(shapes, maskPaint)
        pixels.rewind()
        m.copyPixelsToBuffer(pixels)
        val bytes = pixels.array()
        var n = 0
        for (r in 0 until rows) {
            val rowStart = r * maskRowBytes
            for (c in 0 until cols) {
                if (bytes[rowStart + c].toInt() and 0xFF >= LIT) {
                    lit[n++] = x0 + c * pitch
                    lit[n++] = y0 + r * pitch
                }
            }
        }
        if (now - dbgMs > 1_000L) {
            dbgMs = now
            val b = android.graphics.RectF()
            shapes.computeBounds(b, true)
            val sb = StringBuilder("DBG rows=$rows cols=$cols rb=$maskRowBytes bc=${m.byteCount} pitch=$pitch x0=$x0 y0=$y0 cy=$cy open=$open hei=$hei bounds=$b\n")
            for (r in 0 until rows) {
                for (c in 0 until cols) sb.append(if (bytes[r * maskRowBytes + c].toInt() != 0) '#' else '.')
                sb.append('\n')
            }
            android.util.Log.d("NODEDBG", sb.toString())
        }

        val colour = blend(eyeColor, amberColor, p.amber)
        canvas.drawBitmap(panel, 0f, 0f, null)
        glow.color = colour
        glow.alpha = GLOW_ALPHA
        canvas.drawPath(shapes, glow)
        dotPaint.color = colour
        if (n > 0) canvas.drawPoints(lit, 0, n, dotPaint)
        if (playing == Act.ZZZ) drawZs(canvas, t, rightEdge + pitch, cy - 2f * pitch, colour)

        if (blinking || hopping || playing != null || current != target || stretch > 0f) {
            val onlyZs = playing == Act.ZZZ && !blinking && !hopping && current == target && stretch == 0f
            if (onlyZs) postInvalidateDelayed(SLOW_FRAME_MS) else postInvalidateOnAnimation()
        } else {
            animating = false
        }
    }

    /**
     * Adds one eye to [shapes]: a rounded rectangle, sampled column by column
     * so lids can cut it and a closing eye can bend into "^" or "◡".
     */
    private fun addEye(
        ex: Float,
        ey: Float,
        ew: Float,
        fullH: Float,
        open: Float,
        lid: Float,
        slant: Float,
        squint: Float,
        arc: Float,
        side: Float,
        line: Float,
    ) {
        val eh = max(fullH * open, line)
        // Lids belong to an open eye; they fade out as it shuts.
        val lidK = min(open * 2f, 1f)
        // A squint's lower lid: half of it shortens the eye from below, which
        // keeps the corners round, and half bows the bottom edge up in the middle.
        val cheek = eh * squint * lidK / 2f
        val halfW = ew / 2f
        val rectH = eh - cheek
        // Rounder as it shuts, so a closed eye is a line with round ends.
        val roundness = CORNER + (0.5f - CORNER) * (1f - min(open * 3f, 1f))
        val r = min(min(ew, rectH) * roundness, rectH / 2f)
        val bend = arc * fullH * BEND * (1f - open)
        for (i in 0..SAMPLES) {
            val u = U[i]
            val dx = max(abs(u) * halfW - (halfW - r), 0f)
            val corner = r - sqrt(max(r * r - dx * dx, 0f))
            val eyeTop = ey - eh / 2f - bend * (1f - u * u)
            // 0 at the corner nearest the nose, 1 at the outer one.
            val outer = 0.5f + 0.5f * u * side
            val drop = lid + SLANT * (max(slant, 0f) * outer + max(-slant, 0f) * (1f - outer))
            val top = max(eyeTop + corner, eyeTop + eh * drop * lidK)
            val bottom = eyeTop + rectH - corner - cheek * (1f - u * u)
            // Where a lid has cut right through, the column pinches to nothing.
            val mid = (top + bottom) / 2f
            tops[i] = min(top, mid)
            bottoms[i] = max(bottom, mid)
        }
        shapes.moveTo(ex + U[0] * halfW, tops[0])
        for (i in 1..SAMPLES) shapes.lineTo(ex + U[i] * halfW, tops[i])
        for (i in SAMPLES downTo 0) shapes.lineTo(ex + U[i] * halfW, bottoms[i])
        shapes.close()
    }

    private fun addHeart(x: Float, y: Float, half: Float) {
        shapes.moveTo(x + HEART_PTS[0] * half, y + HEART_PTS[1] * half)
        var i = 2
        while (i < HEART_PTS.size) {
            shapes.lineTo(x + HEART_PTS[i] * half, y + HEART_PTS[i + 1] * half)
            i += 2
        }
        shapes.close()
    }

    /**
     * "!!" beside the right eye, whose outermost dot column is [eyeRight],
     * top-aligned with [eyeTop], the top row of a fully open eye, so a blink
     * does not move it. Each "!" is a bar over a dot, its edges between dots
     * so every dot is clearly in or out.
     */
    private fun addBangs(eyeRight: Float, eyeTop: Float) {
        val left0 = eyeRight + (BANG_CLEAR_DOTS + 0.5f) * pitch
        val top = eyeTop - 0.5f * pitch
        for (mark in 0..1) {
            val left = left0 + mark * (BANG_W_DOTS + BANG_SPACE_DOTS) * pitch
            val right = left + BANG_W_DOTS * pitch
            shapes.addRect(left, top, right, top + BANG_H_DOTS * pitch, Path.Direction.CW)
            val dotTop = top + (BANG_H_DOTS + 1) * pitch
            shapes.addRect(left, dotTop, right, dotTop + BANG_W_DOTS * pitch, Path.Direction.CW)
        }
    }

    /**
     * Z's drifting up and to the right from ([x], [y]), each bigger than the
     * last and fading in and out: a row of dots, a diagonal, a row of dots.
     */
    private fun drawZs(canvas: Canvas, t: Float, x: Float, y: Float, colour: Int) {
        zPaint.color = colour
        for (k in 0 until Z_COUNT) {
            val zt = (t - k * Z_STAGGER) / Z_LIFE
            if (zt !in 0f..1f) continue
            val size = Z_FIRST_DOTS + k
            val left = snap(x + zt * Z_DRIFT_DOTS * pitch, x0)
            val bottom = snap(y - zt * Z_RISE_DOTS * pitch, y0)
            val top = bottom - (size - 1) * pitch
            var n = 0
            for (c in 0 until size) {
                zDots[n++] = left + c * pitch
                zDots[n++] = top
                zDots[n++] = left + c * pitch
                zDots[n++] = bottom
            }
            for (d in 1 until size - 1) {
                zDots[n++] = left + (size - 1 - d) * pitch
                zDots[n++] = top + d * pitch
            }
            zPaint.alpha = (Z_ALPHA * sin(PI * zt)).toInt().coerceIn(0, 255)
            canvas.drawPoints(zDots, 0, n, zPaint)
        }
    }

    /** The nearest dot centre to [v] on a grid starting at [origin]. */
    private fun snap(v: Float, origin: Float) = origin + ((v - origin) / pitch).roundToInt() * pitch

    /** [v] rounded to whole dots. */
    private fun whole(v: Float) = (v / pitch).roundToInt() * pitch

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

        /** Mask coverage, of 255, at which a dot lights. */
        const val LIT = 128

        // Geometry, as fractions of the view's height.
        const val EYE_W = 0.34f
        const val EYE_H = 0.40f
        /** Centre to centre: the eyes sit close, a small gap between them. */
        const val EYE_GAP = 0.43f
        const val FACE_Y = 0.44f
        const val GAZE_X = 0.40f
        const val GAZE_Y = 0.13f
        const val HOP = 0.06f

        /** Corner radius as a fraction of the eye's smaller side; 0.5 would be a pill. */
        const val CORNER = 0.28f

        /** A closed eye's line in dots, and how much thicker it gets fully bent into "^" or "◡". */
        const val LINE_DOTS = 1.5f
        const val BENT_LINE_DOTS = 1.2f

        /** How far a closed eye's line arches or sags, as a fraction of the eye's height. */
        const val BEND = 0.3f

        /** How far a fully slanted lid drops at its low end, as a fraction of the eye's height. */
        const val SLANT = 0.45f

        /** A full tilt's difference in size, and in height, between the two eyes. */
        const val TILT = 0.09f
        const val TILT_RISE = 0.035f

        /** A blinking eye widens a touch as it squashes. */
        const val BLINK_WIDEN = 0.08f
        const val MOTION_STRETCH = 22f
        const val MAX_STRETCH = 0.14f

        /** A soft haze behind the lit dots, as LEDs bleed into a diffuser. */
        const val GLOW = 0.05f
        const val GLOW_ALPHA = 70

        /** Columns per eye outline; sampled densest at the sides, where the corners turn fastest. */
        const val SAMPLES = 48
        val U = FloatArray(SAMPLES + 1) { -cos(PI * it / SAMPLES).toFloat() }

        /** "!" in dots: a bar two wide and seven tall, a one-dot gap, then a two-by-two dot. */
        const val BANG_W_DOTS = 2
        const val BANG_H_DOTS = 7
        const val BANG_SPACE_DOTS = 2
        const val BANG_CLEAR_DOTS = 2

        // Acts.
        const val JOY_BOUNCES = 3f
        const val JOY_HOP = 0.05f
        const val WINK_SQUINT = 0.3f
        const val NO_SHAKES = 2.5
        const val NO_SHAKE = 0.06f
        const val SCAN_X = 0.85f
        const val SCAN_Y = -0.15f
        const val HEART = 0.52f
        const val DIZZY_TURNS = 2.0
        const val DIZZY_X = 0.07f
        const val DIZZY_Y = 0.06f
        const val DIZZY_SIZE = 0.1f
        const val DIZZY_LID_LEFT = 0.3f
        const val DIZZY_LID_RIGHT = 0.12f
        const val DIZZY_SLANT = 0.4f
        const val DIZZY_SHAKE_AT = 0.72f
        const val Z_COUNT = 3
        const val Z_FIRST_DOTS = 3
        const val Z_MAX_DOTS = 3 * (Z_FIRST_DOTS + Z_COUNT - 1)
        const val Z_STAGGER = 0.22f
        const val Z_LIFE = 0.56f
        const val Z_DRIFT_DOTS = 5f
        const val Z_RISE_DOTS = 9f
        const val Z_ALPHA = 220.0

        // Motion.
        const val EASE_MS = 90.0
        const val SETTLED = 0.002f
        const val FRAME_MS = 16L
        const val SLOW_FRAME_MS = 33L
        const val MAX_STEP_MS = 100L
        const val BLINK_MS = 180L
        const val HOP_MS = 380L
        const val DOUBLE_BLINK_GAP_MS = 280L
        const val DOUBLE_BLINK_CHANCE = 0.15f

        // Idle behaviour, per idle tick.
        const val WANDER_X = 0.7f
        const val WANDER_Y = 0.4f
        const val GLANCE_CHANCE = 0.25f
        const val GLANCE_MS = 1_200L
        const val SCAN_CHANCE = 0.2f
        const val FIDGET_CHANCE = 0.08f
        const val ZZZ_CHANCE = 0.3f

        fun nextBlinkMs() = Random.nextLong(2_500L, 6_000L)
        fun nextIdleMs() = Random.nextLong(1_500L, 4_000L)

        /** The classic heart curve, scaled to span -1..1 across and centred. */
        val HEART_PTS: FloatArray = run {
            val n = 72
            val xs = FloatArray(n) { 16f * sin(2.0 * PI * it / n).pow(3).toFloat() }
            val ys = FloatArray(n) {
                val a = 2.0 * PI * it / n
                -(13 * cos(a) - 5 * cos(2 * a) - 2 * cos(3 * a) - cos(4 * a)).toFloat()
            }
            val mid = (ys.max() + ys.min()) / 2f
            FloatArray(2 * n) { if (it % 2 == 0) xs[it / 2] / 16f else (ys[it / 2] - mid) / 16f }
        }

        /** Dots drawn as round points: one call for the lot. */
        fun roundDots() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        /** Rises over the first [rise] of 0..1 and falls over the last [fall]. */
        fun envelope(t: Float, rise: Float, fall: Float) = smooth(t / rise) * (1f - smooth((t - (1f - fall)) / fall))

        /** Eases out past 1 and settles back: a pop. */
        fun backOut(x: Float): Float {
            val c = 1.7f
            val y = x - 1f
            return 1f + (c + 1f) * y * y * y + c * y * y
        }

        /** A lub-dub, three times over the act. */
        fun heartbeat(t: Float): Float {
            val ph = (t * 3f) % 1f
            fun beat(at: Float) = exp(-((ph - at) / 0.06f).let { it * it })
            return 1f + 0.1f * (beat(0.15f) + 0.6f * beat(0.38f))
        }

        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
