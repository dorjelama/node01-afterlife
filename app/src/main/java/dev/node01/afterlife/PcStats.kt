package dev.node01.afterlife

import kotlin.math.roundToInt

/**
 * The PC's own load, as a script on it reports every few seconds: CPU,
 * memory and disk activity, each 0..100. Only these three numbers cross the
 * LAN; nothing is kept across a restart, since a stale load is no load.
 */
data class PcStats(val cpu: Int, val ram: Int, val disk: Int) {
    companion object {
        /** From the script's form: `cpu`, `ram`, `disk`, decimals allowed. Null unless all three are there. */
        fun parse(form: Map<String, String>): PcStats? {
            fun pct(name: String) = form[name]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0, 100)
            return PcStats(pct("cpu") ?: return null, pct("ram") ?: return null, pct("disk") ?: return null)
        }
    }
}
