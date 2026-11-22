package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ActivitiesTest {

    private val day = LocalDate.of(2026, 11, 22)
    private val cupUp = listOf(Activities.Seen("cup", 0.4f))

    @Test
    fun aCupRaisedForASecondIsADrink() {
        val a = Activities().apply { watched = setOf(Doing.DRINK) }
        assertNull(a.see(cupUp, 0L, day))
        assertEquals(Doing.DRINK to 1, a.see(cupUp, 1_000L, day))
        // Still drinking: not a second drink.
        assertNull(a.see(cupUp, 2_000L, day))
        // Ten minutes on, the next one.
        assertNull(a.see(cupUp, 10 * 60_000L + 1_000L, day))
        assertEquals(Doing.DRINK to 2, a.see(cupUp, 10 * 60_000L + 2_000L, day))
    }

    @Test
    fun aCupOnTheDeskIsNoDrink() {
        val a = Activities().apply { watched = setOf(Doing.DRINK) }
        val low = listOf(Activities.Seen("bottle", 0.9f))
        for (t in 0L..10_000L step 1_000L) assertNull(a.see(low, t, day))
    }

    @Test
    fun onlyWhatIsAllowed() {
        val a = Activities()
        for (t in 0L..5_000L step 1_000L) assertNull(a.see(cupUp, t, day))
        a.watched = setOf(Doing.PHONE)
        for (t in 0L..5_000L step 1_000L) assertNull(a.see(cupUp, t, day))
    }

    @Test
    fun aPhoneHeldForTwentySecondsAllowingAMissedFrame() {
        val a = Activities().apply { watched = setOf(Doing.PHONE) }
        val phone = listOf(Activities.Seen("cell phone", 0.7f))
        var got: Pair<Doing, Int>? = null
        for (t in 0L..20_000L step 1_000L) got = a.see(if (t == 7_000L) emptyList() else phone, t, day) ?: got
        assertEquals(Doing.PHONE to 1, got)
    }

    @Test
    fun putDownForAWhileStartsAgain() {
        val a = Activities().apply { watched = setOf(Doing.PHONE) }
        val phone = listOf(Activities.Seen("cell phone", 0.5f))
        for (t in 0L..15_000L step 1_000L) assertNull(a.see(phone, t, day))
        // Gone longer than the gap, or simply not looked at while you were away.
        for (t in 16_000L..20_000L step 1_000L) assertNull(a.see(emptyList(), t, day))
        assertNull(a.see(phone, 60_000L, day))
    }

    @Test
    fun countsStartAgainEachDay() {
        val a = Activities().apply { watched = setOf(Doing.DRINK) }
        a.see(cupUp, 0L, day)
        assertEquals(Doing.DRINK to 1, a.see(cupUp, 1_000L, day))
        a.see(cupUp, 11 * 60_000L, day.plusDays(1))
        assertEquals(Doing.DRINK to 1, a.see(cupUp, 11 * 60_000L + 1_000L, day.plusDays(1)))
    }

    @Test
    fun keptAsNames() {
        assertEquals(setOf(Doing.DRINK, Doing.READING), Activities.decode("DRINK, READING,nonsense,"))
        assertEquals(emptySet<Doing>(), Activities.decode(null))
        assertEquals("DRINK,PHONE", Activities.encode(linkedSetOf(Doing.DRINK, Doing.PHONE)))
    }
}
