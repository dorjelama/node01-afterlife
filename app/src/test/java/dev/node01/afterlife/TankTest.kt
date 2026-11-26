package dev.node01.afterlife

import dev.node01.afterlife.Tank.Job
import dev.node01.afterlife.Tank.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TankTest {

    private val day = Tank.DAY_MS
    private val hour = 3_600_000L
    private val utc = ZoneOffset.UTC

    // 2026-11-26 12:00 UTC.
    private val noon = 1_795_694_400_000L
    private val start = noon - 10 * day

    @Test
    fun aJobWithNoRecordCountsFromTheStart() {
        val t = Tank.start(start)
        assertEquals(start + 7 * day, t.dueMs(Job.WATER_CHANGE))
        assertEquals(10, t.daysSince(Job.WATER_CHANGE, noon))
        assertEquals("due longest ago first", listOf(Job.FEED, Job.WATER_CHANGE, Job.FERTILIZE), t.overdue(noon))
    }

    @Test
    fun doneMovesTheNextOneOn() {
        val t = Tank.start(start).done(Job.WATER_CHANGE, noon - day)
        assertEquals(noon + 6 * day, t.dueMs(Job.WATER_CHANGE))
        assertTrue(Job.WATER_CHANGE !in t.overdue(noon))
    }

    @Test
    fun theIntervalIsYours() {
        val t = Tank.start(start).setUp(mapOf("every.WATER_CHANGE" to "14", "every.FILTER" to "nonsense"))
        assertEquals(14, t.every(Job.WATER_CHANGE))
        assertEquals("unreadable keeps the default", 30, t.every(Job.FILTER))
        assertEquals(1, Tank.start(start).setUp(mapOf("every.FEED" to "0")).every(Job.FEED))
    }

    @Test
    fun aSecondFeedingSoonIsNoticed() {
        val t = Tank.start(start).done(Job.FEED, noon - 3 * hour)
        assertEquals(noon - 3 * hour, t.fedWithin(noon))
        assertNull(t.fedWithin(noon + 2 * hour))
        assertNull(Tank.start(start).fedWithin(noon))
    }

    @Test
    fun anOverdueJobIsBroughtUpOnceADay() {
        val t = Tank.start(start)
        assertEquals(Job.FEED, t.toRaise(noon, utc))
        val raised = t.raised(Job.FEED, noon)
        assertEquals("the next one, not the same again", Job.WATER_CHANGE, raised.toRaise(noon + hour, utc))
        val all = Job.entries.fold(t) { acc, job -> acc.raised(job, noon) }
        assertNull(all.toRaise(noon + hour, utc))
        assertEquals("tomorrow, again", Job.FEED, all.toRaise(noon + day, utc))
    }

    @Test
    fun riskGrowsWithNeglect() {
        val cared = Tank.start(start).done(Job.WATER_CHANGE, noon - day)
        assertEquals(Risk.LOW, cared.risk(noon).first)
        assertEquals(Risk.BUILDING, Tank.start(start).risk(noon).first)
        assertEquals(Risk.HIGH, Tank.start(noon - 15 * day).risk(noon).first)
        assertEquals(Risk.HIGH, Tank.start(start).risk(noon, greener = true).first)
        val (risk, why) = Tank.start(start).risk(noon)
        assertEquals(Risk.BUILDING, risk)
        assertEquals(listOf("10 days since the last water change"), why)
    }

    @Test
    fun overfeedingCounts() {
        var t = Tank.start(start).done(Job.WATER_CHANGE, noon - day)
        for (i in 0 until 12) t = t.done(Job.FEED, noon - i * 6 * hour)
        val (risk, why) = t.risk(noon)
        assertEquals(Risk.BUILDING, risk)
        assertTrue(why.single().startsWith("fed about 4 times a day"))
    }

    @Test
    fun notesSayWhatIsKnownAndThatItIsAGuess() {
        val t = Tank.start(start).setUp(mapOf("litres" to "60", "livestock" to "6 neon tetras"))
            .done(Job.FEED, noon - 2 * hour)
        val notes = t.notes(noon, utc, camera = "The lights are on.")
        assertTrue(notes, notes.startsWith("The aquarium: low-tech planted, 60 litres, with 6 neon tetras."))
        assertTrue(notes, notes.contains("Fed today at 10:00 AM."))
        assertTrue(notes, notes.contains("Water change: last not recorded yet, due every 7 days."))
        assertTrue(notes, notes.contains("The lights are on."))
        assertTrue(notes, notes.contains("no water tests: building, because 10 days since the last water change."))
    }

    @Test
    fun keptAcrossRestarts() {
        val t = Tank.start(start).setUp(mapOf("name" to "Desk tank", "litres" to "60", "every.TRIM" to "21"))
            .done(Job.FEED, noon - day).done(Job.FEED, noon).done(Job.FILTER, noon).raised(Job.WATER_CHANGE, noon)
        assertEquals(t, Tank.decode(t.encode()))
        assertNull(Tank.decode(null))
        assertNull(Tank.decode(""))
    }

    @Test
    fun feedingsOlderThanAWeekAreDropped() {
        val t = Tank.start(start).done(Job.FEED, noon - 8 * day).done(Job.FEED, noon)
        assertEquals(listOf(noon), t.feeds)
    }
}
