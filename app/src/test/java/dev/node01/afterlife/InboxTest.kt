package dev.node01.afterlife

import dev.node01.afterlife.Inbox.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun dueNowOrLater() {
        val inbox = Inbox()
        inbox.add("later", 10 * minute, now)
        inbox.add("now", 0, now)
        assertEquals("now", inbox.due(now)?.text)
        assertTrue(inbox.dismiss(now))
        assertNull(inbox.due(now))
        assertEquals("later", inbox.due(now + 10 * minute)?.text)
        assertFalse(inbox.dismiss(now))
    }

    @Test
    fun cleansText() {
        val inbox = Inbox()
        assertEquals("two lines and tab", inbox.add("  two\nlines\tand   tab ", 0, now)?.text)
        assertNull(inbox.add(" \n\t ", 0, now))
        assertEquals(Inbox.MAX_CHARS, inbox.add("x".repeat(500), 0, now)?.text?.length)
    }

    @Test
    fun delayIsBounded() {
        val inbox = Inbox()
        assertEquals(now, inbox.add("a", -5, now)?.dueMs)
        assertEquals(now + Inbox.MAX_DELAY_MS, inbox.add("b", Long.MAX_VALUE / 2, now)?.dueMs)
    }

    @Test
    fun oldestGiveWay() {
        val inbox = Inbox()
        repeat(Inbox.MAX_NOTES + 3) { inbox.add("n$it", 0, now + it) }
        assertEquals(Inbox.MAX_NOTES, inbox.size)
        assertEquals("n3", inbox.due(now + 100)?.text)
    }

    @Test
    fun timersCanBeCancelledAndNothingElse() {
        val inbox = Inbox()
        inbox.add("Time's up.", 5 * minute, now, Kind.TIMER)
        inbox.add("stretch", 5 * minute, now, Kind.REMINDER)
        inbox.add("desk note", 5 * minute, now, Kind.NOTE)
        assertTrue(inbox.timerRunning(now))
        assertTrue(inbox.cancelTimers(now))
        assertFalse(inbox.timerRunning(now))
        assertFalse("nothing left to cancel", inbox.cancelTimers(now))
        assertEquals(2, inbox.size)
    }

    @Test
    fun aTimerThatHasRungIsNoLongerRunning() {
        val inbox = Inbox()
        inbox.add("Time's up.", minute, now, Kind.TIMER)
        assertFalse(inbox.timerRunning(now + minute))
        assertFalse("ringing, so dismissed rather than cancelled", inbox.cancelTimers(now + minute))
        assertEquals(Kind.TIMER, inbox.due(now + minute)?.kind)
    }

    @Test
    fun survivesARestart() {
        val inbox = Inbox()
        inbox.add("drink water", 5 * minute, now, Kind.REMINDER)
        inbox.add("stretch · now", 0, now)
        inbox.add("समय सकियो।", 25 * minute, now, Kind.TIMER)
        val restored = Inbox.decode(inbox.encode())
        assertEquals(inbox.encode(), restored.encode())
        assertEquals("stretch · now", restored.due(now)?.text)
        assertTrue(restored.dismiss(now))
        assertEquals(Kind.REMINDER, restored.due(now + 5 * minute)?.kind)
        assertTrue(restored.timerRunning(now))
        assertEquals(0, Inbox.decode(null).size)
        assertEquals(0, Inbox.decode("garbage\nmore\tgarbage").size)
    }

    @Test
    fun readsNotesSavedBeforeKinds() {
        val restored = Inbox.decode("$now\tStretch · drink water")
        assertEquals(Inbox.Note(now, "Stretch · drink water", Kind.NOTE), restored.due(now))
    }
}
