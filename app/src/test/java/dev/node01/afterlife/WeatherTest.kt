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
    fun readsTheDailyForecast() {
        val reply = """{"current_units":{"temperature_2m":"°C","weather_code":"wmo code"},""" +
            """"current":{"temperature_2m":18.4,"weather_code":2},"daily_units":{"temperature_2m_max":"°C"},""" +
            """"daily":{"time":["2026-11-22","2026-11-23"],"weather_code":[2,61],"temperature_2m_min":[7.6,9.1],""" +
            """"temperature_2m_max":[21.5,19.4],"precipitation_probability_max":[10,null]}}"""
        assertEquals(Weather.Now(18, 2), Weather.parse(reply))
        assertEquals(listOf(Weather.Day(2, 8, 22, 10), Weather.Day(61, 9, 19, 0)), Weather.days(reply))
        assertEquals(emptyList<Weather.Day>(), Weather.days("""{"current":{"temperature_2m":18}}"""))
    }

    @Test
    fun answersAWeatherQuestion() {
        val f = Weather.Forecast(Weather.Now(18, 2), listOf(Weather.Day(2, 8, 22, 10), Weather.Day(61, 9, 19, 80)))
        assertEquals(
            "Now 18 degrees, partly cloudy. Today 8 to 22 degrees, chance of rain 10%.",
            Weather.describe(f, tomorrow = false, Lang.EN),
        )
        assertEquals("Tomorrow: rain, 9 to 19 degrees, chance of rain 80%.", Weather.describe(f, tomorrow = true, Lang.EN))
        assertEquals("भोलि पानी परेको, ९ देखि १९ डिग्री, पानी पर्ने सम्भावना ८०%।", Weather.describe(f, tomorrow = true, Lang.NE))
        // Only the weather now came: today is that, and tomorrow is nothing.
        val now = Weather.Forecast(Weather.Now(18, 2), emptyList())
        assertEquals("18 degrees, partly cloudy.", Weather.describe(now, tomorrow = false, Lang.EN))
        assertNull(Weather.describe(now, tomorrow = true, Lang.EN))
    }

    @Test
    fun saysTheWeatherInBothLanguages() {
        assertEquals("18 degrees, rain", Weather.describe(Weather.Now(18, 61), Lang.EN))
        assertEquals("22 degrees, clear", Weather.describe(Weather.Now(22, 0), Lang.EN))
        assertEquals("5 degrees, thunderstorms", Weather.describe(Weather.Now(5, 95), Lang.EN))
        assertEquals("१८ डिग्री, पानी परेको", Weather.describe(Weather.Now(18, 61), Lang.NE))
    }
}
