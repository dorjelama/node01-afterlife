package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopRecorderTest {

    private val gb = 1_000_000_000L

    private fun clip(minute: Int, size: Long) = "NODE_20261029_12%02d00.mp4".format(minute) to size

    @Test
    fun keepsEverythingUnderCapWithSpaceToSpare() {
        val clips = listOf(clip(0, 3 * gb), clip(5, 3 * gb))
        assertEquals(emptyList<String>(), clipsToDelete(clips, 8 * gb, 10 * gb, 2 * gb))
    }

    @Test
    fun deletesOldestFirstUntilUnderCap() {
        // 12 GB against an 8 GB cap: the two oldest go, whatever the input order.
        val clips = listOf(clip(15, 3 * gb), clip(0, 3 * gb), clip(10, 3 * gb), clip(5, 3 * gb))
        assertEquals(
            listOf(clip(0, 0).first, clip(5, 0).first),
            clipsToDelete(clips, 8 * gb, 10 * gb, 2 * gb),
        )
    }

    @Test
    fun deletesUntilFreeSpaceFloorIsMet() {
        // Well under the cap, but the phone itself is nearly full.
        val clips = listOf(clip(0, 1 * gb), clip(5, 1 * gb), clip(10, 1 * gb))
        assertEquals(
            listOf(clip(0, 0).first, clip(5, 0).first),
            clipsToDelete(clips, 8 * gb, gb / 2, 2 * gb),
        )
    }

    @Test
    fun neverReturnsAnythingItWasNotGiven() {
        // Hopeless free space: everything may go, but nothing else.
        val clips = listOf(clip(0, gb), clip(5, gb))
        val doomed = clipsToDelete(clips, 8 * gb, 0, 100 * gb)
        assertEquals(clips.map { it.first }, doomed)
        assertTrue(clipsToDelete(emptyList(), 8 * gb, 0, 100 * gb).isEmpty())
    }
}
