package dev.node01.afterlife

import dev.node01.afterlife.ClaudeSessions.State
import dev.node01.afterlife.ClaudeSessions.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTest {

    private val long = 1_000_000_000L

    /** Sitting there, settled in, nothing going on. */
    private val sitting = Mood.Inputs(
        present = true,
        presentForMs = 60_000,
        awayForMs = 0,
        lastAbsenceMs = 5_000,
        faceX = 0.3f,
        faceY = -0.1f,
        dark = false,
        knockAgoMs = long,
        petAgoMs = long,
        claude = null,
        note = null,
    )

    private fun away(ms: Long) = sitting.copy(present = false, presentForMs = 0, awayForMs = ms)

    @Test
    fun followsYouWhenNothingIsGoingOn() {
        val look = Mood.decide(sitting)
        assertEquals(Expression.NEUTRAL, look.expression)
        assertEquals(Gaze.At(0.3f, -0.1f), look.gaze)
        assertEquals(Light.FULL, look.light)
        assertNull(look.caption)
        assertFalse(look.amber)
    }

    @Test
    fun searchesThenDozesThenSleeps() {
        assertEquals(Gaze.Wander, Mood.decide(away(10_000)).gaze)
        assertEquals(Light.FULL, Mood.decide(away(10_000)).light)
        assertEquals(Expression.SLEEPY, Mood.decide(away(Mood.SEARCH_MS)).expression)
        assertEquals(Light.DOZE, Mood.decide(away(Mood.SEARCH_MS)).light)
        assertEquals(Expression.CLOSED, Mood.decide(away(Mood.SLEEP_MS)).expression)
        assertEquals(Light.MIN, Mood.decide(away(Mood.SLEEP_MS)).light)
    }

    @Test
    fun darkRoomSleepsWhenAwayAndIsSleepyWhenNot() {
        val awayInDark = Mood.decide(away(5_000).copy(dark = true))
        assertEquals(Expression.CLOSED, awayInDark.expression)
        assertEquals(Light.MIN, awayInDark.light)
        assertEquals(Expression.SLEEPY, Mood.decide(sitting.copy(dark = true)).expression)
    }

    @Test
    fun needsYouIsAmberAndBright() {
        val look = Mood.decide(away(Mood.SLEEP_MS).copy(claude = Summary(State.NEEDS_YOU, "node01", 0, false)))
        assertEquals(Expression.WIDE, look.expression)
        assertTrue(look.amber)
        assertEquals(Caption.NeedsYou("node01", 0), look.caption)
        assertEquals(Light.FULL, look.light)
        assertEquals("looks straight out when nobody is there", Gaze.At(0f, 0f), look.gaze)
        assertEquals(
            "but no beacon in a dark room",
            Light.DOZE,
            Mood.decide(away(5_000).copy(dark = true, claude = Summary(State.NEEDS_YOU, "x", 0, false))).light,
        )
    }

    @Test
    fun aNoteOutranksClaude() {
        val look = Mood.decide(sitting.copy(note = "stretch", claude = Summary(State.NEEDS_YOU, "x", 0, false)))
        assertEquals(Caption.Note("stretch"), look.caption)
        assertTrue(look.amber)
        assertTrue("Claude still waits, so the !! stays", look.exclaim)
    }

    @Test
    fun listeningShowsWhatItHears() {
        val look = Mood.decide(sitting.copy(listening = true, heard = "what time"))
        assertEquals(Caption.Heard("what time"), look.caption)
        assertEquals("head tilted, listening", Expression.CURIOUS, look.expression)
        assertEquals(Light.FULL, look.light)
        assertFalse(look.amber)
    }

    @Test
    fun listeningTakesTheCaptionButKeepsTheAlert() {
        val look = Mood.decide(
            sitting.copy(listening = true, heard = "", claude = Summary(State.NEEDS_YOU, "x", 0, false)),
        )
        assertEquals(Caption.Heard(""), look.caption)
        assertTrue("still amber", look.amber)
        assertTrue("still !!", look.exclaim)
    }

    @Test
    fun aReplyShowsUntilSomethingMoreUrgent() {
        assertEquals(Caption.Reply("It's 3:45 PM."), Mood.decide(sitting.copy(reply = "It's 3:45 PM.")).caption)
        assertEquals(
            Caption.Note("stretch"),
            Mood.decide(sitting.copy(reply = "It's 3:45 PM.", note = "stretch")).caption,
        )
        assertEquals(Light.FULL, Mood.decide(away(Mood.SLEEP_MS).copy(reply = "Good night.")).light)
    }

    @Test
    fun toldToSleepItSleepsUntilSomethingNeedsYou() {
        val asleep = Mood.decide(sitting.copy(voiceAsleep = true))
        assertEquals(Expression.CLOSED, asleep.expression)
        assertEquals(Light.MIN, asleep.light)
        val woken = Mood.decide(sitting.copy(voiceAsleep = true, claude = Summary(State.NEEDS_YOU, "x", 0, false)))
        assertEquals(Expression.WIDE, woken.expression)
        assertTrue(woken.amber)
    }

    @Test
    fun exclaimOnlyWhileClaudeWaitsForYou() {
        assertTrue(Mood.decide(sitting.copy(claude = Summary(State.NEEDS_YOU, "x", 0, false))).exclaim)
        assertTrue(Mood.decide(away(Mood.SLEEP_MS).copy(claude = Summary(State.NEEDS_YOU, "x", 0, false))).exclaim)
        assertFalse("a note alone", Mood.decide(sitting.copy(note = "stretch")).exclaim)
        assertFalse(Mood.decide(sitting.copy(claude = Summary(State.DONE, "x", 0, false))).exclaim)
        assertFalse(Mood.decide(sitting.copy(claude = Summary(State.WORKING, "x", 0, false))).exclaim)
        assertFalse("a knock", Mood.decide(sitting.copy(knockAgoMs = 0)).exclaim)
        assertFalse(Mood.decide(sitting).exclaim)
    }

    @Test
    fun doneIsHappyUntilSeenThenJustACaption() {
        val unseen = Mood.decide(sitting.copy(claude = Summary(State.DONE, "node01", 0, seen = false)))
        assertEquals(Expression.HAPPY, unseen.expression)
        assertEquals(Caption.Done("node01", 0), unseen.caption)
        assertFalse(unseen.amber)
        val seen = Mood.decide(sitting.copy(claude = Summary(State.DONE, "node01", 0, seen = true)))
        assertEquals(Expression.NEUTRAL, seen.expression)
        assertEquals(Caption.Done("node01", 0), seen.caption)
    }

    @Test
    fun workingLooksTowardThePc() {
        val look = Mood.decide(sitting.copy(claude = Summary(State.WORKING, "node01", 0, false)))
        assertEquals(Expression.FOCUSED, look.expression)
        val gaze = look.gaze as Gaze.Mostly
        assertEquals(Mood.PC_SIDE > 0, gaze.x > 0)
        assertEquals("glances back at you", 0.3f, gaze.glanceX)
        assertNull("nothing to say while it works", look.caption)
        assertTrue(Mood.decide(away(5_000).copy(claude = Summary(State.WORKING, "x", 0, false))).gaze is Gaze.At)
    }

    @Test
    fun knockStartlesEvenAsleep() {
        val look = Mood.decide(away(Mood.SLEEP_MS).copy(knockAgoMs = 100))
        assertEquals(Expression.WIDE, look.expression)
        assertTrue(look.startled)
        assertFalse(Mood.decide(away(Mood.SLEEP_MS).copy(knockAgoMs = Mood.KNOCK_MS)).startled)
    }

    @Test
    fun petIsHappy() {
        assertEquals(Expression.HAPPY, Mood.decide(sitting.copy(petAgoMs = 0)).expression)
        assertEquals(Expression.NEUTRAL, Mood.decide(sitting.copy(petAgoMs = Mood.PET_MS)).expression)
    }

    @Test
    fun greetsOnlyAfterARealAbsence() {
        val back = sitting.copy(presentForMs = 300, lastAbsenceMs = Mood.SEARCH_MS)
        assertEquals(Expression.WIDE, Mood.decide(back).expression)
        assertEquals(Expression.NEUTRAL, Mood.decide(back.copy(lastAbsenceMs = 30_000)).expression)
        assertEquals(Expression.NEUTRAL, Mood.decide(back.copy(presentForMs = Mood.GREET_MS)).expression)
    }
}
