package dev.node01.afterlife

import kotlin.math.roundToInt

/**
 * The phone's own health, read from the battery broadcast.
 *
 * NODE lives plugged in on a desk for years at a time, which is exactly how a
 * battery swells: warm and permanently full. So two things are worth saying
 * out of all the battery reports -- that it is running hot, and that it has
 * come unplugged and is draining.
 *
 * Pure: [of] turns one broadcast's numbers into this, and is unit-tested.
 */
data class Battery(
    val tempC: Int,
    /** 0..100, and 100 when the phone did not say. */
    val percent: Int,
    val plugged: Boolean,
    val hot: Boolean,
) {
    /** Unplugged and getting low: on this desk the charger is the normal state. */
    val low: Boolean get() = !plugged && percent <= LOW_PERCENT

    /** Worth the amber and a caption. */
    val wrong: Boolean get() = hot || low

    companion object {
        /** Hot at this, and not cool again until [COOL_C]: a battery sitting at the line must not flicker. */
        const val HOT_C = 45
        const val COOL_C = 42
        const val LOW_PERCENT = 20

        /**
         * From `ACTION_BATTERY_CHANGED`: [tenthsC] is EXTRA_TEMPERATURE,
         * [level] and [scale] are the charge, [plugged] is any charger at all.
         * [wasHot] is the last answer, which is what makes the hysteresis work.
         *
         * Anything the phone does not report reads as healthy, so a device that
         * keeps quiet about its battery never raises a false alarm.
         */
        fun of(tenthsC: Int, level: Int, scale: Int, plugged: Boolean, wasHot: Boolean): Battery {
            val tempC = (tenthsC / 10f).roundToInt()
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 100
            val hot = if (wasHot) tempC >= COOL_C else tempC >= HOT_C
            return Battery(tempC, percent, plugged, hot)
        }
    }
}
