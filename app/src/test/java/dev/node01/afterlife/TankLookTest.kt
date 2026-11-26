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
    fun clarityIsTheSpread() {
        assertEquals(0.0, TankLook.clarity(grey(100)), 1e-9)
        assertEquals(50.0, TankLook.clarity(ByteArray(100) { (if (it % 2 == 0) 50 else 150).toByte() }), 1e-9)
    }

    @Test
    fun plantsAreTheClearlyGreenShare() {
        assertEquals(0.5, TankLook.plants(intArrayOf(argb(40, 120, 40), argb(100, 100, 100))), 1e-9)
        assertEquals("barely greener is not a plant", 0.0, TankLook.plants(intArrayOf(argb(100, 105, 100))), 1e-9)
    }

    @Test
    fun moversAreSeparateBlobsOfChange() {
        val w = 10
        val still = ByteArray(100) { 50 }
        val two = still.copyOf().apply { this[11] = 200.toByte(); this[12] = 200.toByte(); this[77] = 200.toByte(); this[78] = 200.toByte() }
        assertEquals(2, TankLook.movers(listOf(still, two), w))
        val speck = still.copyOf().apply { this[55] = 200.toByte() }
        assertEquals("one changed pixel is noise", 0, TankLook.movers(listOf(still, speck), w))
        val edge = still.copyOf().apply { this[9] = 200.toByte(); this[10] = 200.toByte() }
        assertEquals("a row's end does not join the next row's start", 0, TankLook.movers(listOf(still, edge), w))
        assertEquals(0, TankLook.movers(listOf(still), w))
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
    fun aFeedingIsABurstOfMovement() {
        val spot = TankLook.FeedSpotter()
        repeat(19) { assertFalse(spot.minute(5.0)) }
        assertFalse("too little known yet", spot.minute(20.0))
        repeat(5) { spot.minute(5.0) }
        assertTrue(spot.minute(12.0))
        assertFalse("the same frenzy", spot.minute(15.0))
        repeat(30) { spot.minute(5.0) }
        assertTrue("another, later", spot.minute(11.0))
    }

    @Test
    fun aRestlessMinuteIsNoFeeding() {
        val spot = TankLook.FeedSpotter()
        repeat(30) { spot.minute(5.0) }
        assertFalse("half again, not twice", spot.minute(7.5))
        val busy = TankLook.FeedSpotter()
        repeat(30) { busy.minute(1.0) }
        assertFalse("twice almost nothing is still almost nothing", busy.minute(2.5))
    }

    @Test
    fun keptAsALogLine() {
        val s = Stats(118, 0.412, 3.07, 41.2, 0.31, 3)
        assertEquals("118 0.412 3.07 41.2 0.310 3", s.encode())
        assertEquals(s, TankLook.decode(s.encode()))
        assertEquals("a minutes.csv row's numbers", s, TankLook.decode("118,0.412,3.07,41.2,0.310,3"))
        assertEquals("an older line, without clarity", Stats(118, 0.412, 3.07), TankLook.decode("118 0.412 3.07"))
        assertNull(TankLook.decode("feeding done"))
    }

    @Test
    fun describedForTheModel() {
        assertTrue(TankLook.describe(Stats(10, 0.3, 0.0), false, false).contains("light is off"))
        assertTrue(TankLook.describe(Stats(150, 0.4, 3.0), true, false).contains("greener than last week"))
        assertTrue(TankLook.describe(Stats(150, 0.4, 0.1), false, true).contains("much stiller"))
    }
}
