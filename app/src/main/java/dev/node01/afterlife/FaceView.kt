package dev.node01.afterlife

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A dot-matrix face: the whole screen is a grid of faint unlit dots, and the
 * eyes are the dots that light, over a soft glow.
 *
 * Every shape -- an open eye, a lidded one, the "^" of a happy one, a heart --
 * is worked out each frame from the current [EyeParams] as smooth geometry,
 * then each dot is simply on or off: on when its centre falls inside, edges
 * included. Each eye is centred on a dot, the two sit whole dots apart, and
 * dots are tested at whole-dot offsets from their eye's centre, so the two
 * halves of an eye, and the two eyes, are exact mirror images. They move in
 * crisp dot-sized steps, like an LED panel. Behind them, a glow: the lit dots
 * again, one pixel each, softened and stretched over the panel.
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

    /** The session usage readout: information, so grey, like the captions. */
    private val usagePaint = roundDots().apply { color = color(R.color.node_secondary) }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // The panel, rebuilt only when the size changes.
    private var grid: Bitmap? = null
    private var pitch = 1f
    private var dotR = 1f
    private var x0 = 0f
    private var y0 = 0f
    private var cols = 0
    private var rows = 0

    /** Which dots light this frame, one byte per dot, row by row; then their centres, to draw. */
    private var on = ByteArray(0)
    private var lit = FloatArray(0)

    /**
     * The glow: [on] softened by a few box blurs, one pixel per dot, drawn
     * scaled up with filtering. Far cheaper than blurring screen-sized shapes.
     */
    private var glowMap: Bitmap? = null
    private var glowRowBytes = 0
    private var glowBytes = ByteArray(0)
    private var glowBuffer: ByteBuffer = ByteBuffer.allocate(0)
    private var blurA = IntArray(0)
    private var blurB = IntArray(0)
    private val glowSrc = Rect()
    private val glowDst = RectF()
    private val zDots = FloatArray(2 * Z_MAX_DOTS)

    private var usagePercent: Int? = null
    private var timerText: String? = null
    private var clockText: String? = null
    private val glyphDots = FloatArray(2 * GLYPH_W * GLYPH_H * MAX_GLYPHS)

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

    // The eye being drawn, worked out once by shapeEye() for both its glow and its dots.
    private var eHalfW = 0f
    private var eH = 0f
    private var eRectH = 0f
    private var eR = 0f
    private var eBend = 0f
    private var eLid = 0f
    private var eSlant = 0f
    private var eLidK = 0f
    private var eSide = 0f

    // What edges() found: an eye column's top and bottom, relative to the eye's centre.
    private var eTop = 0f
    private var eBottom = 0f

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

    /** Shows [percent] (0..100) in the top-left corner in dot digits; null shows nothing. */
    fun showUsage(percent: Int?) {
        if (percent == usagePercent) return
        usagePercent = percent
        kick()
    }

    /** A timer counting down, as `M:SS`, in the top-right corner; null shows nothing. */
    fun showTimer(text: String?) {
        if (text == timerText) return
        timerText = text
        kick()
    }

    /** The time, large and alone in the middle of a dark room; null shows nothing. */
    fun showClock(text: String?) {
        if (text == clockText) return
        clockText = text
        kick()
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
        glowMap?.recycle()
        grid = null
        glowMap = null
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
        on = ByteArray(cols * rows)
        lit = FloatArray(2 * cols * rows)
        val g = Bitmap.createBitmap(cols, rows, Bitmap.Config.ALPHA_8)
        glowMap = g
        glowRowBytes = g.rowBytes
        glowBytes = ByteArray(g.rowBytes * rows)
        glowBuffer = ByteBuffer.wrap(glowBytes)
        blurA = IntArray(cols * rows)
        blurB = IntArray(cols * rows)

        dotPaint.strokeWidth = 2f * dotR
        zPaint.strokeWidth = 2f * dotR
        usagePaint.strokeWidth = 2f * dotR
    }

    override fun onDraw(canvas: Canvas) {
        val panel = grid
        val glowMap = glowMap
        if (panel == null || glowMap == null) {
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
                // Woozy: from plain open eyes, whatever the face was doing --
                // a shake wakes it -- the head lolls round and round, tipping
                // one eye up as the other drops and swapping their sizes, under
                // uneven lids. Then a quick shake of the head to snap out of it.
                val e = envelope(t, 0.1f, 0.3f)
                val k = 1f - e
                open += (1f - open) * e
                arc *= k
                lid *= k
                slant *= k
                squint *= k
                tilt *= k
                wid += (1f - wid) * e
                hei += (1f - hei) * e
                val spin = (2.0 * PI * DIZZY_TURNS * t).toFloat()
                for (i in 0..1) {
                    val side = SIDES[i]
                    eyeDx[i] = cos(spin) * DIZZY_X * e
                    eyeDy[i] = side * sin(spin) * DIZZY_Y * e
                    eyeSize[i] = 1f + side * sin(spin) * DIZZY_SIZE * e
                    eyeLid[i] = (if (side < 0f) DIZZY_LID_LEFT else DIZZY_LID_RIGHT) * e
                }
                val s = (t - DIZZY_SHAKE_AT) / (1f - DIZZY_SHAKE_AT)
                if (s in 0f..1f) shake = sin(2.0 * PI * 2.0 * s).toFloat() * NO_SHAKE * (1f - s)
            }
            Act.ZZZ, null -> Unit
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val rawCx = w / 2f + (gx * GAZE_X + shake) * h
        // The face's centre dot: a shape centred between dots comes out lopsided.
        val cCol = ((rawCx - x0) / pitch).roundToInt()
        val cRow = ((h * FACE_Y + gy * GAZE_Y * h - lift * h - y0) / pitch).roundToInt()
        val cy = y0 + cRow * pitch
        // Centre to each eye in whole dots, so the two are mirror images.
        val halfCols = (h * EYE_GAP / 2f / pitch).roundToInt()

        // Moving fast sideways stretches the eyes along the way they go.
        val speed = if (fresh) 0f else abs(rawCx - lastCx) / h / dtMs
        lastCx = rawCx
        stretch += (min(speed * MOTION_STRETCH, MAX_STRETCH) - stretch) * 0.5f
        if (stretch < 0.001f) stretch = 0f

        val baseW = h * EYE_W
        val baseH = h * EYE_H
        on.fill(0)
        var rightCol = cCol
        for (i in 0..1) {
            val side = SIDES[i]
            val col = cCol + (if (i == 0) -halfCols else halfCols) + dots(eyeDx[i] * h)
            // A tilted head lifts and enlarges one eye.
            val row = cRow - dots(side * tilt * TILT_RISE * h) + dots(eyeDy[i] * h)
            if (i == 1) rightCol = col
            val scale = (1f + side * tilt * TILT) * eyeSize[i]
            if (eyeScale > 0.02f) {
                val winking = side == winkSide
                val shut = if (winking) max(closure, wink) else closure
                val s = scale * eyeScale
                // A winking eye closes into a "^".
                val bendTo = if (winking) arc + (1f - arc) * wink else arc
                shapeEye(
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
                lightEye(col, row)
            }
            if (heart > 0.01f) lightHeart(col, row, baseW * HEART * heart * scale)
        }
        // The right eye's outermost column, and the top row of a fully open eye.
        val rightEdge = rightCol + floor(baseW * p.width / 2f / pitch).toInt()
        if (p.exclaim > 0.5f) addBangs(rightEdge, cRow - floor(baseH * p.height / 2f / pitch).toInt())

        var n = 0
        var minC = cols
        var maxC = -1
        var minR = rows
        var maxR = -1
        for (k in on.indices) {
            if (on[k].toInt() != 0) {
                val c = k % cols
                val r = k / cols
                lit[n++] = x0 + c * pitch
                lit[n++] = y0 + r * pitch
                minC = min(minC, c)
                maxC = max(maxC, c)
                minR = min(minR, r)
                maxR = max(maxR, r)
            }
        }

        val colour = blend(eyeColor, amberColor, p.amber)
        canvas.drawBitmap(panel, 0f, 0f, null)
        if (n > 0) drawGlow(canvas, glowMap, colour, minC, maxC, minR, maxR)
        dotPaint.color = colour
        if (n > 0) canvas.drawPoints(lit, 0, n, dotPaint)
        if (playing == Act.ZZZ) drawZs(canvas, t, x0 + (rightEdge + 1) * pitch, cy - 2f * pitch, colour)
        usagePercent?.let { drawGlyphs(canvas, "${it.coerceIn(0, 100)}%", USAGE_COL, USAGE_ROW, usagePaint) }
        // The countdown sits opposite the usage, out of the eyes' way.
        timerText?.let { drawGlyphs(canvas, it, dots(width.toFloat()) - glyphsWidth(it) - USAGE_COL, USAGE_ROW, usagePaint) }
        // A knock in the dark: the time alone, where the eyes would be.
        clockText?.let { drawGlyphs(canvas, it, (dots(width.toFloat()) - glyphsWidth(it)) / 2, CLOCK_ROW, usagePaint) }

        if (blinking || hopping || playing != null || current != target || stretch > 0f) {
            val onlyZs = playing == Act.ZZZ && !blinking && !hopping && current == target && stretch == 0f
            if (onlyZs) postInvalidateDelayed(SLOW_FRAME_MS) else postInvalidateOnAnimation()
        } else {
            animating = false
        }
    }

    /**
     * Works out one eye's shape, for [edges]: a rounded rectangle, cut by lids
     * and, as it closes, bent into "^" or "◡".
     */
    private fun shapeEye(
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
        eH = max(fullH * open, line)
        // Lids belong to an open eye; they fade out as it shuts.
        eLidK = min(open * 2f, 1f)
        // A squint's lower lid shortens the eye from below, keeping its round
        // corners. On dots a curved lid would leave stray stubs at the corners.
        eHalfW = ew / 2f
        eRectH = eH * (1f - squint * eLidK)
        // Rounder as it shuts, so a closed eye is a line with round ends.
        val roundness = CORNER + (0.5f - CORNER) * (1f - min(open * 3f, 1f))
        eR = min(min(ew, eRectH) * roundness, eRectH / 2f)
        eBend = arc * fullH * BEND * (1f - open)
        eLid = lid
        eSlant = slant
        eSide = side
    }

    /**
     * The eye's top and bottom edges at [u], -1..1 across it, relative to its
     * centre, into [eTop] and [eBottom]; the top is below the bottom where a
     * lid has cut right through. Only the slant tells u from -u, so an eye is
     * symmetric unless it is meant not to be.
     */
    private fun edges(u: Float) {
        val dx = max(abs(u) * eHalfW - (eHalfW - eR), 0f)
        val corner = eR - sqrt(max(eR * eR - dx * dx, 0f))
        val top0 = -eH / 2f - eBend * (1f - u * u)
        // 0 at the corner nearest the nose, 1 at the outer one.
        val outer = 0.5f + 0.5f * u * eSide
        val drop = eLid + SLANT * (max(eSlant, 0f) * outer + max(-eSlant, 0f) * (1f - outer))
        eTop = max(top0 + corner, top0 + eH * drop * eLidK)
        eBottom = top0 + eRectH - corner
    }

    /** Lights the eye centred on the dot at [col], [row]: every dot whose centre is inside it. */
    private fun lightEye(col: Int, row: Int) {
        if (eHalfW <= 0f) return
        val reach = (eHalfW / pitch).toInt()
        for (i in -reach..reach) {
            edges(i * pitch / eHalfW)
            if (eTop > eBottom) continue
            light(col + i, row + ceil(eTop / pitch).toInt(), row + floor(eBottom / pitch).toInt())
        }
    }

    /**
     * Lights a heart [half] pixels across each way, centred on the dot at
     * [col], [row]: inside the curve (x² + y² - 1)³ = x²y³.
     */
    private fun lightHeart(col: Int, row: Int, half: Float) {
        val unit = half / HEART_HALF_W / pitch
        if (unit <= 0f) return
        val reachX = ceil(HEART_HALF_W * unit).toInt()
        val reachY = ceil(HEART_REACH_Y * unit).toInt()
        for (i in -reachX..reachX) {
            val x = i / unit
            val x2 = x * x
            for (j in -reachY..reachY) {
                // Up is +y here; the curve's own middle is a little above 0.
                val y = HEART_MID - j / unit
                val a = x2 + y * y - 1f
                if (a * a * a - x2 * y * y * y <= 0f) light(col + i, row + j, row + j)
            }
        }
    }

    /** Lights column [col] from [fromRow] to [toRow], both included, where they are on the panel. */
    private fun light(col: Int, fromRow: Int, toRow: Int) {
        if (col < 0 || col >= cols) return
        for (r in max(fromRow, 0)..min(toRow, rows - 1)) on[r * cols + col] = 1
    }

    /**
     * "!!" beside the right eye, whose outermost dot column is [eyeRight],
     * top-aligned with [topRow], the top row of a fully open eye, so a blink
     * does not move it. Each "!" is a bar over a dot.
     */
    private fun addBangs(eyeRight: Int, topRow: Int) {
        for (mark in 0..1) {
            val col0 = eyeRight + 1 + BANG_CLEAR_DOTS + mark * (BANG_W_DOTS + BANG_SPACE_DOTS)
            for (k in 0 until BANG_W_DOTS) {
                light(col0 + k, topRow, topRow + BANG_H_DOTS - 1)
                light(col0 + k, topRow + BANG_H_DOTS + 1, topRow + BANG_H_DOTS + BANG_W_DOTS)
            }
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
            // Each further up a diagonal than the last, so they never overlap, and drifting a little.
            val left = snap(x + (k * Z_STEP_X_DOTS + zt * Z_DRIFT_DOTS) * pitch, x0)
            val bottom = snap(y - (k * Z_STEP_Y_DOTS + zt * Z_RISE_DOTS) * pitch, y0)
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

    /**
     * The glow behind the lit dots: [on] blurred into [glowMap], then drawn
     * stretched over the panel, a pixel to a dot, filtered into a soft haze.
     * Only the lit area, plus the blur's reach, is worked out and drawn.
     */
    private fun drawGlow(canvas: Canvas, map: Bitmap, colour: Int, minC: Int, maxC: Int, minR: Int, maxR: Int) {
        val c0 = max(minC - GLOW_REACH, 0)
        val c1 = min(maxC + GLOW_REACH, cols - 1)
        val r0 = max(minR - GLOW_REACH, 0)
        val r1 = min(maxR + GLOW_REACH, rows - 1)
        for (r in r0..r1) for (c in c0..c1) blurA[r * cols + c] = if (on[r * cols + c].toInt() != 0) 255 else 0
        repeat(GLOW_PASSES) {
            // A three-wide box blur across, then down: twice over, near enough a Gaussian.
            for (r in r0..r1) {
                for (c in c0..c1) {
                    val k = r * cols + c
                    val left = if (c > c0) blurA[k - 1] else 0
                    val right = if (c < c1) blurA[k + 1] else 0
                    blurB[k] = (left + blurA[k] + right) / 3
                }
            }
            for (r in r0..r1) {
                for (c in c0..c1) {
                    val k = r * cols + c
                    val up = if (r > r0) blurB[k - cols] else 0
                    val down = if (r < r1) blurB[k + cols] else 0
                    blurA[k] = (up + blurB[k] + down) / 3
                }
            }
        }
        for (r in r0..r1) for (c in c0..c1) glowBytes[r * glowRowBytes + c] = blurA[r * cols + c].toByte()
        glowBuffer.rewind()
        map.copyPixelsFromBuffer(glowBuffer)
        // Pixel (c, r) is the dot at (c, r): its centre lands on the dot's centre.
        glowSrc.set(c0, r0, c1 + 1, r1 + 1)
        glowDst.set(
            x0 + (c0 - 0.5f) * pitch,
            y0 + (r0 - 0.5f) * pitch,
            x0 + (c1 + 0.5f) * pitch,
            y0 + (r1 + 0.5f) * pitch,
        )
        glowPaint.color = colour
        glowPaint.alpha = GLOW_ALPHA
        canvas.drawBitmap(map, glowSrc, glowDst, glowPaint)
        // Leave the map clean for the next frame, which may light elsewhere.
        for (r in r0..r1) for (c in c0..c1) glowBytes[r * glowRowBytes + c] = 0
    }

    /**
     * [text] in 5×7 dot digits with its top-left dot at [col], [row], a dot
     * between glyphs. Digits, `%` and `:` only; anything else is a gap.
     */
    private fun drawGlyphs(canvas: Canvas, text: String, col: Int, row: Int, paint: Paint) {
        var n = 0
        var at = col
        for (ch in text.take(MAX_GLYPHS)) {
            if (ch == ':') {
                // Two dots of its own, so a clock reads 9:42 and not 9 : 42.
                for (r in intArrayOf(2, 4)) {
                    glyphDots[n++] = x0 + at * pitch
                    glyphDots[n++] = y0 + (row + r) * pitch
                }
            } else {
                glyphOf(ch)?.let { glyph ->
                    for (r in 0 until GLYPH_H) {
                        for (c in 0 until GLYPH_W) {
                            if (glyph[r * GLYPH_W + c]) {
                                glyphDots[n++] = x0 + (at + c) * pitch
                                glyphDots[n++] = y0 + (row + r) * pitch
                            }
                        }
                    }
                }
            }
            at += glyphWidth(ch) + 1
        }
        if (n > 0) canvas.drawPoints(glyphDots, 0, n, paint)
    }

    private fun glyphOf(ch: Char): BooleanArray? = when {
        ch in '0'..'9' -> GLYPHS[ch - '0']
        ch == '%' -> GLYPHS[PERCENT]
        else -> null
    }

    private fun glyphWidth(ch: Char) = if (ch == ':') COLON_W else GLYPH_W

    /** How wide [text] is, in dots, as [drawGlyphs] lays it out. */
    private fun glyphsWidth(text: String): Int =
        text.take(MAX_GLYPHS).sumOf { glyphWidth(it) + 1 } - 1

    /** The nearest dot centre to [v] on a grid starting at [origin]. */
    private fun snap(v: Float, origin: Float) = origin + ((v - origin) / pitch).roundToInt() * pitch

    /** [v] pixels, in whole dots. */
    private fun dots(v: Float) = (v / pitch).roundToInt()

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
        const val EYE_W = 0.34f
        const val EYE_H = 0.40f
        /** Centre to centre: the eyes sit close, a small gap between them. */
        const val EYE_GAP = 0.43f
        const val FACE_Y = 0.44f
        const val GAZE_X = 0.40f
        const val GAZE_Y = 0.13f
        const val HOP = 0.06f

        /** Corner radius as a fraction of the eye's smaller side; 0.5 would be a pill. */
        const val CORNER = 0.34f

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
        const val MAX_STRETCH = 0.1f

        /** A soft haze behind the lit dots, as LEDs bleed into a diffuser: its strength, and its blur. */
        const val GLOW_ALPHA = 110
        const val GLOW_PASSES = 2

        /** How many dots the glow reaches past the last lit one: one per blur pass, and one for the filtering. */
        const val GLOW_REACH = GLOW_PASSES + 1


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
        const val HEART = 0.5f

        /** The heart curve's half-width, its reach up or down from its middle, and that middle. */
        const val HEART_HALF_W = 1.14f
        const val HEART_REACH_Y = 1.15f
        const val HEART_MID = 0.12f
        const val DIZZY_TURNS = 2.0
        const val DIZZY_X = 0.07f
        const val DIZZY_Y = 0.06f
        const val DIZZY_SIZE = 0.12f
        const val DIZZY_LID_LEFT = 0.18f
        const val DIZZY_LID_RIGHT = 0.04f
        const val DIZZY_SHAKE_AT = 0.72f
        const val Z_COUNT = 3
        const val Z_FIRST_DOTS = 3
        const val Z_MAX_DOTS = 3 * (Z_FIRST_DOTS + Z_COUNT - 1)
        const val Z_STAGGER = 0.22f
        const val Z_LIFE = 0.56f
        /** Each Z starts this far right of and above the last: wider than the biggest, so they never touch. */
        const val Z_STEP_X_DOTS = 6
        const val Z_STEP_Y_DOTS = 4
        const val Z_DRIFT_DOTS = 1f
        const val Z_RISE_DOTS = 2f
        const val Z_ALPHA = 220.0

        // The session usage readout: where its top-left dot sits, out of the eyes' way.
        const val USAGE_COL = 2
        const val USAGE_ROW = 1

        /** The night clock's top row: level with the eyes, which are closed under it. */
        const val CLOCK_ROW = 16

        /** Dot digits, five wide and seven tall, as on an LED panel. */
        const val GLYPH_W = 5
        const val GLYPH_H = 7
        const val PERCENT = 10

        /** A colon is two dots wide of nothing but itself. */
        const val COLON_W = 1

        /** The longest readout: "24:59". */
        const val MAX_GLYPHS = 6

        /** 0-9, then "%": each a row of five, top to bottom. */
        val GLYPHS: Array<BooleanArray> = arrayOf(
            ".###." + "#...#" + "#...#" + "#...#" + "#...#" + "#...#" + ".###.",
            "..#.." + ".##.." + "..#.." + "..#.." + "..#.." + "..#.." + ".###.",
            ".###." + "#...#" + "....#" + "...#." + "..#.." + ".#..." + "#####",
            "#####" + "...#." + "..#.." + "...#." + "....#" + "#...#" + ".###.",
            "...#." + "..##." + ".#.#." + "#..#." + "#####" + "...#." + "...#.",
            "#####" + "#...." + "####." + "....#" + "....#" + "#...#" + ".###.",
            "..##." + ".#..." + "#...." + "####." + "#...#" + "#...#" + ".###.",
            "#####" + "....#" + "...#." + "..#.." + ".#..." + ".#..." + ".#...",
            ".###." + "#...#" + "#...#" + ".###." + "#...#" + "#...#" + ".###.",
            ".###." + "#...#" + "#...#" + ".####" + "....#" + "...#." + ".##..",
            "##..." + "##..#" + "...#." + "..#.." + ".#..." + "#..##" + "...##",
        ).map { s -> BooleanArray(s.length) { s[it] == '#' } }.toTypedArray()

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
