package dev.node01.afterlife

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * The weather now and the forecast for today and tomorrow: the line NODE says
 * when it first sees you in the morning, and the answer to "will it rain
 * tomorrow?".
 *
 * It sends a fixed pair of coordinates -- [LAT] and [LON] below, yours to
 * change -- and nothing else: no identifier, no account, no phone. Open-Meteo
 * needs no key. If it fails, or is slow, or there is no Wi-Fi, the briefing
 * simply goes without it.
 *
 * [parse], [days] and [describe] are pure and tested; [fetch] blocks and must
 * be called off the main thread.
 */
object Weather {

    /** Kathmandu. Change these two to wherever NODE's desk is. */
    const val LAT = 27.7172
    const val LON = 85.3240

    data class Now(val tempC: Int, val code: Int)

    /** A day's forecast: its weather, low and high, and the chance of rain at its likeliest, in percent. */
    data class Day(val code: Int, val minC: Int, val maxC: Int, val rainPct: Int)

    /** The weather now, and [days] from today on. */
    data class Forecast(val now: Now, val days: List<Day>)

    /** Blocking, and never throws: null for anything that did not work. */
    fun fetch(): Forecast? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$LAT&longitude=$LON&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_probability_max" +
                "&forecast_days=2&timezone=auto",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // A generic client name: NODE never says whose phone it is.
            setRequestProperty("User-Agent", "NODE-Companion")
        }
        try {
            if (connection.responseCode != 200) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }.take(MAX_BYTES)
            parse(json)?.let { Forecast(it, days(json)) }
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

    /** The "daily" arrays, one [Day] per day they all cover; a missing chance of rain counts as none. */
    internal fun days(json: String): List<Day> {
        fun list(key: String) = Regex(""""$key"\s*:\s*\[([^\]]*)]""").find(json)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().toDoubleOrNull() }.orEmpty()
        val codes = list("weather_code")
        val lows = list("temperature_2m_min")
        val highs = list("temperature_2m_max")
        val rain = list("precipitation_probability_max")
        return codes.indices.map { i ->
            Day(
                codes[i]?.toInt() ?: return@map null,
                lows.getOrNull(i)?.roundToInt() ?: return@map null,
                highs.getOrNull(i)?.roundToInt() ?: return@map null,
                rain.getOrNull(i)?.roundToInt() ?: 0,
            )
        }.takeWhile { it != null }.filterNotNull()
    }

    /** "18 degrees, light rain." */
    fun describe(now: Now, lang: Lang): String = if (lang == Lang.NE) {
        "${Bs.neDigits(now.tempC)} डिग्री, ${conditionNe(now.code)}"
    } else {
        "${now.tempC} degrees, ${conditionEn(now.code)}"
    }

    /**
     * The answer to a weather question: now and the rest of today, or
     * [tomorrow]'s forecast. Null when the forecast for that day did not come.
     */
    fun describe(f: Forecast, tomorrow: Boolean, lang: Lang): String? {
        val day = f.days.getOrNull(if (tomorrow) 1 else 0)
        if (day == null) return if (tomorrow) null else describe(f.now, lang) + if (lang == Lang.NE) "।" else "."
        return if (lang == Lang.NE) {
            val range = "${Bs.neDigits(day.minC)} देखि ${Bs.neDigits(day.maxC)} डिग्री, पानी पर्ने सम्भावना ${Bs.neDigits(day.rainPct)}%"
            if (tomorrow) "भोलि ${conditionNe(day.code)}, $range।" else "अहिले ${describe(f.now, lang)}। आज $range।"
        } else {
            val range = "${day.minC} to ${day.maxC} degrees, chance of rain ${day.rainPct}%"
            if (tomorrow) "Tomorrow: ${conditionEn(day.code)}, $range." else "Now ${describe(f.now, lang)}. Today $range."
        }
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
