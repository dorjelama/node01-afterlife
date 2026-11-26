package dev.node01.afterlife

import java.util.Locale
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

    /**
     * One look: mean luma 0..255, the green share of the colour 0..1, the mean change between frames, and the
     * spread of luma -- the picture's contrast, which cloudy water flattens.
     */
    data class Stats(
        val brightness: Int,
        val green: Double,
        val motion: Double,
        val clarity: Double = 0.0,
        /** The share of the picture that is plant-green, 0..1. */
        val plants: Double = 0.0,
        /** The most separate moving things in any one moment: at most the fish seen together, not a count of them. */
        val movers: Int = 0,
    ) {
        val lightsOn: Boolean get() = brightness >= LIGHTS_ON

        /** For the week-long stats log and minutes.csv: `118 0.412 3.07 41.2 0.310 3`. */
        fun encode() = listOf(
            brightness, "%.3f".format(Locale.ROOT, green), "%.2f".format(Locale.ROOT, motion),
            "%.1f".format(Locale.ROOT, clarity), "%.3f".format(Locale.ROOT, plants), movers,
        ).joinToString(" ")
    }

    /** The share of a picture that is plant-green: green clearly over both red and blue. */
    fun plants(argb: IntArray): Double {
        if (argb.isEmpty()) return 0.0
        return argb.count { p ->
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            g > r + PLANT_MARGIN && g > b + PLANT_MARGIN
        }.toDouble() / argb.size
    }

    /**
     * Separate moving things between two frames ([w] wide): the pixels that changed by [MOVED], joined where they
     * touch, counting blobs of [MIN_BLOB] or more. Two fish side by side are one; a fish behind a plant, none. So:
     * at most the fish seen moving together. Over a burst, the 75th percentile of its frame pairs, not the most:
     * a minute's 300 pairs always hold a flicker or an exposure step that lights up blobs everywhere.
     */
    fun movers(frames: List<ByteArray>, w: Int): Int {
        val counts = frames.zipWithNext { a, b ->
            val moved = BooleanArray(a.size) { abs((a[it].toInt() and 0xff) - (b[it].toInt() and 0xff)) >= MOVED }
            blobs(moved, w)
        }.sorted()
        return if (counts.isEmpty()) 0 else counts[(counts.size - 1) * 3 / 4]
    }

    /** Blobs of [MIN_BLOB] or more set cells, joined across their four sides. */
    private fun blobs(set: BooleanArray, w: Int): Int {
        val seen = BooleanArray(set.size)
        var count = 0
        val stack = ArrayDeque<Int>()
        for (start in set.indices) {
            if (!set[start] || seen[start]) continue
            var size = 0
            seen[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                size++
                val x = i % w
                for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                    if (n in set.indices && set[n] && !seen[n]) {
                        seen[n] = true
                        stack.addLast(n)
                    }
                }
            }
            if (size >= MIN_BLOB) count++
        }
        return count
    }

    /** The spread of a grey frame's luma, its standard deviation: higher for a crisp picture, lower through haze. */
    fun clarity(luma: ByteArray): Double {
        if (luma.isEmpty()) return 0.0
        val mean = luma.sumOf { it.toInt() and 0xff }.toDouble() / luma.size
        return kotlin.math.sqrt(luma.sumOf { ((it.toInt() and 0xff) - mean).let { d -> d * d } } / luma.size)
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

    /** A stats log line back, after its date; older, shorter lines too: null if it is not one. */
    fun decode(line: String): Stats? {
        val parts = line.trim().split(' ', ',')
        if (parts.size !in 3..6) return null
        return Stats(
            parts[0].toIntOrNull() ?: return null,
            parts[1].toDoubleOrNull() ?: return null,
            parts[2].toDoubleOrNull() ?: return null,
            parts.getOrNull(3)?.let { it.toDoubleOrNull() ?: return null } ?: 0.0,
            parts.getOrNull(4)?.let { it.toDoubleOrNull() ?: return null } ?: 0.0,
            parts.getOrNull(5)?.let { it.toIntOrNull() ?: return null } ?: 0,
        )
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
    private const val PLANT_MARGIN = 12
    private const val MOVED = 25
    private const val MIN_BLOB = 2

    /** Mean luma at or above which the tank's light counts as on. */
    const val LIGHTS_ON = 60

    /** A day of looks with the light on before anything is compared. */
    const val MIN_PAST = 36
    private const val GREENER_BY = 0.03
    private const val STILL_FRACTION = 0.25
}
