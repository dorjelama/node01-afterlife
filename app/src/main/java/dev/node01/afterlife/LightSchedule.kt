package dev.node01.afterlife

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * When the tank's light usually comes on and how long it stays on, learnt from
 * the last [KEEP_DAYS] days the rear camera saw it, so a light that did not come
 * on, or will not go off -- a timer or a bulb failing -- is noticed.
 *
 * A day counts once its light was seen coming on: NODE started while it was
 * already on does not know when that was, and says nothing about it. Immutable;
 * the caller persists [encode]. Pure, so it is unit-tested.
 */
data class LightSchedule(val days: List<Day> = emptyList()) {

    /** One day: when the light came on and went off, in minutes after midnight; [offMin] null while it is on. */
    data class Day(val date: LocalDate, val onMin: Int, val offMin: Int? = null)

    /** The light seen coming on at [at]: a new day, the last [KEEP_DAYS] kept. A second on the same day replaces it. */
    fun on(at: LocalDateTime): LightSchedule =
        copy(days = (days.filter { it.date != at.toLocalDate() } + Day(at.toLocalDate(), minute(at))).takeLast(KEEP_DAYS))

    /** The light seen going off at [at], closing the day it came on. */
    fun off(at: LocalDateTime): LightSchedule {
        val last = days.lastOrNull()?.takeIf { it.date == at.toLocalDate() && it.offMin == null } ?: return this
        return copy(days = days.dropLast(1) + last.copy(offMin = minute(at)))
    }

    /** The usual time it comes on, in minutes after midnight, or null with too few days to say. */
    fun usualOn(): Int? = days.map { it.onMin }.takeIf { it.size >= MIN_DAYS }?.let(::median)

    /** How long it usually stays on, in minutes, or null with too few whole days to say. */
    fun usualMinutes(): Int? = days.mapNotNull { d -> d.offMin?.let { it - d.onMin }?.takeIf { it > 0 } }
        .takeIf { it.size >= MIN_DAYS }?.let(::median)

    /** The light has not come on today, and it is an hour past when it usually does. */
    fun late(now: LocalDateTime): Boolean {
        val usual = usualOn() ?: return false
        return days.none { it.date == now.toLocalDate() } && minute(now) >= usual + LATE_MIN
    }

    /** On today and still on, longer than usual by a wide margin, or than any tank needs. */
    fun tooLong(now: LocalDateTime): Boolean {
        val today = days.lastOrNull()?.takeIf { it.date == now.toLocalDate() && it.offMin == null } ?: return false
        val lit = minute(now) - today.onMin
        val usual = usualMinutes()
        return lit >= MAX_MIN || (usual != null && lit >= usual + OVER_MIN)
    }

    /** One day a line: `2026-11-26 450 1260`, or without the off time while it is on. */
    fun encode(): String = days.joinToString("\n") { listOfNotNull(it.date, it.onMin, it.offMin).joinToString(" ") }

    companion object {
        const val KEEP_DAYS = 14
        private const val MIN_DAYS = 3
        private const val LATE_MIN = 60
        private const val OVER_MIN = 120
        /** No planted tank wants its light on longer than this, whatever it usually does. */
        private const val MAX_MIN = 14 * 60

        fun decode(stored: String?): LightSchedule = LightSchedule(
            stored.orEmpty().lines().mapNotNull { line ->
                val p = line.trim().split(' ')
                val date = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return@mapNotNull null
                val on = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                Day(date, on, p.getOrNull(2)?.toIntOrNull())
            },
        )

        private fun minute(at: LocalDateTime) = at.hour * 60 + at.minute
        private fun median(values: List<Int>) = values.sorted()[values.size / 2]
    }
}
