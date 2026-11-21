package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BsDateTest {

    @Test
    fun knownDates() {
        // The epoch, Nepali New Year 2083, and a date checked against a printed calendar.
        assertEquals(BsDate(2000, 1, 1), Bs.fromAd(LocalDate.of(1943, 4, 14)))
        assertEquals(BsDate(2083, 1, 1), Bs.fromAd(LocalDate.of(2026, 4, 14)))
        assertEquals(BsDate(2083, 4, 31), Bs.fromAd(LocalDate.of(2026, 8, 16)))
    }

    @Test
    fun roundTrips() {
        var d = LocalDate.of(2020, 1, 1)
        while (d.isBefore(LocalDate.of(2030, 1, 1))) {
            val bs = Bs.fromAd(d)!!
            assertEquals(d, Bs.toAd(bs.year, bs.month, bs.day))
            d = d.plusDays(1)
        }
    }

    @Test
    fun outsideTheTable() {
        assertNull(Bs.fromAd(LocalDate.of(1943, 4, 13)))
        assertNull(Bs.toAd(2083, 13, 1))
        assertNull(Bs.toAd(2083, 1, 40))
    }

    @Test
    fun nepaliDigits() {
        assertEquals("२०८३", Bs.neDigits(2083))
        assertEquals("०५", Bs.neDigits2(5))
    }
}
