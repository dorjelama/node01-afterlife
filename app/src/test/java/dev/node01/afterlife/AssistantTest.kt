package dev.node01.afterlife

import dev.node01.afterlife.Assistant.parse
import dev.node01.afterlife.ClaudeSessions.State
import dev.node01.afterlife.ClaudeSessions.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun understandsNepaliWrittenInEnglishLetters() {
        val cases = mapOf(
            "kati bajyo" to Command.Time,
            "aaja kati gate" to Command.Date,
            "paanch minute ko timer" to Command.Timer(5 * min),
            "dhanyabad" to Command.Thanks,
            "suta" to Command.Sleep,
            "madat" to Command.Help,
        )
        for ((said, expected) in cases) assertEquals(said, expected, parse(said))
        // Short words match whole words only.
        assertEquals("sut inside sutton", Command.Unknown, parse("sutton road"))
        assertEquals("gate inside navigate", Command.Unknown, parse("navigate home"))
        assertEquals(Command.Unknown, parse("blah"))
    }

    @Test
    fun answersAHearingCheck() {
        for (said in listOf("test", "testing", "can you hear me", "सुन्छौ")) {
            assertEquals(said, Command.Hello, parse(said))
        }
        // Hands-free: "Hey NODE, test" reaches the parser as "test".
        assertEquals(Command.Hello, parse(Assistant.wake("hey node test")!!))
        assertEquals("I can hear you!", Assistant.reply(Command.Hello, Lang.EN, facts(LocalDateTime.of(2026, 11, 10, 9, 0))))
        // Not in the middle of other words.
        assertEquals(Command.Unknown, parse("contested results"))
    }

    @Test
    fun greetsBack() {
        val cases = mapOf(
            "hello" to Greeting.HELLO,
            "hi" to Greeting.HELLO,
            "hey there" to Greeting.HELLO,
            "नमस्ते" to Greeting.HELLO,
            "namaste" to Greeting.HELLO,
            "good morning" to Greeting.MORNING,
            "morning" to Greeting.MORNING,
            "शुभ प्रभात" to Greeting.MORNING,
            "good afternoon" to Greeting.AFTERNOON,
            "good evening buddy" to Greeting.EVENING,
            "शुभ साँझ" to Greeting.EVENING,
            "nice to meet you" to Greeting.NICE,
            "good to see you" to Greeting.NICE,
            "nice to see you" to Greeting.NICE,
            "I'm back" to Greeting.BACK,
            "how are you" to Greeting.HOW_ARE_YOU,
            "what's up" to Greeting.HOW_ARE_YOU,
            "तपाईंलाई कस्तो छ" to Greeting.HOW_ARE_YOU,
            "I'm fine" to Greeting.FINE,
            "doing well thanks" to Greeting.FINE,
            "म ठिक छु" to Greeting.FINE,
            "what's your name" to Greeting.NAME,
            "who are you" to Greeting.NAME,
            "bye" to Greeting.BYE,
            "see you later" to Greeting.BYE,
        )
        for ((said, kind) in cases) assertEquals(said, Command.Greet(kind), parse(said))
        // A command wins over the greeting around it.
        assertEquals(Command.Time, parse("hi, what time is it"))
        assertEquals(Command.Date, parse("good morning, what's the date today"))
        // "Good night" still puts it to sleep, and "ठिक छ" (OK) still dismisses.
        assertEquals(Command.Sleep, parse("good night"))
        assertEquals(Command.Dismiss, parse("ठिक छ"))
        // Not inside other words.
        assertEquals(Command.Unknown, parse("this is a high shelf"))
        assertEquals(Command.Unknown, parse("they lobbied"))
    }

    @Test
    fun greetsBackInKind() {
        // In kind, whatever the clock says: "good morning" at ten at night is still answered.
        val late = facts(LocalDateTime.of(2026, 11, 12, 22, 4))
        assertEquals("Good morning!", Assistant.reply(parse("good morning"), Lang.EN, late))
        assertEquals("Good afternoon!", Assistant.reply(parse("good afternoon"), Lang.EN, late))
        assertEquals("Good evening!", Assistant.reply(parse("good evening"), Lang.EN, late))
        assertEquals("शुभ प्रभात!", Assistant.reply(parse("शुभ प्रभात"), Lang.NE, late))
        assertEquals("Nice to see you too!", Assistant.reply(parse("nice to meet you"), Lang.EN, late))
        assertEquals("Welcome back!", Assistant.reply(parse("I'm back"), Lang.EN, late))
        assertEquals("I'm good, thanks! How are you?", Assistant.reply(Command.Greet(Greeting.HOW_ARE_YOU), Lang.EN, facts(LocalDateTime.of(2026, 11, 12, 9, 0))))
    }

    @Test
    fun handsFreeNeedsNoNameWhileYouFaceIt() {
        // Facing NODE: a command or a greeting is enough.
        assertEquals("what time is it", Assistant.handsFree("what time is it", facing = true))
        assertEquals("hello", Assistant.handsFree("hello", facing = true))
        // Talk that is not for it is never answered.
        assertNull(Assistant.handsFree("I'll call you back after lunch", facing = true))
        // Not facing it: only with its name.
        assertNull(Assistant.handsFree("what time is it", facing = false))
        assertEquals("what time is it", Assistant.handsFree("hey buddy what time is it", facing = false))
        // Its name on its own is a hello, in the language it was said in.
        assertEquals("hello", Assistant.handsFree("hey buddy", facing = false))
        assertEquals(Command.Greet(Greeting.HELLO), parse(Assistant.handsFree("हे बडी", facing = false)!!))
    }

    @Test
    fun hearsItsName() {
        assertEquals("what time is it", Assistant.wake("Hey NODE, what time is it?"))
        assertEquals("what time is it", Assistant.wake("Hey Buddy, what time is it?"))
        assertEquals("test", Assistant.wake("hey body test"))
        assertEquals("", Assistant.wake("ok buddy"))
        assertEquals("what's the date", Assistant.wake("buddy what's the date"))
        assertEquals("कति बज्यो", Assistant.wake("हे बडी कति बज्यो"))
        assertEquals("what time is it", Assistant.wake("hey nod what time is it"))
        assertEquals("what time is it", Assistant.wake("hey no what time is it"))
        assertEquals("test", Assistant.wake("hello know test"))
        assertEquals("set a timer for 5 minutes", Assistant.wake("ok node set a timer for 5 minutes"))
        assertEquals("", Assistant.wake("hey node"))
        assertEquals("what's the date", Assistant.wake("node what's the date"))
        assertEquals("so um what time is it", Assistant.wake("hey node so um what time is it"))
        assertEquals("कति बज्यो", Assistant.wake("हे नोड कति बज्यो"))
        assertEquals("", Assistant.wake("नोड"))
    }

    @Test
    fun ignoresEverythingElse() {
        for (s in listOf("what time is it", "take a note buy milk", "note: call mom", "the nodes are down",
            "everybody hey", "somebody told me", "my buddy is here",
            "anode", "nodejs is slow", "", "कति बज्यो")) {
            assertEquals(s, null, Assistant.wake(s))
        }
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
        // Never which project: the answer is shown on screen.
        assertEquals(
            "Claude needs you. And 1 more.",
            Assistant.reply(Command.ClaudeStatus, Lang.EN, base.copy(claude = Summary(State.NEEDS_YOU, "node01", 1, false))),
        )
        assertEquals(
            "Claude काम गर्दैछ।",
            Assistant.reply(Command.ClaudeStatus, Lang.NE, base.copy(claude = Summary(State.WORKING, "node01", 0, false))),
        )
        for (state in State.entries) {
            for (lang in Lang.entries) {
                val said = Assistant.reply(Command.ClaudeStatus, lang, base.copy(claude = Summary(state, "node01", 0, false)))
                assertFalse("$state $lang: $said", "node01" in said)
            }
        }
    }

    @Test
    fun understandsAClockTime() {
        assertEquals(Command.RemindAt(5, 30, false, "call mom"), parse("remind me at 5:30 to call mom"))
        assertEquals(Command.RemindAt(17, 30, true, "call mom"), parse("remind me at 5:30 pm to call mom"))
        assertEquals(Command.RemindAt(7, 0, false, "stretch"), parse("remind me at 7 to stretch"))
        assertEquals(Command.RemindAt(20, 15, true, "Reminder"), parse("remind me at 20:15"))
        assertEquals(Command.RemindAt(0, 30, true, "Reminder"), parse("remind me at 00:30"))
        // Noon and midnight, where the 12 has to flip.
        assertEquals(Command.RemindAt(12, 0, true, "lunch"), parse("remind me at 12 pm about lunch"))
        assertEquals(Command.RemindAt(0, 0, true, "Reminder"), parse("remind me at 12 am"))
        // Nepali, in both scripts, and romanized.
        assertEquals(Command.RemindAt(5, 0, false, "पानी पिउन"), parse("५ बजे पानी पिउन सम्झाउनु"))
        assertEquals(Command.RemindAt(5, 30, false, "पानी पिउन"), parse("पाँच:३० बजे पानी पिउन सम्झाउनु"))
        assertEquals(Command.RemindAt(7, 0, false, "Reminder"), parse("saat baje samjhau"))
        // Still a relative reminder when no clock time is named.
        assertEquals(Command.Remind(10 * min, "stretch"), parse("remind me in 10 minutes to stretch"))
        // "at the gym" is not a time.
        assertEquals(Command.Note("meet her at the gym"), parse("remind me to meet her at the gym"))
    }

    @Test
    fun understandsARepeatingNudge() {
        assertEquals(Command.RemindEvery(45 * min, "stretch"), parse("remind me to stretch every 45 minutes"))
        assertEquals(Command.RemindEvery(60 * min, "drink water"), parse("remind me to drink water every hour"))
        assertEquals(Command.RemindEvery(30 * min, "look away"), parse("remind me every 30 minutes to look away"))
        assertEquals(Command.RemindEvery(20 * min, "पानी पिउन"), parse("हरेक २० मिनेटमा पानी पिउन सम्झाउनु"))
        // Nothing absurd: a nudge is never oftener than once a minute.
        assertEquals(60 * sec, (parse("remind me to blink every 5 seconds") as Command.RemindEvery).everyMs)
        // A one-off reminder is still a one-off.
        assertEquals(Command.Remind(10 * min, "stretch"), parse("remind me in 10 minutes to stretch"))

        val f = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        assertEquals(
            "OK. Every 45 minutes while you're here: stretch.",
            Assistant.reply(Command.RemindEvery(45 * min, "stretch"), Lang.EN, f),
        )
        assertTrue(Assistant.reply(Command.RemindEvery(45 * min, "स्ट्रेच"), Lang.NE, f).contains("हरेक ४५ मिनेट"))
    }

    @Test
    fun understandsAnAlarm() {
        assertEquals(Command.Alarm(7, 0, false), parse("wake me at 7"))
        assertEquals(Command.Alarm(6, 30, true), parse("set an alarm for 6:30 am"))
        assertEquals(Command.Alarm(7, 0, false), parse("७ बजे उठाउ"))
        // "Wake up" with no hour is still just waking it up, and the time is still the time.
        assertEquals(Command.Wake, parse("wake up"))
        assertEquals(Command.Time, parse("कति बज्यो"))
        // An alarm with no hour at all is not an alarm.
        assertEquals(Command.Wake, parse("wake me"))

        val f = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        // Same rule as a reminder: a bare 7, heard at nine in the morning, is this evening.
        assertEquals(
            "Alarm set for 7:00 PM. Tap me to turn it off.",
            Assistant.reply(Command.Alarm(7, 0, false), Lang.EN, f),
        )
        // Say "am" for the morning, and it is tomorrow's.
        assertEquals(
            "Alarm set for 7:00 AM. Tap me to turn it off.",
            Assistant.reply(Command.Alarm(7, 0, true), Lang.EN, f),
        )
        assertTrue(Assistant.reply(Command.Alarm(7, 0, true), Lang.NE, f).startsWith("बिहान ७:०० बजे"))
        assertEquals("Wake up!", Assistant.wakeUp(Lang.EN))
    }

    @Test
    fun picksTheNextTimeTheClockReadsThat() {
        val morning = LocalDateTime.of(2026, 11, 10, 9, 0)
        // Said without am or pm, the sooner reading wins: 5 in the morning means this evening.
        assertEquals(LocalDateTime.of(2026, 11, 10, 17, 0), Assistant.dueAt(morning, 5, 0, exact = false))
        // Before either: this morning.
        assertEquals(
            LocalDateTime.of(2026, 11, 10, 5, 0),
            Assistant.dueAt(LocalDateTime.of(2026, 11, 10, 3, 0), 5, 0, exact = false),
        )
        // After both: tomorrow morning.
        assertEquals(
            LocalDateTime.of(2026, 11, 11, 5, 0),
            Assistant.dueAt(LocalDateTime.of(2026, 11, 10, 18, 0), 5, 0, exact = false),
        )
        // Said exactly: that hour, today if it is still coming.
        assertEquals(LocalDateTime.of(2026, 11, 10, 17, 30), Assistant.dueAt(morning, 17, 30, exact = true))
        assertEquals(
            LocalDateTime.of(2026, 11, 11, 8, 0),
            Assistant.dueAt(morning, 8, 0, exact = true),
        )
    }

    @Test
    fun confirmsAClockReminderInBothLanguages() {
        val f = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        assertEquals(
            "OK. At 5:30 PM, I'll remind you: call mom.",
            Assistant.reply(Command.RemindAt(5, 30, false, "call mom"), Lang.EN, f),
        )
        assertEquals(
            "हुन्छ, बेलुका ५:३० बजे सम्झाउँछु: फोन गर्न।",
            Assistant.reply(Command.RemindAt(5, 30, false, "फोन गर्न"), Lang.NE, f),
        )
        assertEquals("5:30 PM", Assistant.clockEn(17, 30))
        assertEquals("12:00 AM", Assistant.clockEn(0, 0))
        assertEquals("बिहान ९:०५", Assistant.clockNe(9, 5))
    }

    @Test
    fun answersAboutTheSessionWindow() {
        val base = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        assertEquals(Command.SessionUsage, Assistant.parse("how much of my session is left"))
        assertEquals(Command.SessionUsage, Assistant.parse("when does my claude session reset"))
        assertEquals(Command.SessionUsage, Assistant.parse("what's my usage"))
        // Asking what Claude is doing is still that, not the window.
        assertEquals(Command.ClaudeStatus, Assistant.parse("what is claude doing"))

        assertTrue(Assistant.reply(Command.SessionUsage, Lang.EN, base).startsWith("I haven't been told"))
        assertEquals(
            "Your session is 80% used. It resets in 2 hours 10 minutes.",
            Assistant.reply(Command.SessionUsage, Lang.EN, base.copy(usagePercent = 80, usageResetsInMs = 130 * min)),
        )
        // No reset time known: the percentage alone, with no dangling sentence.
        assertEquals(
            "Your session is 42% used.",
            Assistant.reply(Command.SessionUsage, Lang.EN, base.copy(usagePercent = 42)),
        )
        assertEquals(
            "तपाईंको सेसन ४२% प्रयोग भयो।",
            Assistant.reply(Command.SessionUsage, Lang.NE, base.copy(usagePercent = 42)),
        )
    }

    @Test
    fun warnsOnceAtEachUsageMark() {
        assertEquals(0, Assistant.usagePassed(0))
        assertEquals(0, Assistant.usagePassed(79))
        assertEquals(80, Assistant.usagePassed(80))
        assertEquals(80, Assistant.usagePassed(94))
        assertEquals(95, Assistant.usagePassed(95))
        assertEquals(95, Assistant.usagePassed(100))
        assertEquals("Session 95% used.", Assistant.usageWarning(95, Lang.EN))
        assertEquals("सेसन ८०% प्रयोग भयो।", Assistant.usageWarning(80, Lang.NE))
    }

    @Test
    fun confirmsWhatItDid() {
        val f = facts(LocalDateTime.of(2026, 11, 10, 9, 0))
        assertEquals("Timer set for 5 minutes.", Assistant.reply(Command.Timer(5 * min), Lang.EN, f))
        assertEquals("५ मिनेटको टाइमर राखेँ।", Assistant.reply(Command.Timer(5 * min), Lang.NE, f))
        assertEquals("There's nothing to cancel.", Assistant.reply(Command.CancelTimer, Lang.EN, f))
        assertEquals("Cancelled.", Assistant.reply(Command.CancelTimer, Lang.EN, f.copy(timerRunning = true)))
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
