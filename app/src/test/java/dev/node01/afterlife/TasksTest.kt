package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksTest {

    private val now = 1_700_000_000_000L

    @Test
    fun keptUntilMarkedDone() {
        val tasks = Tasks()
        val domain = tasks.add("Renew the domain", "Before the 20th.\nCard ends 4242.", now)!!
        val sam = tasks.add("Reply to Sam", "", now)!!
        assertNotEquals("two in the same millisecond still get their own ids", domain.id, sam.id)
        assertEquals("one line, so it can be stored", "Before the 20th. Card ends 4242.", domain.description)

        assertTrue(tasks.complete(domain.id))
        assertEquals(listOf(sam), tasks.all)
        assertFalse("already done", tasks.complete(domain.id))
    }

    @Test
    fun cleansAndCaps() {
        val tasks = Tasks()
        assertNull("no title, no task", tasks.add(" \n\t ", "details", now))
        val long = tasks.add("x".repeat(500), "y".repeat(2_000), now)!!
        assertEquals(Inbox.MAX_CHARS, long.title.length)
        assertEquals(Tasks.MAX_DESCRIPTION, long.description.length)
    }

    @Test
    fun aFullListRefusesRatherThanDropping() {
        val tasks = Tasks()
        repeat(Tasks.MAX_TASKS) { assertTrue(tasks.add("t$it", "", now + it) != null) }
        assertNull(tasks.add("one too many", "", now))
        assertEquals("the first is still there", "t0", tasks.all.first().title)
    }

    @Test
    fun survivesARestart() {
        val tasks = Tasks()
        tasks.add("Renew the domain", "Before the 20th · card ends 4242", now)
        tasks.add("दूध किन्नु", "", now)
        val restored = Tasks.decode(tasks.encode())
        assertEquals(tasks.all, restored.all)
        // Ids carry on from the restored ones, so a new task never takes an old one's.
        val next = restored.add("Call mom", "", 0L)!!
        assertTrue(restored.all.dropLast(1).all { it.id < next.id })
        assertEquals(0, Tasks.decode(null).size)
        assertEquals(0, Tasks.decode("garbage\nmore\tgarbage\n12\t").size)
    }
}
