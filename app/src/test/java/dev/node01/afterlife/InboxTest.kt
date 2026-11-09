package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {

    private val now = 1_700_000_000_000L

    @Test
    fun dueNowOrLater() {
        val inbox = Inbox()
        inbox.add("later", 10, now)
        inbox.add("now", 0, now)
        assertEquals("now", inbox.due(now)?.text)
        assertTrue(inbox.dismiss(now))
        assertNull(inbox.due(now))
        assertEquals("later", inbox.due(now + 10 * 60_000)?.text)
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
        assertEquals(now + Inbox.MAX_MINUTES * 60_000L, inbox.add("b", 1_000_000, now)?.dueMs)
    }

    @Test
    fun oldestGiveWay() {
        val inbox = Inbox()
        repeat(Inbox.MAX_NOTES + 3) { inbox.add("n$it", 0, now + it) }
        assertEquals(Inbox.MAX_NOTES, inbox.size)
        assertEquals("n3", inbox.due(now + 100)?.text)
    }

    @Test
    fun survivesARestart() {
        val inbox = Inbox()
        inbox.add("drink water", 5, now)
        inbox.add("stretch · now", 0, now)
        val restored = Inbox.decode(inbox.encode())
        assertEquals(inbox.encode(), restored.encode())
        assertEquals("stretch · now", restored.due(now)?.text)
        assertEquals(0, Inbox.decode(null).size)
        assertEquals(0, Inbox.decode("garbage\nmore\tgarbage").size)
    }
}
