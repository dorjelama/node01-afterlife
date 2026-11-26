package dev.node01.afterlife

/**
 * The water since the last water change, as the rear camera sees it: its
 * green and its clarity over the first [BASELINE_MS] after the change are the
 * clean water, and from then on how far it has drifted. Each change also keeps
 * how fast the water greened over the cycle it ends, so the tank's own usual
 * pace is learnt, and water turning [faster] than that -- or, before there is
 * any, past fixed limits -- is worth a water change sooner.
 *
 * It sees colour and haze, not ammonia or nitrate. Immutable; the caller
 * persists [encode]. Pure, so it is unit-tested.
 */
data class WaterWatch(
    val changedMs: Long? = null,
    /** The clean water's averages, over [baseLooks] lit looks in the first day after the change. */
    val baseGreen: Double = 0.0,
    val baseClarity: Double = 0.0,
    val baseLooks: Int = 0,
    /** The latest lit look: what a cycle ended on, at the next change. */
    val lastGreen: Double? = null,
    /** Past cycles' greening, in green share a day, newest last. */
    val rates: List<Double> = emptyList(),
) {
    /** A water change at [nowMs]: the cycle it ends is learnt from, and a new clean water begins. */
    fun changed(nowMs: Long): WaterWatch {
        val since = changedMs
        val days = since?.let { (nowMs - it).toDouble() / Tank.DAY_MS }
        val rate = if (days != null && days >= 1 && baseLooks >= MIN_LOOKS && lastGreen != null) (lastGreen - baseGreen) / days else null
        return WaterWatch(changedMs = nowMs, rates = (rates + listOfNotNull(rate)).takeLast(KEEP_CYCLES))
    }

    /** A lit look at [nowMs]: in the first day after a change it is part of the clean water; always the latest. */
    fun look(green: Double, clarity: Double, nowMs: Long): WaterWatch {
        val since = changedMs ?: return copy(lastGreen = green)
        if (nowMs - since > BASELINE_MS) return copy(lastGreen = green)
        val n = baseLooks + 1
        return copy(
            baseGreen = baseGreen + (green - baseGreen) / n,
            baseClarity = baseClarity + (clarity - baseClarity) / n,
            baseLooks = n,
            lastGreen = green,
        )
    }

    /**
     * Whether the water at [green] and [clarity] has turned faster than usual since the change: greener than the
     * usual pace allows for its days by a clear margin, or hazier by [CLARITY_DROP]. Before the tank's pace is
     * known, past [GREEN_RISE].
     */
    fun faster(green: Double, clarity: Double, nowMs: Long): Boolean {
        val since = changedMs ?: return false
        if (baseLooks < MIN_LOOKS || nowMs - since <= BASELINE_MS) return false
        val rise = green - baseGreen
        val hazier = baseClarity > 0 && clarity < baseClarity * (1 - CLARITY_DROP)
        val usual = rates.takeIf { it.size >= MIN_CYCLES }?.sorted()?.let { it[it.size / 2] }
        val allowed = if (usual != null && usual > 0) {
            usual * (nowMs - since) / Tank.DAY_MS * FASTER_TIMES + MARGIN
        } else {
            GREEN_RISE
        }
        return rise > allowed || hazier
    }

    /** One line: `changed baseGreen baseClarity baseLooks lastGreen rate,rate,...`. */
    fun encode(): String = listOf(changedMs ?: "-", baseGreen, baseClarity, baseLooks, lastGreen ?: "-", rates.joinToString(","))
        .joinToString(" ")

    companion object {
        const val BASELINE_MS = Tank.DAY_MS
        private const val MIN_LOOKS = 6
        private const val MIN_CYCLES = 2
        private const val KEEP_CYCLES = 8
        // shortcut: limits reasoned, not yet seen on the tank; tune them from tank-stats.log.
        private const val GREEN_RISE = 0.03
        private const val CLARITY_DROP = 0.25
        private const val FASTER_TIMES = 1.5
        private const val MARGIN = 0.01

        fun decode(stored: String?): WaterWatch {
            val p = stored?.trim()?.split(' ') ?: return WaterWatch()
            if (p.size < 5) return WaterWatch()
            return WaterWatch(
                changedMs = p[0].toLongOrNull(),
                baseGreen = p[1].toDoubleOrNull() ?: 0.0,
                baseClarity = p[2].toDoubleOrNull() ?: 0.0,
                baseLooks = p[3].toIntOrNull() ?: 0,
                lastGreen = p[4].toDoubleOrNull(),
                rates = p.getOrNull(5).orEmpty().split(',').mapNotNull { it.toDoubleOrNull() },
            )
        }
    }
}
