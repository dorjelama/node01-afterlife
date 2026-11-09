package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceTest {

    /** Gaze from a face centre at ([sx], [sy]) of the sensor, turned by [rotation]. */
    private fun assertGaze(x: Float, y: Float, sx: Float, sy: Float, rotation: Int) {
        val (gx, gy) = Presence.toGaze(sx, sy, rotation)
        assertEquals("x for ($sx, $sy) at $rotation°", x, gx, 1e-6f)
        assertEquals("y for ($sx, $sy) at $rotation°", y, gy, 1e-6f)
    }

    @Test
    fun centreIsStraightAhead() {
        for (r in listOf(0, 90, 180, 270, 360)) assertGaze(0f, 0f, 0.5f, 0.5f, r)
    }

    @Test
    fun frontCameraIsMirrored() {
        // Upright sensor: a face on the image's left is on your right.
        assertGaze(1f, 0f, 0f, 0.5f, 0)
        assertGaze(0f, -1f, 0.5f, 0f, 0)
    }

    @Test
    fun turnsClockwise() {
        // The image's top edge, turned a quarter clockwise, is its right edge -- mirrored, your left.
        assertGaze(-1f, 0f, 0.5f, 0f, 90)
        assertGaze(0f, 1f, 0.5f, 0f, 180)
        assertGaze(1f, 0f, 0.5f, 0f, 270)
        // Sensor orientation 270 plus display rotation 90 is this phone in landscape: no turn.
        assertGaze(-0.6f, 0.4f, 0.8f, 0.7f, 360)
    }
}
