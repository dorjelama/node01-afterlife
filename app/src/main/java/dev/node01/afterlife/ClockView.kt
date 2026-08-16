package dev.node01.afterlife

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Minimal analog dial: twelve hairline ticks, numerals at 12/3/6/9, no ring.
 *
 * Hour and minute hands only -- deliberately no seconds hand, so the view is
 * redrawn once per minute off the system tick and never needs a timer of its
 * own. Every Paint is allocated up front; [onDraw] allocates nothing.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var hour = 0
    private var minute = 0

    private val d = resources.displayMetrics.density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_dim, context.theme)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * d
        strokeCap = Paint.Cap.BUTT
    }

    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_secondary, context.theme)
        typeface = Typeface.MONOSPACE
        textSize = 19f * d
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }

    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_primary, context.theme)
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * d
        strokeCap = Paint.Cap.ROUND
    }

    private val minutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_primary, context.theme)
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * d
        strokeCap = Paint.Cap.ROUND
    }

    /** Open ring pivot. The hands stop short of it so nothing overlaps. */
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_primary, context.theme)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * d
    }

    private val hubRadius = 5.5f * d

    /** Where the hands begin: clear of the ring, not at dead centre. */
    private val handStart = hubRadius + 3.5f * d

    /** No-ops when the minute has not changed, so redundant ticks cost nothing. */
    fun setTime(hour: Int, minute: Int) {
        if (this.hour == hour && this.minute == minute) return
        this.hour = hour
        this.minute = minute
        invalidate()
    }

    // Square, sized to whichever axis the parent constrains more tightly.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 2f * d
        if (r <= 0f) return

        // Ticks, drawn inward from the (invisible) rim. The four quarters are
        // skipped -- the numerals already mark those positions, and a tick
        // beside a numeral reads as a stray dash.
        for (i in 0 until 12) {
            if (i % 3 == 0) continue
            val a = Math.toRadians(i * 30.0)
            val sinA = sin(a).toFloat()
            val cosA = cos(a).toFloat()
            val inner = r - 11f * d
            canvas.drawLine(
                cx + inner * sinA, cy - inner * cosA,
                cx + r * sinA, cy - r * cosA,
                tickPaint,
            )
        }

        // Numerals at the quarters, set inside the tick ring. Baseline is
        // nudged so the glyph is optically centred on its radial position.
        val fm = numeralPaint.fontMetrics
        val baseline = -(fm.ascent + fm.descent) / 2f
        val numR = r - 34f * d
        for ((i, label) in NUMERALS) {
            val a = Math.toRadians(i * 30.0)
            canvas.drawText(
                label,
                cx + numR * sin(a).toFloat(),
                cy - numR * cos(a).toFloat() + baseline,
                numeralPaint,
            )
        }

        // Hour hand: 30 deg per hour, plus the half-degree per minute of drift
        // that stops it sitting on the hour mark at :59.
        val hourA = Math.toRadians((hour % 12) * 30.0 + minute * 0.5)
        drawHand(canvas, hourA, r * 0.50f, hourPaint)

        val minuteA = Math.toRadians(minute * 6.0)
        drawHand(canvas, minuteA, r * 0.74f, minutePaint)

        canvas.drawCircle(cx, cy, hubRadius, hubPaint)
    }

    /** Draws a hand from just outside the pivot ring out to [length]. */
    private fun drawHand(canvas: Canvas, angle: Double, length: Float, paint: Paint) {
        val sinA = sin(angle).toFloat()
        val cosA = cos(angle).toFloat()
        canvas.drawLine(
            width / 2f + handStart * sinA,
            height / 2f - handStart * cosA,
            width / 2f + length * sinA,
            height / 2f - length * cosA,
            paint,
        )
    }

    private companion object {
        /** Tick index (30 deg each) to label, for the four quarters. */
        val NUMERALS = arrayOf(
            0 to "12",
            3 to "3",
            6 to "6",
            9 to "9",
        )
    }
}
