package dev.node01.afterlife

import dev.node01.afterlife.ClaudeSessions.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSessionsTest {

    private val sessions = ClaudeSessions()

    private fun event(name: String, id: String = "s1", cwd: String = "C:\\code\\node01", type: String? = null) =
        buildMap {
            put(ClaudeSessions.EVENT, name)
            put(ClaudeSessions.SESSION_ID, id)
            put(ClaudeSessions.CWD, cwd)
            if (type != null) put(ClaudeSessions.NOTIFICATION_TYPE, type)
        }

    @Test
    fun followsOneTurn() {
        assertEquals(State.WORKING, sessions.onEvent(event("UserPromptSubmit"), 0))
        assertNull("still working is not news", sessions.onEvent(event("PostToolUse"), 10))
        assertEquals(State.NEEDS_YOU, sessions.onEvent(event("PermissionRequest"), 20))
        assertEquals(State.NEEDS_YOU, sessions.summary(20)?.state)
        assertEquals("approved, carried on", State.WORKING, sessions.onEvent(event("PostToolUse"), 30))
        assertEquals(State.DONE, sessions.onEvent(event("Stop"), 40))
        assertEquals(ClaudeSessions.Summary(State.DONE, "node01", 0, seen = false), sessions.summary(40))
        assertNull(sessions.onEvent(event("SessionEnd"), 50))
        assertNull(sessions.summary(50))
    }

    @Test
    fun notificationsThatMeanNeedsYou() {
        for (type in listOf("permission_prompt", "elicitation_dialog", "agent_needs_input")) {
            val s = ClaudeSessions()
            assertEquals(type, State.NEEDS_YOU, s.onEvent(event("Notification", type = type), 0))
        }
        assertNull(sessions.onEvent(event("Notification", type = "auth_success"), 0))
        assertNull(sessions.summary(0))
    }

    @Test
    fun idleOnlyEndsWorkNeverAnApproval() {
        assertNull("idle with nothing going on", sessions.onEvent(event("Notification", type = "idle_prompt"), 0))
        sessions.onEvent(event("PermissionRequest"), 0)
        assertNull(sessions.onEvent(event("Notification", type = "idle_prompt"), 10))
        assertEquals(State.NEEDS_YOU, sessions.summary(10)?.state)
        sessions.onEvent(event("PostToolUse"), 20)
        assertEquals("missed Stop", State.DONE, sessions.onEvent(event("Notification", type = "idle_prompt"), 30))
    }

    @Test
    fun ignoresIncompleteOrUnknownEvents() {
        assertNull(sessions.onEvent(mapOf(ClaudeSessions.EVENT to "Stop"), 0))
        assertNull(sessions.onEvent(mapOf(ClaudeSessions.SESSION_ID to "s1"), 0))
        assertNull(sessions.onEvent(event("SubagentStop"), 0))
        assertNull(sessions.summary(0))
    }

    @Test
    fun mostUrgentSessionWins() {
        sessions.onEvent(event("UserPromptSubmit", "a", "/w/alpha"), 0)
        sessions.onEvent(event("Stop", "b", "/w/beta"), 0)
        assertEquals(ClaudeSessions.Summary(State.DONE, "beta", 0, false), sessions.summary(0))
        sessions.onEvent(event("PermissionRequest", "c", "/w/gamma"), 0)
        sessions.onEvent(event("PermissionRequest", "d", "/w/delta"), 5)
        assertEquals(ClaudeSessions.Summary(State.NEEDS_YOU, "delta", 1, false), sessions.summary(5))
    }

    @Test
    fun doneLingersBrieflyOnceSeen() {
        sessions.onEvent(event("Stop"), 0)
        assertFalse(sessions.summary(60_000)!!.seen)
        sessions.acknowledge(60_000, inViewSinceMs = 0)
        assertTrue(sessions.summary(60_000)!!.seen)
        assertEquals(State.DONE, sessions.summary(60_000 + ClaudeSessions.DONE_LINGER_MS)?.state)
        assertNull(sessions.summary(60_001 + ClaudeSessions.DONE_LINGER_MS))
    }

    @Test
    fun doneWhileYouSitThereStillGetsItsMoment() {
        sessions.onEvent(event("Stop"), 10_000)
        sessions.acknowledge(10_000 + ClaudeSessions.LOOK_MS - 1, inViewSinceMs = 0)
        assertFalse(sessions.summary(11_999)!!.seen)
        sessions.acknowledge(10_000 + ClaudeSessions.LOOK_MS, inViewSinceMs = 0)
        assertTrue(sessions.summary(12_000)!!.seen)
    }

    @Test
    fun doneWhileAwayIsSeenOnlyAfterYouLook() {
        sessions.onEvent(event("Stop"), 0)
        sessions.acknowledge(50_500, inViewSinceMs = 50_000)
        assertFalse("just sat down", sessions.summary(50_500)!!.seen)
        sessions.acknowledge(50_000 + ClaudeSessions.LOOK_MS, inViewSinceMs = 50_000)
        assertTrue(sessions.summary(52_000)!!.seen)
    }

    @Test
    fun unseenDoneOutranksSeenDone() {
        sessions.onEvent(event("Stop", "a", "/w/alpha"), 0)
        sessions.acknowledge(ClaudeSessions.LOOK_MS, inViewSinceMs = 0)
        sessions.onEvent(event("Stop", "b", "/w/beta"), ClaudeSessions.LOOK_MS)
        assertEquals("beta", sessions.summary(ClaudeSessions.LOOK_MS)!!.project)
    }

    @Test
    fun workingExpiresWithoutEvents() {
        sessions.onEvent(event("UserPromptSubmit"), 0)
        assertEquals(State.WORKING, sessions.summary(ClaudeSessions.WORKING_EXPIRY_MS)?.state)
        assertNull(sessions.summary(ClaudeSessions.WORKING_EXPIRY_MS + 1))
    }

    @Test
    fun aNewStateIsUnseenAgain() {
        val look = ClaudeSessions.LOOK_MS
        sessions.onEvent(event("Stop"), 0)
        sessions.acknowledge(look, inViewSinceMs = 0)
        sessions.onEvent(event("UserPromptSubmit"), look + 1)
        sessions.onEvent(event("Stop"), look + 2)
        assertFalse(sessions.summary(look + 2)!!.seen)
    }

    @Test
    fun projectIsTheLastFolder() {
        assertEquals("node01", ClaudeSessions.projectOf("C:\\Users\\me\\node01"))
        assertEquals("node01", ClaudeSessions.projectOf("C:\\Users\\me\\node01\\"))
        assertEquals("node01", ClaudeSessions.projectOf("/home/me/node01/"))
        assertEquals("claude", ClaudeSessions.projectOf(null))
        assertEquals("claude", ClaudeSessions.projectOf("/"))
        // A broken path cannot split the caption over lines or run it off the screen.
        assertEquals("C:codeode01", ClaudeSessions.projectOf("C:code\node01"))
        assertEquals(40, ClaudeSessions.projectOf("x".repeat(100)).length)
        assertEquals("claude", ClaudeSessions.projectOf("\n\t"))
    }
}
