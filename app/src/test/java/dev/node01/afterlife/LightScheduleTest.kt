package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LightScheduleTest {

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    /** On at 7, off at 19, on the 20th to the 22nd. */
    private val usual = (20..22).fold(LightSchedule()) { s, d -> s.on(at(d, 7)).off(at(d, 19)) }

    @Test
    fun learnsTheUsualDay() {
        assertEquals(7 * 60, usual.usualOn())
        assertEquals(12 * 60, usual.usualMinutes())
        assertNull("too few days to say", LightSchedule().on(at(20, 7)).usualOn())
    }

    @Test
    fun noticesALightThatDidNotComeOn() {
        assertFalse("not yet an hour late", usual.late(at(23, 7, 59)))
        assertTrue(usual.late(at(23, 8, 0)))
        assertFalse("it came on", usual.on(at(23, 7, 30)).late(at(23, 9)))
    }

    @Test
    fun noticesALightLeftOn() {
        val today = usual.on(at(23, 7))
        assertFalse(today.tooLong(at(23, 20, 59)))
        assertTrue("two hours past its usual twelve", today.tooLong(at(23, 21)))
        assertFalse("it went off", today.off(at(23, 19)).tooLong(at(23, 22)))
        assertTrue("fourteen hours, with nothing learnt yet", LightSchedule().on(at(23, 7)).tooLong(at(23, 21)))
    }

    @Test
    fun offWithoutOnIsIgnored() {
        assertEquals(usual, usual.off(at(23, 19)))
    }

    @Test
    fun keepsTwoWeeksAcrossRestarts() {
        val long = (1..20).fold(LightSchedule()) { s, d -> s.on(at(d, 7)).off(at(d, 19)) }
        assertEquals(LightSchedule.KEEP_DAYS, long.days.size)
        assertEquals(long, LightSchedule.decode(long.encode()))
        val on = usual.on(at(23, 7, 15))
        assertEquals(on, LightSchedule.decode(on.encode()))
        assertEquals(LightSchedule(), LightSchedule.decode(null))
    }
}
