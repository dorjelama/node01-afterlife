package dev.node01.afterlife

import java.net.HttpURLConnection
import java.net.URL

/**
 * This morning's weather, for the one line NODE says when it first sees you.
 *
 * The only thing in NODE that talks to the internet. It sends a fixed pair of
 * coordinates -- [LAT] and [LON] below, yours to change -- and nothing else:
 * no identifier, no account, no phone. Open-Meteo needs no key. If it fails,
 * or is slow, or there is no Wi-Fi, the briefing simply goes without it.
 *
 * [parse] and [describe] are pure and tested; [fetch] blocks and must be
 * called off the main thread.
 */
object Weather {

    /** Kathmandu. Change these two to wherever NODE's desk is. */
    const val LAT = 27.7172
    const val LON = 85.3240

    data class Now(val tempC: Int, val code: Int)

    /** Blocking, and never throws: null for anything that did not work. */
    fun fetch(): Now? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$LAT&longitude=$LON&current=temperature_2m,weather_code",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // A generic client name: NODE never says whose phone it is.
            setRequestProperty("User-Agent", "NODE-Companion")
        }
        try {
            if (connection.responseCode != 200) return null
            parse(connection.inputStream.bufferedReader().use { it.readText() }.take(MAX_BYTES))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * The two numbers, out of a small flat reply. Read with a regex rather
     * than a parser: they sit inside a "current" object, and [JsonFields]
     * deliberately looks no deeper than the top level.
     */
    internal fun parse(json: String): Now? {
        val temp = Regex(""""temperature_2m"\s*:\s*(-?\d+(?:\.\d+)?)""").find(json)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val code = Regex(""""weather_code"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return Now(Math.round(temp).toInt(), code)
    }

    /** "18 degrees, light rain." */
    fun describe(now: Now, lang: Lang): String = if (lang == Lang.NE) {
        "${Bs.neDigits(now.tempC)} डिग्री, ${conditionNe(now.code)}"
    } else {
        "${now.tempC} degrees, ${conditionEn(now.code)}"
    }

    /** WMO weather codes, in the handful of bands worth telling apart out loud. */
    private fun conditionEn(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 80..82 -> "showers"
        in 95..99 -> "thunderstorms"
        else -> "cloudy"
    }

    private fun conditionNe(code: Int): String = when (code) {
        0 -> "सफा"
        1, 2 -> "केही बादल"
        3 -> "बादल लागेको"
        45, 48 -> "कुहिरो"
        in 51..57 -> "सिमसिम पानी"
        in 61..67 -> "पानी परेको"
        in 71..77, 85, 86 -> "हिउँ"
        in 80..82 -> "झरी"
        in 95..99 -> "चट्याङसहित वर्षा"
        else -> "बादल"
    }

    /** Short: the briefing waits on this, and a morning hello must not hang. */
    private const val TIMEOUT_MS = 3_000

    private const val MAX_BYTES = 4_096
}
