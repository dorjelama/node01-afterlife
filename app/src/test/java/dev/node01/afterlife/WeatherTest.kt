package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {

    private val reply = """
        {"latitude":27.7,"longitude":85.3,"generationtime_ms":0.03,"utc_offset_seconds":0,
         "current_units":{"temperature_2m":"°C","weather_code":"wmo code"},
         "current":{"time":"2026-11-13T07:00","interval":900,"temperature_2m":18.4,"weather_code":61}}
    """.trimIndent()

    @Test
    fun readsTheTwoNumbersItWants() {
        assertEquals(Weather.Now(18, 61), Weather.parse(reply))
        // Below zero, and a whole number.
        assertEquals(Weather.Now(-3, 71), Weather.parse("""{"current":{"temperature_2m":-2.6,"weather_code":71}}"""))
        assertEquals(Weather.Now(20, 0), Weather.parse("""{"current":{"temperature_2m":20,"weather_code":0}}"""))
    }

    @Test
    fun anythingElseIsNoWeatherAtAll() {
        assertNull(Weather.parse(""))
        assertNull(Weather.parse("""{"error":true,"reason":"no"}"""))
        // Cut off mid-number: the second field never arrives.
        assertNull(Weather.parse("""{"current":{"temperature_2m":18.4,"weather_c"""))
    }

    @Test
    fun saysTheWeatherInBothLanguages() {
        assertEquals("18 degrees, rain", Weather.describe(Weather.Now(18, 61), Lang.EN))
        assertEquals("22 degrees, clear", Weather.describe(Weather.Now(22, 0), Lang.EN))
        assertEquals("5 degrees, thunderstorms", Weather.describe(Weather.Now(5, 95), Lang.EN))
        assertEquals("१८ डिग्री, पानी परेको", Weather.describe(Weather.Now(18, 61), Lang.NE))
    }
}
