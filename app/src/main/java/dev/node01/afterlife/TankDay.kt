package dev.node01.afterlife

import java.time.LocalDate
import java.util.Locale

/**
 * One day of the tank, from the minutes the rear camera watched it lit: how
 * long it was lit, how much moved, how many things moved at once, how much of
 * the picture was plant, the water's green and clarity, and the feeds. Medians,
 * so a hand at the glass does not make a day; for moving things the 90th
 * percentile, the most seen together on a good look. One row of `days.csv`.
 *
 * Pure, so it is unit-tested.
 */
data class TankDay(
    val date: LocalDate,
    val litMinutes: Int,
    val activity: Double,
    val movers: Int,
    val plants: Double,
    val green: Double,
    val clarity: Double,
    val feeds: Int,
) {
    fun csv(): String = listOf(
        date, litMinutes, "%.2f".format(Locale.ROOT, activity), movers, "%.3f".format(Locale.ROOT, plants),
        "%.3f".format(Locale.ROOT, green), "%.1f".format(Locale.ROOT, clarity), feeds,
    ).joinToString(",")

    /** The day in words, for the page and the model's notes, against the [usual] days before it. */
    fun describe(usual: TankDay?): String {
        val hours = litMinutes / 60.0
        val parts = mutableListOf("lit %.1f h".format(Locale.ROOT, hours))
        parts += "activity %.1f".format(Locale.ROOT, activity) + (usual?.let { " (usual %.1f)".format(Locale.ROOT, it.activity) } ?: "")
        parts += "up to $movers moving at once"
        parts += "plants %d%% of the picture".format(Locale.ROOT, Math.round(plants * 100)) +
            (usual?.let { " (usual %d%%)".format(Locale.ROOT, Math.round(it.plants * 100)) } ?: "")
        parts += "water green %.3f, clarity %.0f".format(Locale.ROOT, green, clarity)
        parts += if (feeds == 1) "fed once" else "fed $feeds times"
        return "$date: " + parts.joinToString(", ")
    }

    companion object {
        const val HEADER = "date,lit_minutes,activity,moving_at_once,plants,green,clarity,feeds"

        /** [date] from its minutes' numbers and its [feeds]; null if the tank was never seen lit. */
        fun of(date: LocalDate, minutes: List<TankLook.Stats>, feeds: Int): TankDay? {
            val lit = minutes.filter { it.lightsOn }.takeIf { it.isNotEmpty() } ?: return null
            return TankDay(
                date, lit.size, median(lit.map { it.motion }), percentile(lit.map { it.movers }, 0.9),
                median(lit.map { it.plants }), median(lit.map { it.green }), median(lit.map { it.clarity }), feeds,
            )
        }

        /** A `days.csv` row back; null for the header or anything else. */
        fun decode(row: String): TankDay? {
            val p = row.split(',')
            if (p.size != 8) return null
            return TankDay(
                runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return null,
                p[1].toIntOrNull() ?: return null, p[2].toDoubleOrNull() ?: return null, p[3].toIntOrNull() ?: return null,
                p[4].toDoubleOrNull() ?: return null, p[5].toDoubleOrNull() ?: return null, p[6].toDoubleOrNull() ?: return null,
                p[7].toIntOrNull() ?: return null,
            )
        }

        /** The usual day of [days]: each number's median, or null with fewer than [MIN_DAYS]. */
        fun usual(days: List<TankDay>): TankDay? {
            if (days.size < MIN_DAYS) return null
            return TankDay(
                days.last().date, median(days.map { it.litMinutes.toDouble() }).toInt(), median(days.map { it.activity }),
                percentile(days.map { it.movers }, 0.5), median(days.map { it.plants }), median(days.map { it.green }),
                median(days.map { it.clarity }), median(days.map { it.feeds.toDouble() }).toInt(),
            )
        }

        /** [rows] of `days.csv` with [day]'s row put in, replacing any it had, oldest first; the header first. */
        fun upsert(rows: List<String>, day: TankDay): List<String> =
            listOf(HEADER) + (rows.mapNotNull(::decode).filter { it.date != day.date } + day).sortedBy { it.date }.map { it.csv() }

        private const val MIN_DAYS = 3
        private fun median(v: List<Double>) = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        private fun percentile(v: List<Int>, p: Double) = v.sorted()[((v.size - 1) * p).toInt()]
    }
}
