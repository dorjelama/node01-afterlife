package dev.node01.afterlife

import dev.node01.afterlife.TankLook.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TankDayTest {

    private val nov26 = LocalDate.of(2026, 11, 26)

    @Test
    fun aDayIsItsLitMinutesMedians() {
        val minutes = List(10) { Stats(100, 0.37, 5.0, 80.0, 0.30, if (it < 9) 2 else 7) } +
            Stats(150, 0.9, 60.0, 10.0, 0.9, 9) + // a hand at the glass
            List(5) { Stats(20, 0.33, 0.5, 5.0, 0.0, 0) } // the light off
        val day = TankDay.of(nov26, minutes, feeds = 2)!!
        assertEquals(11, day.litMinutes)
        assertEquals(5.0, day.activity, 1e-9)
        assertEquals("the 90th percentile, not the one wild minute", 7, day.movers)
        assertEquals(0.30, day.plants, 1e-9)
        assertEquals(80.0, day.clarity, 1e-9)
        assertNull("never lit", TankDay.of(nov26, List(5) { Stats(20, 0.3, 0.0) }, 0))
    }

    @Test
    fun keptInDaysCsv() {
        val day = TankDay(nov26, 720, 5.0, 3, 0.31, 0.372, 78.4, 2)
        assertEquals("2026-11-26,720,5.00,3,0.310,0.372,78.4,2", day.csv())
        assertEquals(day, TankDay.decode(day.csv()))
        assertNull(TankDay.decode(TankDay.HEADER))
        val rows = TankDay.upsert(listOf(TankDay.HEADER, day.csv()), day.copy(feeds = 3))
        assertEquals(listOf(TankDay.HEADER, day.copy(feeds = 3).csv()), rows)
        val earlier = day.copy(date = nov26.minusDays(1))
        assertEquals("oldest first", earlier.csv(), TankDay.upsert(rows, earlier)[1])
    }

    @Test
    fun describedAgainstTheUsualDay() {
        val days = (1..3).map { TankDay(nov26.minusDays(it.toLong()), 720, 5.0, 3, 0.30, 0.37, 80.0, 2) }
        val usual = TankDay.usual(days)!!
        assertEquals(5.0, usual.activity, 1e-9)
        assertNull("too few days", TankDay.usual(days.take(2)))
        val today = TankDay(nov26, 600, 2.5, 1, 0.25, 0.38, 70.0, 1)
        val words = today.describe(usual)
        assertTrue(words, words.startsWith("2026-11-26: lit 10.0 h, activity 2.5 (usual 5.0), up to 1 moving at once"))
        assertTrue(words, words.contains("plants 25% of the picture (usual 30%)"))
        assertTrue(words, words.endsWith("fed once"))
    }
}
