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

    /**
     * A 4×2 NV21 frame, luma 0..7 row by row, then two chroma pairs -- (10, 11)
     * under the left half and (20, 21) under the right -- turned upright for the camera view.
     */
    @Test
    fun turnsCameraViewFramesUpright() {
        val frame = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 20, 21)
        fun turned(degrees: Int) = Presence.rotateNv21(frame, 4, 2, degrees).toList()
        // A quarter clockwise: each column, read bottom up, becomes a row; now 2 wide and 4 tall.
        assertEquals(listOf<Byte>(4, 0, 5, 1, 6, 2, 7, 3, 10, 11, 20, 21), turned(90))
        assertEquals(listOf<Byte>(7, 6, 5, 4, 3, 2, 1, 0, 20, 21, 10, 11), turned(180))
        assertEquals(listOf<Byte>(3, 7, 2, 6, 1, 5, 0, 4, 20, 21, 10, 11), turned(270))
        // This phone in landscape: 270 + 90, no turn at all.
        assertEquals(frame.toList(), turned(360))
    }
}
