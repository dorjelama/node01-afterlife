package dev.node01.afterlife

import dev.node01.afterlife.Assistant.parse
import dev.node01.afterlife.ClaudeSessions.State
import dev.node01.afterlife.ClaudeSessions.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AssistantTest {

    private val min = 60_000L
    private val sec = 1_000L

    @Test
    fun understandsEnglish() {
        val cases = mapOf(
            "What time is it?" to Command.Time,
            "What's the date today" to Command.Date,
            "nepali date" to Command.Date,
            "Set a timer for 5 minutes" to Command.Timer(5 * min),
            "timer twenty five minutes" to Command.Timer(25 * min),
            "timer 90 seconds" to Command.Timer(90 * sec),
            "timer five" to Command.Timer(5 * min),
            "set a timer" to Command.Timer(null),
            "timer for an hour" to Command.Timer(60 * min),
            "start a focus session" to Command.Focus(Assistant.FOCUS_MS),
            "focus for 50 minutes" to Command.Focus(50 * min),
            "pomodoro" to Command.Focus(Assistant.FOCUS_MS),
            "cancel the timer" to Command.CancelTimer,
            "stop focus" to Command.CancelTimer,
            "clear the timer" to Command.CancelTimer,
            "Remind me in 10 minutes to stretch" to Command.Remind(10 * min, "stretch"),
            "remind me to drink water in half an hour" to Command.Remind(30 * min, "drink water"),
            "remind me to call mom" to Command.Note("call mom"),
            "take a note buy milk" to Command.Note("buy milk"),
            "Note: buy milk" to Command.Note("buy milk"),
            "remember to water the plants" to Command.Note("water the plants"),
            "what is Claude doing" to Command.ClaudeStatus,
            "what's cloud doing" to Command.ClaudeStatus,
            "go to sleep" to Command.Sleep,
            "good night" to Command.Sleep,
            "wake up" to Command.Wake,
            "okay" to Command.Dismiss,
            "dismiss" to Command.Dismiss,
            "thank you" to Command.Thanks,
            "help" to Command.Help,
            "what can you do" to Command.Help,
            "what's your address" to Command.Address,
            "speak Nepali" to Command.Language(Lang.NE),
            "english" to Command.Language(Lang.EN),
            "blah blah" to Command.Unknown,
            "" to Command.Unknown,
        )
        for ((said, expected) in cases) assertEquals(said, expected, parse(said))
    }

    @Test
    fun understandsNepali() {
        val cases = mapOf(
            "कति बज्यो" to Command.Time,
            "समय कति भयो" to Command.Time,
            "आज कति गते" to Command.Date,
            "पाँच मिनेटको टाइमर" to Command.Timer(5 * min),
            "१० मिनेटको टाइमर राख" to Command.Timer(10 * min),
            "टाइमर रद्द गर" to Command.CancelTimer,
            "फोकस" to Command.Focus(Assistant.FOCUS_MS),
            "दस मिनेटपछि पानी पिउन सम्झाउनु" to Command.Remind(10 * min, "पानी पिउन"),
            "नोट दूध किन्नु" to Command.Note("दूध किन्नु"),
            "क्लाउड के गर्दैछ" to Command.ClaudeStatus,
            "सुत" to Command.Sleep,
            "धन्यवाद" to Command.Thanks,
            "अंग्रेजीमा बोल" to Command.Language(Lang.EN),
            "नेपालीमा बोल" to Command.Language(Lang.NE),
        )
        for ((said, expected) in cases) assertEquals(said, expected, parse(said))
    }

    @Test
    fun nepaliIsIsNotSix() {
        // "छ" means "is" as well as six: without a unit it is never a length of time.
        assertEquals(Command.Note("पानी पिउनु छ भनेर"), parse("पानी पिउनु छ भनेर सम्झाउ"))
        assertEquals(6 * min, Assistant.duration("छ मिनेट"))
    }

    @Test
    fun tellsTheLanguagesApart() {
        assertEquals(Lang.EN, Assistant.langOf("what time is it"))
        assertEquals(Lang.NE, Assistant.langOf("कति बज्यो"))
        assertEquals(Lang.NE, Assistant.langOf("Claude के गर्दैछ"))
    }

    @Test
    fun saysTheTime() {
        val afternoon = facts(LocalDateTime.of(2026, 11, 10, 15, 45))
        assertEquals("It's 3:45 PM.", Assistant.reply(Command.Time, Lang.EN, afternoon))
        assertEquals("अहिले दिउँसो ३:४५ बजेको छ।", Assistant.reply(Command.Time, Lang.NE, afternoon))
        val morning = facts(LocalDateTime.of(2026, 11, 10, 7, 5))
        assertEquals("अहिले बिहान ७:०५ बजेको छ।", Assistant.reply(Command.Time, Lang.NE, morning))
    }

    @Test
    fun saysTheDateInBothCalendars() {
        val f = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        val bs = Bs.fromAd(LocalDate.of(2026, 11, 10))!!
        assertEquals(2083, bs.year)
        assertEquals("10 November 2026 is in Kartik", 7, bs.month)
        assertEquals(
            "Tuesday, November 10. In Bikram Sambat, Kartik ${bs.day}, 2083.",
            Assistant.reply(Command.Date, Lang.EN, f),
        )
        assertEquals("आज २०८३ कार्तिक ${Bs.neDigits(bs.day)} गते, मंगलबार।", Assistant.reply(Command.Date, Lang.NE, f))
    }

    @Test
    fun answersAboutClaude() {
        val base = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        assertEquals("No Claude sessions right now.", Assistant.reply(Command.ClaudeStatus, Lang.EN, base))
        assertEquals(
            "Claude needs you in node01. And 1 more.",
            Assistant.reply(Command.ClaudeStatus, Lang.EN, base.copy(claude = Summary(State.NEEDS_YOU, "node01", 1, false))),
        )
        assertEquals(
            "Claude node01 मा काम गर्दैछ।",
            Assistant.reply(Command.ClaudeStatus, Lang.NE, base.copy(claude = Summary(State.WORKING, "node01", 0, false))),
        )
    }

    @Test
    fun confirmsWhatItDid() {
        val f = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        assertEquals("Timer set for 5 minutes.", Assistant.reply(Command.Timer(5 * min), Lang.EN, f))
        assertEquals("५ मिनेटको टाइमर राखेँ।", Assistant.reply(Command.Timer(5 * min), Lang.NE, f))
        assertEquals("No timer is running.", Assistant.reply(Command.CancelTimer, Lang.EN, f))
        assertEquals("Timer cancelled.", Assistant.reply(Command.CancelTimer, Lang.EN, f.copy(timerRunning = true)))
        assertEquals(
            "OK. In 10 minutes, I'll remind you: stretch.",
            Assistant.reply(Command.Remind(10 * min, "stretch"), Lang.EN, f),
        )
        assertTrue(Assistant.reply(Command.Unknown, Lang.EN, f).startsWith("Sorry"))
    }

    @Test
    fun durations() {
        assertEquals("1 minute 30 seconds", Assistant.durationEn(90 * sec))
        assertEquals("1 hour 30 minutes", Assistant.durationEn(90 * min))
        assertEquals("२५ मिनेट", Assistant.durationNe(25 * min))
        assertEquals(30 * min, Assistant.duration("half an hour"))
        assertEquals(null, Assistant.duration("a timer please"))
    }

    private fun facts(now: LocalDateTime) =
        Assistant.Facts(now = now, claude = null, timerRunning = false, noteDue = false, address = null)
}
