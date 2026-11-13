package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcStatsTest {

    @Test
    fun readsTheScriptsForm() {
        assertEquals(PcStats(42, 63, 8), PcStats.parse(mapOf("cpu" to "42", "ram" to "62.6", "disk" to " 8 ")))
        // Disk time can pass 100 on a busy drive, and nothing is below 0.
        assertEquals(PcStats(0, 100, 100), PcStats.parse(mapOf("cpu" to "-3", "ram" to "100", "disk" to "312")))
    }

    @Test
    fun allThreeOrNothing() {
        assertNull(PcStats.parse(mapOf("cpu" to "42", "ram" to "63")))
        assertNull(PcStats.parse(mapOf("cpu" to "x", "ram" to "63", "disk" to "8")))
        assertNull(PcStats.parse(mapOf("cpu" to "NaN", "ram" to "63", "disk" to "8")))
        assertNull(PcStats.parse(emptyMap()))
    }
}
