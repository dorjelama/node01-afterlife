package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterWatchTest {

    private val day = Tank.DAY_MS
    private val hour = 3_600_000L
    private val t0 = 1_795_694_400_000L

    /** Changed at [at], then a clean first day: green 0.35, clarity 40. */
    private fun clean(at: Long = t0, from: WaterWatch = WaterWatch()) =
        (1..10).fold(from.changed(at)) { w, i -> w.look(0.35, 40.0, at + i * hour) }

    @Test
    fun theFirstDayIsTheCleanWater() {
        val w = clean()
        assertEquals(0.35, w.baseGreen, 1e-9)
        assertEquals(40.0, w.baseClarity, 1e-9)
        assertEquals(10, w.baseLooks)
        assertEquals("after the first day, only the latest", 10, w.look(0.50, 10.0, t0 + 2 * day).baseLooks)
    }

    @Test
    fun beforeItsPaceIsKnownFixedLimitsCount() {
        val w = clean()
        assertFalse("not within the first day", w.faster(0.50, 40.0, t0 + 20 * hour))
        assertFalse(w.faster(0.37, 40.0, t0 + 3 * day))
        assertTrue("greener", w.faster(0.39, 40.0, t0 + 3 * day))
        assertTrue("hazier", w.faster(0.35, 29.0, t0 + 3 * day))
        assertFalse("no change known", WaterWatch().faster(0.9, 1.0, t0))
    }

    @Test
    fun learnsItsOwnPace() {
        // Two weekly cycles greening 0.07 each: 0.01 a day.
        var w = clean()
        w = w.look(0.42, 40.0, t0 + 7 * day)
        w = clean(t0 + 7 * day, w).look(0.42, 40.0, t0 + 14 * day)
        w = clean(t0 + 14 * day, w)
        assertEquals(2, w.rates.size)
        assertEquals(0.01, w.rates.last(), 1e-9)
        val start = t0 + 14 * day
        assertFalse("its usual pace, 3 days in", w.faster(0.38, 40.0, start + 3 * day))
        assertTrue("twice its pace", w.faster(0.42, 40.0, start + 3 * day))
    }

    @Test
    fun keptAcrossRestarts() {
        val w = clean().look(0.40, 35.0, t0 + 3 * day).changed(t0 + 7 * day)
        assertEquals(w, WaterWatch.decode(w.encode()))
        assertEquals(WaterWatch(), WaterWatch.decode(null))
        val fresh = WaterWatch().look(0.3, 30.0, t0)
        assertEquals(fresh, WaterWatch.decode(fresh.encode()))
    }
}
