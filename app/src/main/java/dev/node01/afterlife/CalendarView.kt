package dev.node01.afterlife

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

/**
 * Current month as a 7-column grid, Monday first, today picked out in accent.
 *
 * Drawn rather than built from ~42 TextViews: one view, no inflation cost, and
 * exact control over the monospace grid. Redrawn on the same minute tick as
 * everything else; the content only actually changes at midnight, and
 * [setDate] no-ops until then.
 *
 * The whole block is measured then centred vertically, so a short month does
 * not leave the grid hanging with dead space beneath it.
 */
class CalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var today: LocalDate = LocalDate.now()
    private var month: YearMonth = YearMonth.from(today)

    private val d = resources.displayMetrics.density

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_secondary, context.theme)
        typeface = Typeface.MONOSPACE
        textSize = 18f * d
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.20f
    }

    private val dowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_dim, context.theme)
        typeface = Typeface.MONOSPACE
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
    }

    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_secondary, context.theme)
        typeface = Typeface.MONOSPACE
        textSize = 15f * d
        textAlign = Paint.Align.CENTER
    }

    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_accent, context.theme)
        typeface = Typeface.MONOSPACE
        textSize = 15f * d
        textAlign = Paint.Align.CENTER
    }

    private val todayBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.node_accent, context.theme)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
    }

    // Reused across draws so onDraw allocates nothing.
    private val todayBox = RectF()

    fun setDate(date: LocalDate) {
        if (date == today) return
        today = date
        month = YearMonth.from(date)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bs = Bs.fromAd(today)
        if (bs != null) {
            drawGrid(
                canvas,
                title = "${Bs.MONTH_NAMES_NE[bs.month - 1]}  ${Bs.neDigits(bs.year)}",
                length = Bs.daysInMonth(bs.year, bs.month),
                // Nepali weeks start on Sunday. DayOfWeek.value is
                // Monday=1..Sunday=7, so Sunday (7) maps to column 0.
                offset = Bs.toAd(bs.year, bs.month, 1)!!.dayOfWeek.value % 7,
                todayDay = bs.day,
                dow = Bs.WEEKDAYS_NE,
                devanagari = true,
            )
        } else {
            // Outside the BS table's range: fall back to Gregorian rather than
            // draw an empty pane.
            drawGrid(
                canvas,
                title = month.month.getDisplayName(TextStyle.SHORT, Locale.US)
                    .uppercase(Locale.US) + "  " + month.year,
                length = month.lengthOfMonth(),
                offset = month.atDay(1).dayOfWeek.value % 7,
                todayDay = today.dayOfMonth,
                dow = DOW_LATIN,
                devanagari = false,
            )
        }
    }

    private fun drawGrid(
        canvas: Canvas,
        title: String,
        length: Int,
        offset: Int,
        todayDay: Int,
        dow: Array<String>,
        devanagari: Boolean,
    ) {
        val rows = (offset + length + 6) / 7

        // Cap the cell width so the grid stays tight on a wide landscape pane
        // instead of stretching into something sparse, then centre the block.
        val cell = min(width / 7f, 46f * d)
        val gridW = cell * 7f
        val left = (width - gridW) / 2f
        val midX = left + gridW / 2f

        val rowH = 27f * d
        val titleToDow = 30f * d
        val dowToGrid = 26f * d

        // Measure the block, then centre it so a 5-row month is not top-heavy.
        val blockH = titleToDow + dowToGrid + rows * rowH
        val top = ((height - blockH) / 2f).coerceAtLeast(0f)

        val titleY = top
        val dowY = titleY + titleToDow
        val firstRowY = dowY + dowToGrid

        // Devanagari is a connected script: wide letter-spacing pulls matras
        // away from their base glyphs, so it is eased off for that path.
        titlePaint.letterSpacing = if (devanagari) 0.08f else 0.20f

        // Centred on the grid, not left-aligned: letterSpacing pushes a
        // left-aligned title visibly off the grid's optical centre.
        canvas.drawText(title, midX, titleY, titlePaint)

        for (c in 0 until 7) {
            canvas.drawText(dow[c], left + cell * (c + 0.5f), dowY, dowPaint)
        }

        for (day in 1..length) {
            val idx = offset + day - 1
            val x = left + cell * (idx % 7 + 0.5f)
            val y = firstRowY + (idx / 7) * rowH

            val isToday = day == todayDay
            if (isToday) {
                // Outline rather than brackets: brackets would break the
                // monospace column alignment of the grid.
                val bh = 21f * d
                todayBox.set(
                    x - cell * 0.40f, y - bh * 0.74f,
                    x + cell * 0.40f, y + bh * 0.30f,
                )
                canvas.drawRoundRect(todayBox, 2f * d, 2f * d, todayBoxPaint)
            }
            canvas.drawText(
                if (devanagari) Bs.neDigits(day) else day.toString(), x, y,
                if (isToday) todayPaint else dayPaint,
            )
        }
    }

    private companion object {
        /** Sunday-first, for the Gregorian fallback path only. */
        val DOW_LATIN = arrayOf("S", "M", "T", "W", "T", "F", "S")
    }
}
