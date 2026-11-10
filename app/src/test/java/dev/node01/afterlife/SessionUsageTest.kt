package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionUsageTest {

    @Test
    fun readsThePercentageAndTheReset() {
        assertEquals(SessionUsage(24, 1_762_790_400_000L), SessionUsage.parse(mapOf("pct" to "23.5", "resets" to "1762790400")))
        assertEquals(SessionUsage(7, null), SessionUsage.parse(mapOf("pct" to "7")))
        assertEquals("an empty reset is no reset", SessionUsage(7, null), SessionUsage.parse(mapOf("pct" to "7", "resets" to "")))
    }

    @Test
    fun keepsToZeroToAHundred() {
        assertEquals(100, SessionUsage.parse(mapOf("pct" to "104.2"))!!.percent)
        assertEquals(0, SessionUsage.parse(mapOf("pct" to "-3"))!!.percent)
    }

    @Test
    fun ignoresWhatIsNotAPercentage() {
        assertNull(SessionUsage.parse(emptyMap()))
        assertNull(SessionUsage.parse(mapOf("pct" to "")))
        assertNull(SessionUsage.parse(mapOf("pct" to "null")))
        assertNull(SessionUsage.parse(mapOf("pct" to "NaN")))
    }

    @Test
    fun aResetWindowIsUnused() {
        val u = SessionUsage(62, 10_000L)
        assertEquals(62, u.shownAt(9_999L))
        assertEquals(0, u.shownAt(10_000L))
        assertEquals("no reset time: keep showing it", 62, SessionUsage(62, null).shownAt(Long.MAX_VALUE))
    }

    @Test
    fun survivesARestart() {
        assertEquals(SessionUsage(41, 123L), SessionUsage.decode(SessionUsage(41, 123L).encode()))
        assertEquals(SessionUsage(41, null), SessionUsage.decode(SessionUsage(41, null).encode()))
        assertNull(SessionUsage.decode(null))
        assertNull(SessionUsage.decode("junk"))
    }
}
