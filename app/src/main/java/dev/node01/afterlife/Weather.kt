package dev.node01.afterlife

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fixed station location.
 *
 * NODE is a desk appliance -- it does not move. Baking the coordinates in costs
 * no location permission, no GPS wakeup, and never drifts. Set these once.
 */
object Station {
    // Kathmandu, Nepal. Open-Meteo's grid is roughly 10km, so city-level
    // coordinates give the same reading as a precise address -- set your own
    // here. While both are 0.0 the fetch is skipped and the readout stays "--".
    const val LATITUDE = 27.7017
    const val LONGITUDE = 85.3206

    val configured: Boolean
        get() = LATITUDE != 0.0 || LONGITUDE != 0.0
}

/**
 * Current temperature from Open-Meteo. No API key, no signup, no third-party
 * library -- HttpURLConnection and org.json are both in the platform.
 *
 * One request per hour, started from the existing minute tick rather than a
 * timer, service or WorkManager, so NODE still owns no scheduling of its own.
 */
class WeatherClient {

    @Volatile
    private var inFlight = false

    /**
     * Fetches on a throwaway background thread. [onResult] is invoked with the
     * temperature in Celsius, or null on any failure, on that same background
     * thread -- callers are responsible for getting back to the main thread.
     */
    fun fetch(onResult: (Double?) -> Unit) {
        if (inFlight || !Station.configured) return
        inFlight = true

        Thread({
            var result: Double? = null
            var conn: HttpURLConnection? = null
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=${Station.LATITUDE}" +
                        "&longitude=${Station.LONGITUDE}" +
                        "&current=temperature_2m",
                )
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    result = JSONObject(body)
                        .getJSONObject("current")
                        .getDouble("temperature_2m")
                } else {
                    Log.w(TAG, "weather http ${conn.responseCode}")
                }
            } catch (e: Exception) {
                // Offline, DNS failure, malformed payload -- all identical from
                // NODE's point of view: no fresh reading, keep showing stale.
                Log.w(TAG, "weather fetch failed: ${e.javaClass.simpleName}")
            } finally {
                conn?.disconnect()
                inFlight = false
            }
            onResult(result)
        }, "node-weather").start()
    }

    private companion object {
        const val TAG = "NODE"
    }
}
