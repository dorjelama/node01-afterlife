package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CatShapeTest {

    @Test
    fun everyPoseStaysInItsBox() {
        val frames = CatShape.FRAMES
        assertEquals("four steps, then a crouch and a sit, each with a flick", 8, frames.size)
        for (f in frames) {
            for (k in f.indices step 2) {
                assertTrue("column ${f[k]}", f[k] in 0 until CatShape.W)
                assertTrue("row ${f[k + 1]}", f[k + 1] in 0 until CatShape.H)
            }
        }
        // Legs that reach differ from legs that pass under; a flicked tail differs from a still one.
        assertNotEquals(frames[0].toList(), frames[1].toList())
        assertNotEquals(frames[CatShape.SIT].toList(), frames[CatShape.SIT + 1].toList())
    }

    @Test
    fun playsToAndFroWithoutEverLeavingItsPatch() {
        val play = CatPlay(lo = 3, hi = 57, start = 30, random = Random(7))
        var turns = 0
        var facing = play.facing
        var leftmost = Int.MAX_VALUE
        var rightmost = Int.MIN_VALUE
        var pounced = false
        val poses = HashSet<Int>()
        repeat(20_000) {
            play.step()
            assertTrue("x ${play.x}", play.x in 3..57)
            assertTrue("frame ${play.frame}", play.frame in CatShape.FRAMES.indices)
            if (play.facing != facing) {
                turns++
                facing = play.facing
            }
            leftmost = minOf(leftmost, play.x)
            rightmost = maxOf(rightmost, play.x)
            if (play.lift > 0) pounced = true
            poses += play.frame
        }
        assertTrue("turns back, $turns times", turns > 20)
        assertEquals("reaches the left edge", 3, leftmost)
        assertEquals("and the right", 57, rightmost)
        assertTrue("pounces", pounced)
        assertTrue("sits and crouches", CatShape.SIT in poses && CatShape.CROUCH in poses)
    }
}
