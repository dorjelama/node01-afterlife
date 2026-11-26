package dev.node01.afterlife

import kotlin.math.abs

/**
 * What one look through the rear camera says about the aquarium: whether its
 * light is on, how green the picture is, and how much moved during a few
 * seconds -- the fish, mostly. Each means little alone; against the last week
 * of looks ([greener], [still]) it can say the water is turning green or the
 * fish are unusually quiet. It cannot see ammonia, nitrate or temperature.
 *
 * Pure, so it is unit-tested on made-up frames.
 */
object TankLook {

    /** One look: mean luma 0..255, the green share of the colour 0..1, and the mean change between frames. */
    data class Stats(val brightness: Int, val green: Double, val motion: Double) {
        val lightsOn: Boolean get() = brightness >= LIGHTS_ON

        /** For the week-long stats log: `118 0.412 3.07`. */
        fun encode() = "$brightness ${"%.3f".format(java.util.Locale.ROOT, green)} ${"%.2f".format(java.util.Locale.ROOT, motion)}"
    }

    /** Mean luma of a grey frame, 0..255. */
    fun brightness(luma: ByteArray): Int = if (luma.isEmpty()) 0 else (luma.sumOf { it.toInt() and 0xff } / luma.size)

    /** The green share of a picture's colour, `G / (R + G + B)`: a third for grey, more as the water greens. */
    fun green(argb: IntArray): Double {
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in argb) {
            r += p shr 16 and 0xff
            g += p shr 8 and 0xff
            b += p and 0xff
        }
        val sum = r + g + b
        return if (sum == 0L) 1.0 / 3 else g.toDouble() / sum
    }

    /** The mean change in luma from one frame to the next, over a burst of same-sized grey frames. */
    fun motion(frames: List<ByteArray>): Double {
        if (frames.size < 2) return 0.0
        return frames.zipWithNext { a, b ->
            var sum = 0L
            for (i in a.indices) sum += abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
            sum.toDouble() / a.size
        }.average()
    }

    /** Greener than the week's lit looks by a clear margin; false until there are enough of them to say. */
    fun greener(now: Stats, past: List<Stats>): Boolean {
        val lit = past.filter { it.lightsOn }
        return now.lightsOn && lit.size >= MIN_PAST && now.green > median(lit.map { it.green }) + GREENER_BY
    }

    /** Far less movement than usual with the light on: worth a look at the fish. */
    fun still(now: Stats, past: List<Stats>): Boolean {
        val lit = past.filter { it.lightsOn }
        return now.lightsOn && lit.size >= MIN_PAST && now.motion < median(lit.map { it.motion }) * STILL_FRACTION
    }

    /** A line for the model's notes. */
    fun describe(now: Stats, greener: Boolean, still: Boolean): String = if (!now.lightsOn) {
        "The rear camera's last look: the tank's light is off."
    } else {
        "The rear camera's last look: the light is on, " +
            (if (still) "the fish much stiller than usual, " else "the fish moving as usual, ") +
            (if (greener) "and the water greener than last week." else "and the water no greener than last week.")
    }

    /** A stats log line back, after its date: null if it is not one. */
    fun decode(line: String): Stats? {
        val parts = line.trim().split(' ')
        if (parts.size != 3) return null
        return Stats(parts[0].toIntOrNull() ?: return null, parts[1].toDoubleOrNull() ?: return null, parts[2].toDoubleOrNull() ?: return null)
    }

    private fun median(values: List<Double>): Double = values.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * A feeding, spotted in the minutes' movement: a minute moving at least [BURST_TIMES] the last hour's usual,
     * and [BURST_OVER] more, once [BASELINE_MINUTES] of it are known. A hand at the glass looks the same, so what it
     * finds is "seen on video", not certain. Then quiet for [QUIET_MINUTES]: a frenzy lasts a few minutes.
     * One minute at a time, on the main thread.
     */
    class FeedSpotter {
        private val recent = ArrayDeque<Double>()
        private var quietFor = 0

        /** The minute's [motion]; true if a feeding starts in it. */
        fun minute(motion: Double): Boolean {
            val usual = if (recent.size >= BASELINE_MINUTES) median(recent.toList()) else null
            if (quietFor > 0) quietFor--
            val burst = usual != null && quietFor == 0 && motion >= usual * BURST_TIMES && motion >= usual + BURST_OVER
            if (burst) {
                quietFor = QUIET_MINUTES
            } else {
                // A burst is no part of the usual it is measured against.
                recent.addLast(motion)
                if (recent.size > HOUR) recent.removeFirst()
            }
            return burst
        }

        private companion object {
            const val HOUR = 60
            const val BASELINE_MINUTES = 20
            const val BURST_TIMES = 2.0
            const val BURST_OVER = 2.0
            const val QUIET_MINUTES = 30
        }
    }

    // shortcut: thresholds picked by reasoning, not yet from real looks at the tank; tune them from the stats log.
    /** Mean luma at or above which the tank's light counts as on. */
    const val LIGHTS_ON = 60

    /** A day of looks with the light on before anything is compared. */
    const val MIN_PAST = 36
    private const val GREENER_BY = 0.03
    private const val STILL_FRACTION = 0.25
}
