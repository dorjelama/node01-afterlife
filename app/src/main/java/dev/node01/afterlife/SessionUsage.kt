package dev.node01.afterlife

import kotlin.math.roundToInt

/**
 * How much of Claude's 5-hour session limit is used, as Claude Code's status
 * line reports it (`rate_limits.five_hour`). The PC sends only this number and
 * when the window resets; nothing else about the session reaches the phone.
 */
data class SessionUsage(
    /** 0..100. */
    val percent: Int,
    /** Wall-clock time the 5-hour window resets, or null if not said. */
    val resetsAtMs: Long?,
) {
    /** What to show at wall-clock [nowMs]: once the window has reset, nothing of it is used. */
    fun shownAt(nowMs: Long): Int = if (resetsAtMs != null && nowMs >= resetsAtMs) 0 else percent

    /** For saving across restarts: `percent,resetsAtMs`. */
    fun encode(): String = "$percent,${resetsAtMs ?: ""}"

    companion object {
        /**
         * From the status line's form: `pct` (0..100, may have decimals) and
         * `resets` (Unix seconds). Null if there is no usable percentage.
         */
        fun parse(form: Map<String, String>): SessionUsage? {
            val pct = form["pct"]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            val resets = form["resets"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1_000L)
            return SessionUsage(pct.roundToInt().coerceIn(0, 100), resets)
        }

        fun decode(saved: String?): SessionUsage? {
            val parts = saved?.split(',') ?: return null
            val pct = parts.getOrNull(0)?.toIntOrNull() ?: return null
            return SessionUsage(pct.coerceIn(0, 100), parts.getOrNull(1)?.toLongOrNull())
        }
    }
}
