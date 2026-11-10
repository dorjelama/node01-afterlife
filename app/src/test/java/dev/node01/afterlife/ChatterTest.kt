package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatterTest {

    /** Always picks the first line, so the words are predictable. */
    private val chatter = Chatter { 0 }

    @Test
    fun saysSomething() {
        assertEquals("Hi!", chatter.line(Moment.HELLO, Lang.EN, 0))
        assertEquals("नमस्ते!", Chatter { 0 }.line(Moment.HELLO, Lang.NE, 0))
    }

    @Test
    fun keepsAGapBetweenUnpromptedLines() {
        assertNotNull(chatter.line(Moment.KNOCK, Lang.EN, 0))
        assertNull("too soon after the knock", chatter.line(Moment.HELLO, Lang.EN, Chatter.GAP_MS - 1))
        assertNotNull(chatter.line(Moment.HELLO, Lang.EN, Chatter.GAP_MS))
    }

    @Test
    fun claudeIsWorthSayingEvenRightAfterSomethingElse() {
        assertNotNull(chatter.line(Moment.KNOCK, Lang.EN, 0))
        assertEquals("Hey! Claude needs you.", chatter.line(Moment.NEEDS_YOU, Lang.EN, 1_000))
        assertEquals("All done!", chatter.line(Moment.DONE, Lang.EN, 2_000))
    }

    @Test
    fun eachMomentHasItsOwnCooldown() {
        assertNotNull(chatter.line(Moment.KNOCK, Lang.EN, 0))
        assertNull(chatter.line(Moment.KNOCK, Lang.EN, Moment.KNOCK.cooldownMs - 1))
        assertNotNull(chatter.line(Moment.KNOCK, Lang.EN, Moment.KNOCK.cooldownMs))
        assertNotNull(chatter.line(Moment.NEEDS_YOU, Lang.EN, 0))
        assertNull("a second session asking at once", chatter.line(Moment.NEEDS_YOU, Lang.EN, 5_000))
    }

    @Test
    fun neverTheSameWordsTwiceRunning() {
        val first = chatter.line(Moment.KNOCK, Lang.EN, 0)
        val second = chatter.line(Moment.KNOCK, Lang.EN, Moment.KNOCK.cooldownMs)
        assertNotEquals(first, second)
    }

    @Test
    fun everyMomentHasLinesInBothLanguages() {
        for (lang in Lang.values()) {
            for (moment in Moment.values()) {
                assertTrue("$lang $moment", Chatter.LINES.getValue(lang).getValue(moment).isNotEmpty())
            }
        }
    }
}
