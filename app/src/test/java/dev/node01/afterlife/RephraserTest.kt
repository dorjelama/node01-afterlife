package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RephraserTest {

    @Test
    fun keepsTheFirstLineTrimmed() {
        assertEquals("what time is it", Rephraser.clean("  what time is it  \nmore"))
        assertEquals("what time is it", Rephraser.clean("\n\n\"what time is it\""))
    }

    @Test
    fun unknownIsNothing() {
        assertNull(Rephraser.clean("UNKNOWN"))
        assertNull(Rephraser.clean("unknown\n"))
        assertNull(Rephraser.clean(""))
        assertNull(Rephraser.clean("  \n "))
    }

    @Test
    fun questionsAreForTheWebNotTheParser() {
        assertTrue(Rephraser.asks(Rephraser.clean("ASK\n")!!))
        assertTrue(Rephraser.asks("ask: who won the world cup"))
        assertFalse(Rephraser.asks("what time is it"))
        assertFalse("not a word starting with ask", Rephraser.asks("asking for a friend"))
    }

    @Test
    fun capsALongRambling() {
        assertEquals(200, Rephraser.clean("x".repeat(500))!!.length)
    }

    // The model copies what it is shown, so it must only be shown phrases NODE understands.
    @Test
    fun everyCommandItIsToldParses() {
        for (c in Rephraser.COMMANDS) {
            assertNotEquals(c, Command.Unknown, Assistant.parse(c))
        }
    }

    @Test
    fun everyExampleGetsPastTheKeywordsAndParsesAfter() {
        for ((said, heard) in Rephraser.EXAMPLES) {
            assertEquals("the keywords already catch \"$said\"", Command.Unknown, Assistant.parse(said))
            when {
                heard == "UNKNOWN" -> assertNull(heard, Rephraser.clean(heard))
                heard == "ASK" -> assertTrue(Rephraser.asks(Rephraser.clean(heard)!!))
                else -> {
                    assertEquals(heard, Rephraser.clean(heard))
                    assertNotEquals(heard, Command.Unknown, Assistant.parse(heard))
                }
            }
        }
        assertEquals(Command.Unknown, Assistant.parse(Rephraser.WARM_UP))
    }
}
