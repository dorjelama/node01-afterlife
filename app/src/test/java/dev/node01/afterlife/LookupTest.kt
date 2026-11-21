package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupTest {

    private val nepal = "Nepal, officially the Federal Democratic Republic of Nepal, is a landlocked country in South Asia. " +
        "It is mainly situated in the Himalayas. Nepal borders China to the north and India to the south. " +
        "Kathmandu is the nation's capital and its largest city."
    private val kathmandu = "Kathmandu is the capital and largest city of Nepal. It lies in the Kathmandu Valley."

    @Test
    fun searchesForTheSubjectNotTheQuestion() {
        assertEquals("harry potter", Lookup.topic("who wrote harry potter"))
        assertEquals("mount everest", Lookup.topic("How tall is Mount Everest?"))
        assertEquals("joining words stay", "capital of nepal", Lookup.topic("what is the capital of Nepal"))
        assertEquals("all question words: the question as it was", "what is it", Lookup.topic("what is it"))
    }

    @Test
    fun theOpeningSentenceThenTheOneClosestToTheQuestion() {
        val notes = Lookup.pick("what is the capital of Nepal", listOf(nepal, kathmandu))!!
        assertEquals(
            "Nepal, officially the Federal Democratic Republic of Nepal, is a landlocked country in South Asia. " +
                "Kathmandu is the capital and largest city of Nepal.",
            notes,
        )
    }

    @Test
    fun everydayWordsFindTheArticlesWords() {
        val everest = "Mount Everest is Earth's highest mountain above sea level. It lies in the Himalayas. " +
            "The China-Nepal border runs across its summit point. " +
            "Its height was most recently established in 2020 as 8,848.86 m. It attracts many climbers."
        val notes = Lookup.pick("how tall is mount everest", listOf(everest))!!
        assertTrue(notes, notes.startsWith("Mount Everest is Earth's highest mountain"))
        assertTrue(notes, notes.contains("8,848.86 m"))
    }

    @Test
    fun initialsDoNotEndASentence() {
        val potter = "Harry Potter is a series of seven fantasy novels written by British author J. K. Rowling. " +
            "The novels chronicle the lives of a young wizard."
        assertEquals(
            "Harry Potter is a series of seven fantasy novels written by British author J. K. Rowling.",
            Lookup.pick("who wrote harry potter", listOf(potter)),
        )
    }

    @Test
    fun nothingInCommonIsNothingFound() {
        assertNull(Lookup.pick("how tall is mount everest", listOf(kathmandu)))
        assertNull(Lookup.pick("what is it", listOf(nepal)))
        assertNull(Lookup.pick("capital of Nepal", emptyList()))
    }
}
