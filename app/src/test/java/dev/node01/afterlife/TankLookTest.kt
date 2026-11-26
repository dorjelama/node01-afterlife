package dev.node01.afterlife

import dev.node01.afterlife.TankLook.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TankLookTest {

    private fun grey(level: Int, size: Int = 100) = ByteArray(size) { level.toByte() }
    private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun brightnessReadsBytesAsUnsigned() {
        assertEquals(200, TankLook.brightness(grey(200)))
        assertEquals(0, TankLook.brightness(ByteArray(0)))
        assertTrue(Stats(200, 0.3, 1.0).lightsOn)
        assertFalse(Stats(20, 0.3, 1.0).lightsOn)
    }

    @Test
    fun greenIsTheGreenShare() {
        assertEquals(1.0 / 3, TankLook.green(IntArray(10) { argb(100, 100, 100) }), 1e-9)
        assertEquals(0.5, TankLook.green(IntArray(10) { argb(50, 100, 50) }), 1e-9)
        assertEquals("black is not green", 1.0 / 3, TankLook.green(IntArray(10) { argb(0, 0, 0) }), 1e-9)
    }

    @Test
    fun motionIsTheChangeBetweenFrames() {
        assertEquals(0.0, TankLook.motion(listOf(grey(100), grey(100), grey(100))), 1e-9)
        assertEquals(10.0, TankLook.motion(listOf(grey(100), grey(110), grey(100))), 1e-9)
        assertEquals(0.0, TankLook.motion(listOf(grey(100))), 1e-9)
    }

    @Test
    fun comparedOnlyWithAWeekOfLitLooks() {
        val usual = List(TankLook.MIN_PAST) { Stats(150, 0.40, 4.0) }
        assertTrue(TankLook.greener(Stats(150, 0.45, 4.0), usual))
        assertFalse(TankLook.greener(Stats(150, 0.41, 4.0), usual))
        assertTrue(TankLook.still(Stats(150, 0.40, 0.5), usual))
        assertFalse(TankLook.still(Stats(150, 0.40, 3.0), usual))
        assertFalse("too few to say", TankLook.still(Stats(150, 0.40, 0.5), usual.take(5)))
        assertFalse("dark looks don't count", TankLook.still(Stats(150, 0.40, 0.5), List(50) { Stats(10, 0.3, 4.0) }))
        assertFalse("light off now", TankLook.still(Stats(10, 0.40, 0.0), usual))
    }

    @Test
    fun keptAsALogLine() {
        val s = Stats(118, 0.412, 3.07)
        assertEquals("118 0.412 3.07", s.encode())
        assertEquals(s, TankLook.decode(s.encode()))
        assertNull(TankLook.decode("feeding done"))
    }

    @Test
    fun describedForTheModel() {
        assertTrue(TankLook.describe(Stats(10, 0.3, 0.0), false, false).contains("light is off"))
        assertTrue(TankLook.describe(Stats(150, 0.4, 3.0), true, false).contains("greener than last week"))
        assertTrue(TankLook.describe(Stats(150, 0.4, 0.1), false, true).contains("much stiller"))
    }
}
