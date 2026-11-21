package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTest {

    private fun at(tenthsC: Int, percent: Int = 100, plugged: Boolean = true, wasHot: Boolean = false) =
        Battery.of(tenthsC, level = percent, scale = 100, plugged = plugged, wasHot = wasHot)

    @Test
    fun readsTheBroadcastsNumbers() {
        val b = Battery.of(tenthsC = 312, level = 47, scale = 100, plugged = true, wasHot = false)
        assertEquals(31, b.tempC)
        assertEquals(47, b.percent)
        assertTrue(b.plugged)
        assertFalse(b.wrong)
        // A scale that is not 100 still gives a percentage.
        assertEquals(50, Battery.of(250, level = 1000, scale = 2000, plugged = true, wasHot = false).percent)
    }

    @Test
    fun staysHotUntilItIsProperlyCool() {
        assertFalse("44 is not hot yet", at(440).hot)
        assertTrue(at(450).hot)
        // Hovering at the line must not flicker: once hot, hot until under 42.
        assertTrue("43 after being hot", at(430, wasHot = true).hot)
        assertTrue(at(420, wasHot = true).hot)
        // Whole degrees, the same ones the caption shows: 41.9 still reads as 42.
        assertTrue(at(419, wasHot = true).hot)
        assertFalse(at(414, wasHot = true).hot)
        assertTrue(at(460).wrong)
    }

    @Test
    fun unpluggedAndLowIsWrongButPluggedIsFine() {
        assertFalse("plugged in and low is just charging", at(300, percent = 5).wrong)
        assertTrue(at(300, percent = 20, plugged = false).low)
        assertFalse(at(300, percent = 21, plugged = false).low)
        assertTrue(at(300, percent = 8, plugged = false).wrong)
    }

    @Test
    fun aPhoneThatSaysNothingIsHealthy() {
        val quiet = Battery.of(tenthsC = 0, level = -1, scale = -1, plugged = false, wasHot = false)
        assertEquals("unknown charge reads as full, never a false alarm", 100, quiet.percent)
        assertFalse(quiet.wrong)
    }
}
