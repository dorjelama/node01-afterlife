package dev.node01.afterlife

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** The two languages NODE understands and speaks. */
enum class Lang(val tag: String) {
    EN("en-US"),
    NE("ne-NP"),
}

/** What a spoken sentence asks NODE to do. */
sealed interface Command {
    data object Time : Command
    data object Date : Command

    /** Null when no length was heard: NODE asks for one. */
    data class Timer(val ms: Long?) : Command
    data class Focus(val ms: Long) : Command
    data object CancelTimer : Command
    data class Remind(val ms: Long, val text: String) : Command

    /**
     * "Remind me at half past five." [hour] is 0..23 when [exact] -- am, pm or
     * an hour past twelve was said -- and 1..12 when it was not, for
     * [Assistant.dueAt] to settle against the clock.
     *
     * shortcut: today or tomorrow only, whichever comes first; no "on Friday"
     * or "tomorrow at 9". Upgrade when someone asks for a second day.
     */
    data class RemindAt(val hour: Int, val minute: Int, val exact: Boolean, val text: String) : Command

    /** "Wake me at seven": like [RemindAt], but it keeps ringing until you tap it off. */
    data class Alarm(val hour: Int, val minute: Int, val exact: Boolean) : Command

    /** "Remind me to stretch every 45 minutes": a nudge, only while you are at the desk. */
    data class RemindEvery(val everyMs: Long, val text: String) : Command
    data class Note(val text: String) : Command
    data object ClaudeStatus : Command

    /** "How much of my session is left?", "when does my session reset?" */
    data object SessionUsage : Command

    /** "What's 17 times 23?" [result] is NaN for a division by zero. */
    data class Calc(val result: Double) : Command

    /** "How many miles is 10 km?" -- [value] is already in [to]. */
    data class Convert(val value: Double, val to: Measure) : Command

    /**
     * "What's Mangsir 26 in English?" -- one date read in the other calendar.
     * [nepali] is which calendar the month named belongs to; the year is
     * whichever one we are in now.
     */
    data class DateIn(val month: Int, val day: Int, val nepali: Boolean) : Command

    /** "Say you're a good robot" -- repeated back word for word, and a quick test of the voice. */
    data class Echo(val text: String) : Command

    /** "Spell Kathmandu." */
    data class Spell(val text: String) : Command

    /** "Flip a coin." */
    data object Coin : Command

    /** "Roll a dice", "roll a twenty sided dice". */
    data class Dice(val sides: Int) : Command

    /** "Pick a number between 1 and 10." */
    data class Pick(val from: Int, val to: Int) : Command
    data object Sleep : Command
    data object Wake : Command
    data object Dismiss : Command
    data object Thanks : Command
    data object Help : Command

    /** "Can you hear me?" -- a quick check that voice works. */
    data object Hello : Command

    /** Small talk: "hi", "good morning", "how are you", "bye". */
    data class Greet(val kind: Greeting) : Command
    data object Address : Command
    data class Language(val lang: Lang) : Command
    data object Unknown : Command
}

/** The units NODE converts between, in pairs: each [family] has exactly two. */
enum class Measure(val family: Int) {
    KM(0), MILE(0),
    KG(1), LB(1),
    C(2), F(2),

    /** Nepali and Indian rupees, pegged at 1.6, so this needs no network and never goes stale. */
    NPR(3), INR(3),
    ;

    val other: Measure get() = entries.first { it.family == family && it != this }
}

enum class Greeting {
    /** "Hi", "hello", "namaste", or its name on its own. */
    HELLO,

    /** "Good morning" and the rest are answered in kind, whatever the clock says. */
    MORNING,
    AFTERNOON,
    EVENING,
    HOW_ARE_YOU,

    /** "I'm fine": the answer to NODE's own "how are you?". */
    FINE,

    /** "Nice to meet you", "good to see you". */
    NICE,

    /** "I'm back". */
    BACK,
    NAME,
    BYE,
}

/**
 * NODE's voice assistant, minus the microphone and the speaker: turns what
 * was heard into a [Command], and a command into the words to say back.
 * No AI and no network -- a fixed set of things, in English and Nepali,
 * matched by keyword. Pure, so it is unit-tested sentence by sentence.
 */
object Assistant {

    /** Facts a reply may need, gathered by the caller before acting on the command. */
    data class Facts(
        val now: LocalDateTime,
        val claude: ClaudeSessions.Summary?,
        val timerRunning: Boolean,
        val noteDue: Boolean,
        val address: String?,
        /** Claude's 5-hour window, as the PC's status line last reported it. */
        val usagePercent: Int? = null,
        /** How long until that window resets, or null if it was never said. */
        val usageResetsInMs: Long? = null,
        /** Where a coin toss or a dice roll comes from; seeded in tests. */
        val random: Random = Random.Default,
    )

    const val FOCUS_MS = 25 * 60_000L

    /** A nudge no oftener than this, however short an interval was asked for. */
    private const val MIN_EVERY_MS = 60_000L
    private const val MAX_EVERY_MS = 12 * 3_600_000L
    private const val SECOND = 1_000L
    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L

    /** Devanagari in the text means it was Nepali. */
    fun langOf(text: String): Lang = if (text.any { it in 'ऀ'..'ॿ' }) Lang.NE else Lang.EN

    /**
     * For hands-free listening: what was said after "Hey Buddy" (or "Hey NODE"),
     * or null if the wake words are not there. "Hey Buddy, what time is it"
     * gives "what time is it"; a bare "Hey Buddy" gives "". Allows for how the recognizer hears the
     * name -- "nod", "note", "nodes" -- but a lone "note" is not enough, since
     * that is also a command.
     */
    fun wake(heard: String): String? {
        val t = normalise(heard)
        val m = WAKE_EN.find(t) ?: WAKE_NE.find(t) ?: return null
        return t.substring(m.range.last + 1).trim()
    }

    /**
     * Hands-free: what to act on in a sentence heard while NODE listens in the
     * background, or null to let it pass unanswered and unshown.
     *
     * With its name, the rest of the sentence; its name alone is a hello. No
     * name is needed when you are [facing] it, but then only a sentence that is
     * a command or a greeting counts: talk that is not for NODE is never answered.
     */
    fun handsFree(heard: String, facing: Boolean): String? {
        val rest = wake(heard)
        if (rest != null) return rest.ifBlank { if (langOf(heard) == Lang.NE) "नमस्ते" else "hello" }
        return if (facing && parse(heard) != Command.Unknown) heard else null
    }

    // "a node" is how "hey node" is sometimes heard; "a note" is not, as in "take a note".
    private val WAKE_EN =
        Regex(
            """(^|\s)((hey|hi|hello|ok|okay|hay) (buddy|buddie|budy|body|node|nodes|nod|note|know'd|no|know|knowed|noad)|""" +
                """a (node|nod)|^buddy|^node)(\s|$)""",
        )
    private val WAKE_NE = Regex("""(^|\s)(हे |ए )?(बडी|बड्डी|नोड|नोड्)(\s|$)""")

    fun parse(heard: String): Command {
        val t = normalise(heard)
        if (t.isBlank()) return Command.Unknown
        val greeting = greeting(t)
        val clock by lazy { clockIn(t) }
        val sum by lazy { calc(t) }
        val converted by lazy { convert(t) }
        val spelled by lazy { said(t, SPELL_EN, SPELL_NE) }
        val echoed by lazy { said(t, ECHO_EN, ECHO_NE) }
        val dated by lazy { dateIn(t) }
        fun has(vararg words: String) = words.any { t.contains(it) }

        /** Whole words only: short romanized Nepali like "sut" must not match inside English. */
        fun word(vararg words: String) = words.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }

        return when {
            // First of all, so that what follows is taken as words and not as
            // an instruction: "say go to sleep" is a thing to say, not bedtime.
            spelled != null -> Command.Spell(spelled!!)
            echoed != null -> Command.Echo(echoed!!)

            // Before the language switch below: "... in English" is the whole
            // point of the question, not a request to change language.
            dated != null -> dated!!

            // Language first, but "nepali date" is a date, not a request to switch.
            Regex("""\b(speak|talk|use|switch to|in) nepali\b""").containsMatchIn(t) || t == "nepali" ||
                (has("नेपालीमा") && has("बोल")) -> Command.Language(Lang.NE)
            Regex("""\b(speak|talk|use|switch to|in) english\b""").containsMatchIn(t) || t == "english" ||
                has("अंग्रेजी", "अङ्ग्रेजी", "इंग्लिस", "इङ्ग्लिस") -> Command.Language(Lang.EN)

            (has("cancel", "stop", "clear") && has("timer", "focus", "pomodoro", "reminder")) ||
                (has("रद्द", "बन्द", "रोक") && has("टाइमर", "फोकस")) -> Command.CancelTimer

            // Before the time and "wake up" below: with an hour, this is an alarm.
            (has("alarm", "अलार्म", "alaram") || has("wake me", "उठाउ", "uthau")) && clock != null ->
                Command.Alarm(clock!!.hour, clock!!.minute, clock!!.exact)

            // Online recognition often writes Nepali in English letters: "kati bajyo".
            has("remind", "सम्झाउ", "सम्झना", "याद दिला", "samjha", "samjhau", "yaad dila", "yad dila") -> remind(t)

            has("timer", "टाइमर", "taimar") -> Command.Timer(duration(t))
            has("focus", "pomodoro", "फोकस", "पोमोडोरो") -> Command.Focus(duration(t) ?: FOCUS_MS)

            Regex("""^(take a note|note|write down|remember)\b""").containsMatchIn(t) || has("नोट") ->
                Command.Note(noteText(t))

            // Before Claude's own state: "when does my Claude session reset" asks about the window.
            has("session", "usage", "quota", "सेसन", "कोटा") -> Command.SessionUsage

            has("claude", "cloud", "clod", "क्लड", "क्लाउड", "क्लोड") -> Command.ClaudeStatus

            // Before the time: "17 times 23" has "time" in it.
            sum != null -> Command.Calc(sum!!)
            converted != null -> converted!!

            has("coin", "heads or tails", "सिक्का") -> Command.Coin
            has("dice", "die", "पासा") || word("roll") -> Command.Dice(numbersIn(t).firstOrNull()?.toInt() ?: 6)
            has("pick a number", "random number", "choose a number", "संख्या छान", "नम्बर छान") ->
                numbersIn(t).let { Command.Pick(it.getOrElse(0) { 1 }.toInt(), it.getOrElse(1) { 10 }.toInt()) }

            has("time", "o'clock", "समय", "बज्यो", "बजे", "टाइम", "bajyo", "baje", "kati baj") ||
                word("samay", "samaya") -> Command.Time
            has("date", "what day", "today", "गते", "मिति", "तारिख", "कुन बार", "आज") ||
                word("gate", "miti", "aaja", "aja", "kun bar", "kun baar") -> Command.Date

            has("sleep", "good night", "goodnight", "सुत", "शुभरात्री", "शुभ रात्री", "subharatri", "shubharatri") ||
                word("sut", "suta", "sutnu", "sutna") -> Command.Sleep
            has("wake", "उठ", "ब्युँझ") -> Command.Wake
            // Before dismissing and thanks: "म ठिक छु" (I'm fine) contains "ठिक छ" (OK),
            // and "nice to meet you" is not a "nice" for a job well done.
            greeting == Greeting.FINE || greeting == Greeting.NICE -> Command.Greet(greeting)
            has("dismiss", "clear", "got it", "हटाउ", "ठिक छ", "ठीक छ") ||
                t == "ok" || t == "okay" || t == "हुन्छ" -> Command.Dismiss
            has("thank", "good job", "well done", "good boy", "nice", "धन्यवाद", "धन्यबाद", "स्याबास",
                "dhanyabad", "dhanyavad", "dhanyabaad", "dhanyavaad", "syabas", "shyabas") ->
                Command.Thanks
            has("help", "what can you do", "मद्दत", "के गर्न सक्छ") || word("madat", "maddat") -> Command.Help
            has("can you hear", "सुन्छौ", "सुनिरहेको") || word("test", "testing") -> Command.Hello
            has("address", "url", "ठेगाना") || Regex("""\bip\b""").containsMatchIn(t) -> Command.Address
            // Last: "hi, what time is it" is the time.
            greeting != null -> Command.Greet(greeting)
            else -> Command.Unknown
        }
    }

    /** Small talk in [t], already normalised, or null. */
    private fun greeting(t: String): Greeting? {
        fun has(vararg words: String) = words.any { t.contains(it) }

        // Whole words, for short English ones. Java's \b does not see Devanagari, so those use has().
        fun word(vararg words: String) = words.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }
        return when {
            has(
                "i'm fine", "im fine", "i am fine", "i'm good", "im good", "i am good", "i'm great", "i am great",
                "i'm okay", "i'm ok", "doing well", "doing good", "doing great", "not bad", "pretty good", "all good",
                "ठिक छु", "ठीक छु", "सन्चै छु", "सञ्चै छु", "राम्रो छु", "thik chu", "thik xu", "sanchai chu", "ramro chu",
            ) -> Greeting.FINE
            // Before goodbyes: "nice to see you" is not "see you".
            has(
                "nice to meet you", "nice meeting you", "nice to see you", "good to see you", "great to see you",
                "pleased to meet you", "glad to meet you",
                "भेटेर खुसी", "भेटेर खुशी", "bhetera khusi",
            ) -> Greeting.NICE
            has("i'm back", "im back", "i am back", "i'm home", "i am home", "म आएँ", "म फर्किएँ", "म आइपुगेँ") -> Greeting.BACK
            has(
                "how are you", "how r u", "how are things", "how's it going", "how is it going", "hows it going",
                "how do you do", "how you doing", "what's up", "whats up",
                "कस्तो छ", "के छ", "सन्चै", "सञ्चै", "kasto cha", "kasto chha", "sanchai",
            ) || word("ke cha", "k cha", "k xa") -> Greeting.HOW_ARE_YOU
            has("good morning", "morning buddy", "शुभ प्रभात", "शुभ बिहान", "subha prabhat", "shubha prabhat", "shubh prabhat") ||
                t == "morning" -> Greeting.MORNING
            has("good afternoon", "शुभ दिन", "शुभ दिउँसो", "subha din", "shubha din") -> Greeting.AFTERNOON
            has("good evening", "शुभ साँझ", "शुभ सन्ध्या", "subha sanjh", "shubha sanjh") -> Greeting.EVENING
            has(
                "your name", "who are you", "what are you",
                "तिम्रो नाम", "तपाईंको नाम", "तपाईको नाम", "को हौ", "timro naam", "timro nam",
            ) -> Greeting.NAME
            has("see you", "see ya", "बाइ", "फेरि भेटौंला", "फेरि भेटौला", "बिदा") || word("bye", "goodbye", "cya") -> Greeting.BYE
            has("नमस्ते", "नमस्कार", "हेलो", "हाई") ||
                word("hello", "hi", "hey", "hiya", "howdy", "hola", "namaste", "namaskar") -> Greeting.HELLO
            else -> null
        }
    }

    /** What NODE says back, in [lang]. Side effects are the caller's; this is only words. */
    fun reply(command: Command, lang: Lang, f: Facts): String = if (lang == Lang.NE) replyNe(command, f) else replyEn(command, f)

    /** Shown and spoken when a timer runs out. */
    fun timeUp(lang: Lang) = if (lang == Lang.NE) "समय सकियो।" else "Time's up."

    /** Shown and spoken when a focus session ends. */
    fun focusDone(lang: Lang) = if (lang == Lang.NE) "फोकस सकियो। अब आराम गर्नुहोस्।" else "Focus done. Take a break."

    /** Shown and spoken, over and over, when an alarm goes off. */
    fun wakeUp(lang: Lang) = if (lang == Lang.NE) "उठ्नुहोस्!" else "Wake up!"

    /**
     * The morning's one line, after "Good morning": the time, the Nepali date,
     * anything waiting, and the weather if it arrived. Each part is dropped
     * when there is nothing to say, so a quiet morning is a short sentence.
     */
    fun briefing(now: LocalDateTime, notesWaiting: Int, lang: Lang, weather: String?): String =
        if (lang == Lang.NE) {
            val bs = Bs.fromAd(now.toLocalDate())
            listOfNotNull(
                "अहिले ${clockNe(now.hour, now.minute)} बजे",
                bs?.let { "आज ${Bs.MONTH_NAMES_NE[it.month - 1]} ${Bs.neDigits(it.day)} गते" },
                weather,
                when {
                    notesWaiting <= 0 -> null
                    else -> "${Bs.neDigits(notesWaiting)} वटा कुरा पर्खिरहेको छ"
                },
            ).joinToString("। ") + "।"
        } else {
            val bs = Bs.fromAd(now.toLocalDate())
            listOfNotNull(
                "It's ${clockEn(now.hour, now.minute)}",
                bs?.let {
                    val month = Bs.MONTH_NAMES[it.month - 1].lowercase(Locale.ROOT).replaceFirstChar { c -> c.uppercase() }
                    "$month ${it.day}"
                },
                weather,
                when {
                    notesWaiting <= 0 -> null
                    notesWaiting == 1 -> "one thing waiting"
                    else -> "$notesWaiting things waiting"
                },
            ).joinToString(". ") + "."
        }

    /** While the microphone is open. */
    fun listening(lang: Lang) = if (lang == Lang.NE) "सुन्दैछु…" else "Listening…"

    /** Said once as the session window passes [percent]: the number, not advice. */
    fun usageWarning(percent: Int, lang: Lang): String =
        if (lang == Lang.NE) "सेसन ${Bs.neDigits(percent)}% प्रयोग भयो।" else "Session $percent% used."

    /** The points NODE speaks up at, lowest first. */
    val USAGE_WARN_AT = listOf(80, 95)

    /** The highest of [USAGE_WARN_AT] that [percent] has reached, or 0. */
    fun usagePassed(percent: Int): Int = USAGE_WARN_AT.lastOrNull { percent >= it } ?: 0

    // -- understanding ----------------------------------------------------

    /**
     * A length of time in the sentence: "5 minutes", "twenty five minutes",
     * "half an hour", "१० मिनेटपछि", "दुई घण्टा". A bare number counts as
     * minutes. Null when there is none.
     */
    internal fun duration(text: String): Long? {
        val t = normalise(text)
        if (Regex("""\bhalf (an )?hour\b""").containsMatchIn(t) || t.contains("आधा घण्टा") || t.contains("आधा घन्टा")) {
            return 30 * MINUTE
        }
        val words = t.split(' ').filter { it.isNotEmpty() }
        var bare: Long? = null
        var i = 0
        while (i < words.size) {
            val found = numberAt(words, i)
            if (found == null) {
                i++
                continue
            }
            val (value, next) = found
            val unit = words.getOrNull(next)?.let(::unitOf)
            if (unit != null) return value * unit
            // Without a unit, "timer 5" or "timer five" means minutes. Not "a" or
            // "an" ("a timer"), and not a Nepali word: "छ" is also "is".
            if (bare == null && (words[i].all { it.isDigit() } || words[i] in EN_BARE)) bare = value * MINUTE
            i = next
        }
        return bare
    }

    /**
     * A clock time in [t], already normalised -- "at 5 30 pm", "५ बजे",
     * "paanch baje". Null when the sentence names no hour.
     *
     * The colon is gone by now ([normalise] drops punctuation), so half past
     * five arrives here as two numbers.
     */
    internal fun clockIn(t: String): Clock? {
        CLOCK_EN.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            if (h > 23 || minute > 59) return null
            val meridiem = m.groupValues[3]
            val hour = when {
                meridiem.startsWith("p") && h < 12 -> h + 12
                meridiem.startsWith("a") && h == 12 -> 0
                else -> h
            }
            return Clock(hour, minute, exact = meridiem.isNotEmpty() || h == 0 || h > 12, phrase = m.value)
        }
        // Nepali says the hour before "बजे": "पाँच बजे", "5 30 बजे".
        val words = t.split(' ')
        val at = words.indexOfFirst { it.startsWith("बजे") || it.startsWith("baje") || it.startsWith("बज्") }
        if (at <= 0) return null
        val last = numberWord(words[at - 1]) ?: return null
        val before = words.getOrNull(at - 2)?.let(::numberWord)
        // Two numbers running into "बजे" are an hour and its minutes.
        val (hour, minute) = if (before != null && last <= 59 && words[at - 1].length == 2) {
            before to last
        } else {
            last to 0L
        }
        if (hour > 23 || minute > 59) return null
        val from = if (minute > 0L) at - 2 else at - 1
        return Clock(
            hour.toInt(),
            minute.toInt(),
            exact = hour == 0L || hour > 12,
            phrase = words.subList(from, at + 1).joinToString(" "),
        )
    }

    /** An hour and minute heard in a sentence, with the words they were heard in. */
    internal data class Clock(val hour: Int, val minute: Int, val exact: Boolean, val phrase: String)

    /**
     * The next moment the clock reads [hour]:[minute], after [now]. When the
     * hour was said without am or pm, both readings are on offer and the
     * sooner one wins -- "remind me at five" in the morning means this evening.
     */
    fun dueAt(now: LocalDateTime, hour: Int, minute: Int, exact: Boolean): LocalDateTime {
        val hours = (if (exact) listOf(hour) else listOf(hour % 12, hour % 12 + 12)).sorted()
        val today = now.toLocalDate()
        return hours.map { today.atTime(it, minute) }.firstOrNull { it.isAfter(now) }
            ?: today.plusDays(1).atTime(hours.first(), minute)
    }

    private fun remind(t: String): Command {
        if (Regex("""\bevery\b""").containsMatchIn(t) || t.contains("हरेक") || t.contains("harek")) {
            // "every hour" names no number, so a bare unit counts as one of it.
            val every = duration(t) ?: t.split(' ').firstNotNullOfOrNull(::unitOf)
            if (every != null) return Command.RemindEvery(every.coerceIn(MIN_EVERY_MS, MAX_EVERY_MS), remindText(t))
        }
        clockIn(t)?.let { clock ->
            return Command.RemindAt(clock.hour, clock.minute, clock.exact, remindText(t.replace(clock.phrase, " ")))
        }
        val ms = duration(t)
        val text = remindText(t)
        return if (ms == null) Command.Note(text) else Command.Remind(ms, text)
    }

    /** What is left of a reminder once the reminding words and the time are taken out. */
    private fun remindText(t: String): String = if (langOf(t) == Lang.NE) {
        // Drop the reminding words and the "N minutes" phrase; what is left is the reminder.
        val words = t.split(' ')
        val unitAt = words.indexOfFirst { unitOf(it) != null }
        words.filterIndexed { i, w ->
            NE_REMIND_WORDS.none { w.startsWith(it) } && i != unitAt && !(i == unitAt - 1 && numberWord(w) != null)
        }.joinToString(" ")
    } else {
        t.substringAfter("remind me", t)
            .replace(Regex("""\b(in|after|every)\s+[\w\s'-]*?\b(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b"""), " ")
            .replace(Regex("""\bevery (hour|minute|second)\b"""), " ")
            // Romanized Nepali has no Devanagari to spot it by, so it lands here.
            .replace(Regex("""\b(samjhaunu|samjhau|samjha|samjhana|yaad|yad|dila\w*|malai)\b"""), " ")
            .replace(Regex("""^\s*(to|that|about)\s+"""), "")
            .replace(Regex("""\s+"""), " ")
    }.trim().ifEmpty { if (langOf(t) == Lang.NE) "सम्झना" else "Reminder" }

    private fun noteText(t: String): String =
        if (langOf(t) == Lang.NE) {
            t.split(' ').filterNot { w -> NE_NOTE_WORDS.any { w.startsWith(it) } }.joinToString(" ").trim()
        } else {
            t.replace(Regex("""^(take a note( that)?|note( that| down)?|write down|remember( that| to)?)\s*:?\s*"""), "").trim()
        }

    /**
     * Two numbers with something to do between them: "17 times 23", "15% of
     * 2400", "१० जोड ५". Null when the sentence is not sums, NaN when it asks
     * to divide by zero.
     *
     * shortcut: two numbers and one operator, never a chain and never
     * brackets. Upgrade it the day someone dictates an expression.
     */
    internal fun calc(t: String): Double? {
        val words = t.replace("%", " percent ").replace(Regex("""\s+"""), " ").trim().split(' ')
        for ((i, w) in words.withIndex()) {
            val op = OPERATORS[w] ?: continue
            // "divided by four", "15 percent of 2400".
            val from = if (words.getOrNull(i + 1) in setOf("by", "of")) i + 2 else i + 1
            val right = calcNumberAt(words, from)?.first ?: continue
            val left = calcNumberEndingAt(words, i) ?: continue
            return when (op) {
                '+' -> (left + right).toDouble()
                '-' -> (left - right).toDouble()
                '*' -> (left * right).toDouble()
                '%' -> left.toDouble() * right / 100.0
                else -> if (right == 0L) Double.NaN else left.toDouble() / right
            }
        }
        return null
    }

    /**
     * "Mangsir 26 in English", "what's December 25 in Nepali" -- a month, a
     * day, and which calendar you want it in.
     *
     * The calendar has to be named. Without that rule "maybe 5 minutes" would
     * read as the fifth of May, and half the months are ordinary words.
     */
    internal fun dateIn(t: String): Command.DateIn? {
        if (!CALENDAR_CUE.containsMatchIn(t)) return null
        val words = t.split(' ')
        val day = numbersIn(t).firstOrNull { it in 1..32 }?.toInt() ?: return null
        fun monthIn(names: Map<String, Int>) =
            words.firstNotNullOfOrNull { w -> names.entries.firstOrNull { w.startsWith(it.key) }?.value }
        monthIn(BS_MONTHS)?.let { return Command.DateIn(it, day, nepali = true) }
        monthIn(AD_MONTHS)?.let { return Command.DateIn(it, day, nepali = false) }
        return null
    }

    /** The two dates for [c], or null when no such day exists. */
    private fun resolve(c: Command.DateIn, now: LocalDateTime): Pair<BsDate, LocalDate>? = if (c.nepali) {
        val year = Bs.fromAd(now.toLocalDate())?.year
        val ad = year?.let { Bs.toAd(it, c.month, c.day) }
        if (ad == null) null else BsDate(year, c.month, c.day) to ad
    } else {
        val ad = runCatching { LocalDate.of(now.year, c.month, c.day) }.getOrNull()
        val bs = ad?.let { Bs.fromAd(it) }
        if (bs == null) null else bs to ad
    }

    private val CALENDAR_CUE =
        Regex("""\b(english|nepali|bikram|sambat|gregorian)\b|अंग्रेजी|अङ्ग्रेजी|नेपाली|विक्रम|ईस्वी""")

    private val BS_MONTHS: Map<String, Int> = buildMap {
        Bs.MONTH_NAMES.forEachIndexed { i, name -> put(name.lowercase(Locale.ROOT), i + 1) }
        Bs.MONTH_NAMES_NE.forEachIndexed { i, name -> put(name, i + 1) }
        // How they are also commonly written.
        putAll(mapOf("baisakh" to 1, "jeth" to 2, "asar" to 3, "sawan" to 4, "bhadau" to 5, "asoj" to 6))
        putAll(mapOf("mangshir" to 8, "marga" to 8, "push" to 9, "falgun" to 11, "chait" to 12))
    }

    private val AD_MONTHS: Map<String, Int> = (1..12).associateBy(
        { Month.of(it).getDisplayName(TextStyle.FULL, Locale.US).lowercase(Locale.ROOT) },
        { it },
    )

    /**
     * What was asked for literally: English puts the verb first ("say
     * namaste"), Nepali puts it last ("नमस्ते भन"). Null when neither pattern
     * fits, and null for a bare "say" with nothing after it.
     */
    private fun said(t: String, vararg patterns: Regex): String? = patterns
        .firstNotNullOfOrNull { p -> p.find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } }
        ?.trim()?.ifBlank { null }

    private val ECHO_EN = Regex("""^(?:say|repeat after me|repeat)\s+(.+)$""")
    private val ECHO_NE = Regex("""^(.+?)\s+(?:भन्नुहोस्|भन्|भन)$""")
    private val SPELL_EN = Regex("""^spell\s+(?:out\s+)?(.+)$""")
    private val SPELL_NE = Regex("""^(?:हिज्जे)\s+(.+)$|^(.+?)\s+हिज्जे$""")

    /** Every number in the sentence, in the order said. */
    private fun numbersIn(t: String): List<Long> {
        val words = t.split(' ')
        val found = mutableListOf<Long>()
        var i = 0
        while (i < words.size) {
            // "roll a dice" and "pick a number" are not asking for a one.
            val number = if (words[i] in ARTICLES) null else calcNumberAt(words, i)
            if (number == null) {
                i++
            } else {
                found += number.first
                i = number.second
            }
        }
        return found
    }

    /** Like [numberAt], but any size and zero included: this is arithmetic, not minutes. */
    private fun calcNumberAt(words: List<String>, i: Int): Pair<Long, Int>? {
        val first = words.getOrNull(i)?.let { it.toLongOrNull() ?: NUMBERS[it] } ?: return null
        val second = words.getOrNull(i + 1)?.let { it.toLongOrNull() ?: NUMBERS[it] }
        return if (first in TENS && second != null && second in 1..9) (first + second) to i + 2 else first to i + 1
    }

    /** The number that finishes just before [end], "twenty five" included. */
    private fun calcNumberEndingAt(words: List<String>, end: Int): Long? {
        for (start in intArrayOf(end - 2, end - 1)) {
            if (start < 0) continue
            calcNumberAt(words, start)?.let { if (it.second == end) return it.first }
        }
        return null
    }

    /**
     * "10 km in miles", "how many pounds is 70 kilos", "२० डिग्री फरेनहाइटमा".
     * The unit beside the number is what you have; any other unit of the same
     * kind in the sentence is what you want, and without one it is the pair's
     * other half. Whole numbers only: [normalise] drops the decimal point.
     */
    internal fun convert(t: String): Command.Convert? {
        val words = t.split(' ')
        val units = words.mapIndexedNotNull { i, w ->
            UNIT_WORDS.entries.firstOrNull { w.startsWith(it.key) }?.let { i to it.value }
        }
        if (units.isEmpty()) return null
        val found = words.indices.firstNotNullOfOrNull { i -> calcNumberAt(words, i) } ?: return null
        // The one right after the number is what you have -- "how many pounds
        // is 70 kilos" starts with the unit you want, not the one you have.
        val from = (units.firstOrNull { it.first >= found.second } ?: units.first()).second
        val to = units.map { it.second }.firstOrNull { it != from && it.family == from.family } ?: from.other
        return Command.Convert(converted(found.first.toDouble(), from, to), to)
    }

    private fun converted(v: Double, from: Measure, to: Measure): Double = when {
        from == to -> v
        from == Measure.KM -> v * 0.621371
        from == Measure.MILE -> v * 1.609344
        from == Measure.KG -> v * 2.2046226
        from == Measure.LB -> v * 0.45359237
        from == Measure.C -> v * 9 / 5 + 32
        from == Measure.F -> (v - 32) * 5 / 9
        from == Measure.NPR -> v / NPR_PER_INR
        else -> v * NPR_PER_INR
    }

    /** The peg has held since 1993; it is a fixed rate, not a market one. */
    private const val NPR_PER_INR = 1.6

    /** Matched on the start of a word, so plurals and Nepali suffixes come free. */
    private val UNIT_WORDS: Map<String, Measure> = mapOf(
        "kilometre" to Measure.KM, "kilometer" to Measure.KM, "km" to Measure.KM, "किलोमिटर" to Measure.KM,
        "mile" to Measure.MILE, "माइल" to Measure.MILE,
        "kilogram" to Measure.KG, "kilo" to Measure.KG, "kg" to Measure.KG, "केजी" to Measure.KG, "किलो" to Measure.KG,
        "pound" to Measure.LB, "lbs" to Measure.LB, "lb" to Measure.LB, "पाउन्ड" to Measure.LB,
        "celsius" to Measure.C, "centigrade" to Measure.C, "सेल्सियस" to Measure.C,
        "fahrenheit" to Measure.F, "फरेनहाइट" to Measure.F,
        "nepali" to Measure.NPR, "npr" to Measure.NPR, "रुपैयाँ" to Measure.NPR, "रुपिया" to Measure.NPR,
        "indian" to Measure.INR, "inr" to Measure.INR, "भारु" to Measure.INR, "भारतीय" to Measure.INR,
    )

    internal fun unitName(unit: Measure, lang: Lang): String = if (lang == Lang.NE) {
        when (unit) {
            Measure.KM -> "किलोमिटर"
            Measure.MILE -> "माइल"
            Measure.KG -> "केजी"
            Measure.LB -> "पाउन्ड"
            Measure.C -> "डिग्री सेल्सियस"
            Measure.F -> "डिग्री फरेनहाइट"
            Measure.NPR -> "नेपाली रुपैयाँ"
            Measure.INR -> "भारतीय रुपैयाँ"
        }
    } else {
        when (unit) {
            Measure.KM -> "kilometres"
            Measure.MILE -> "miles"
            Measure.KG -> "kilos"
            Measure.LB -> "pounds"
            Measure.C -> "degrees Celsius"
            Measure.F -> "degrees Fahrenheit"
            Measure.NPR -> "Nepali rupees"
            Measure.INR -> "Indian rupees"
        }
    }

    private val OPERATORS: Map<String, Char> = mapOf(
        "plus" to '+', "add" to '+', "जोड" to '+',
        "minus" to '-', "less" to '-', "घटाउ" to '-',
        "times" to '*', "multiplied" to '*', "x" to '*', "गुणा" to '*',
        "divided" to '/', "over" to '/', "भाग" to '/',
        "percent" to '%', "प्रतिशत" to '%',
    )

    /** The number starting at [i], and the index after it. "twenty five" is one number. */
    private fun numberAt(words: List<String>, i: Int): Pair<Long, Int>? {
        val first = numberWord(words[i]) ?: return null
        val second = words.getOrNull(i + 1)?.let(::numberWord)
        return if (first in TENS && second != null && second in 1..9) (first + second) to i + 2 else first to i + 1
    }

    private fun numberWord(w: String): Long? = w.toLongOrNull()?.takeIf { it in 1..999 } ?: NUMBERS[w]

    /** "minutes", "मिनेटपछि", "घण्टाको" -- a unit word, perhaps with a Nepali suffix. */
    private fun unitOf(w: String): Long? = when {
        w.matches(Regex("""(seconds?|secs?)""")) || NE_SECONDS.any { w.startsWith(it) } -> SECOND
        w.matches(Regex("""(minutes?|mins?)""")) || NE_MINUTES.any { w.startsWith(it) } -> MINUTE
        w.matches(Regex("""(hours?|hrs?)""")) || NE_HOURS.any { w.startsWith(it) } -> HOUR
        else -> null
    }

    /** Lower case, Devanagari digits as ASCII, digits split from the words they touch, punctuation gone. */
    private fun normalise(s: String): String =
        s.lowercase(Locale.ROOT)
            .map { if (it in '०'..'९') '0' + (it - '०') else it }
            .joinToString("")
            .replace(Regex("""(\d)(\p{L})"""), "$1 $2")
            .replace(Regex("""[.,!?।:;"]"""), " ")
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    // -- answering --------------------------------------------------------

    private fun replyEn(c: Command, f: Facts): String = when (c) {
        Command.Time -> "It's ${f.now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}."
        Command.Date -> {
            val day = f.now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US))
            val bs = Bs.fromAd(f.now.toLocalDate())
            if (bs == null) "$day." else "$day. In Bikram Sambat, ${bsMonthEn(bs.month)} ${bs.day}, ${bs.year}."
        }
        is Command.Timer -> c.ms?.let { "Timer set for ${durationEn(it)}." }
            ?: "For how long? Say, for example, timer five minutes."
        is Command.Focus -> "Focus for ${durationEn(c.ms)}. I'll tell you when to take a break."
        Command.CancelTimer -> if (f.timerRunning) "Cancelled." else "There's nothing to cancel."
        is Command.Remind -> "OK. In ${durationEn(c.ms)}, I'll remind you: ${c.text}."
        is Command.RemindAt -> "OK. At ${clockEn(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)}, " +
            "I'll remind you: ${c.text}."
        is Command.Alarm -> "Alarm set for ${clockEn(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)}. " +
            "Tap me to turn it off."
        is Command.RemindEvery -> "OK. Every ${durationEn(c.everyMs)} while you're here: ${c.text}."
        is Command.Note -> if (c.text.isBlank()) "What should I note?" else "Noted: ${c.text}."
        Command.ClaudeStatus -> when (f.claude?.state) {
            null -> "No Claude sessions right now."
            // Never which project: the reply is shown on screen, and project names stay off it.
            ClaudeSessions.State.NEEDS_YOU -> "Claude needs you."
            ClaudeSessions.State.WORKING -> "Claude is working."
            ClaudeSessions.State.DONE -> "Claude has finished."
        } + moreEn(f.claude)
        Command.SessionUsage -> if (f.usagePercent == null) {
            "I haven't been told how much of your session is used."
        } else {
            "Your session is ${f.usagePercent}% used." +
                (f.usageResetsInMs?.let { " It resets in ${durationEn(it)}." } ?: "")
        }
        is Command.Calc -> if (c.result.isNaN()) "You can't divide by zero." else "That's ${number(c.result)}."
        is Command.Convert -> "That's ${number(c.value)} ${unitName(c.to, Lang.EN)}."
        is Command.Echo -> c.text
        is Command.Spell -> spellOut(c.text)
        is Command.DateIn -> resolve(c, f.now)?.let { (bs, ad) ->
            val bsText = "${bsMonthEn(bs.month)} ${bs.day}, ${bs.year}"
            val adText = ad.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
            if (c.nepali) "$bsText is $adText." else "$adText is $bsText."
        } ?: "There's no such date."
        Command.Coin -> if (f.random.nextBoolean()) "Heads." else "Tails."
        is Command.Dice -> "You rolled a ${roll(c, f.random)}."
        is Command.Pick -> "${pick(c, f.random)}."
        Command.Sleep -> "Good night."
        Command.Wake -> "I'm awake."
        Command.Dismiss -> if (f.noteDue) "Dismissed." else "There's nothing to dismiss."
        Command.Thanks -> "You're welcome!"
        Command.Help -> "Ask me the time or the date, set a timer or a focus session, ask me to remind you, " +
            "take a note, or ask what Claude is doing. Or just say hi."
        Command.Address -> f.address?.let { "My address is $it" } ?: "I'm not on Wi-Fi."
        is Command.Language -> if (c.lang == Lang.NE) "अब म नेपालीमा बोल्छु।" else "OK, I'll speak English."
        Command.Hello -> "I can hear you!"
        is Command.Greet -> when (c.kind) {
            Greeting.HELLO -> "Hi there!"
            Greeting.MORNING -> "Good morning!"
            Greeting.AFTERNOON -> "Good afternoon!"
            Greeting.EVENING -> "Good evening!"
            Greeting.HOW_ARE_YOU -> "I'm good, thanks! How are you?"
            Greeting.FINE -> "Glad to hear it!"
            Greeting.NICE -> "Nice to see you too!"
            Greeting.BACK -> "Welcome back!"
            Greeting.NAME -> "I'm NODE, your desk buddy."
            Greeting.BYE -> "Bye! See you soon."
        }
        Command.Unknown -> "Sorry, I didn't catch that. Say help to hear what I can do."
    }

    private fun replyNe(c: Command, f: Facts): String = when (c) {
        Command.Time -> "अहिले ${clockNe(f.now.hour, f.now.minute)} बजेको छ।"
        Command.Date -> {
            val weekday = WEEKDAYS_NE[f.now.dayOfWeek.value % 7]
            val bs = Bs.fromAd(f.now.toLocalDate())
            if (bs == null) "आज $weekday।" else
                "आज ${Bs.neDigits(bs.year)} ${Bs.MONTH_NAMES_NE[bs.month - 1]} ${Bs.neDigits(bs.day)} गते, $weekday।"
        }
        is Command.Timer -> c.ms?.let { "${durationNe(it)}को टाइमर राखेँ।" }
            ?: "कति समयको? जस्तै, पाँच मिनेटको टाइमर भन्नुहोस्।"
        is Command.Focus -> "${durationNe(c.ms)} फोकस गरौं। सकिएपछि भन्छु।"
        Command.CancelTimer -> if (f.timerRunning) "रद्द गरेँ।" else "रद्द गर्नुपर्ने केही छैन।"
        is Command.Remind -> "हुन्छ, ${durationNe(c.ms)}पछि सम्झाउँछु: ${c.text}।"
        is Command.RemindAt ->
            "हुन्छ, ${clockNe(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)} बजे सम्झाउँछु: ${c.text}।"
        is Command.Alarm ->
            "${clockNe(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)} बजे उठाउँछु। बन्द गर्न छुनुहोस्।"
        is Command.RemindEvery -> "हुन्छ, तपाईं यहाँ हुँदा हरेक ${durationNe(c.everyMs)}मा सम्झाउँछु: ${c.text}।"
        is Command.Note -> if (c.text.isBlank()) "के नोट गरूँ?" else "नोट गरेँ: ${c.text}।"
        Command.ClaudeStatus -> when (f.claude?.state) {
            null -> "अहिले Claude चलिरहेको छैन।"
            ClaudeSessions.State.NEEDS_YOU -> "Claude लाई तपाईं चाहिएको छ।"
            ClaudeSessions.State.WORKING -> "Claude काम गर्दैछ।"
            ClaudeSessions.State.DONE -> "Claude ले काम सक्यो।"
        }
        Command.SessionUsage -> if (f.usagePercent == null) {
            "सेसन कति प्रयोग भयो मलाई थाहा छैन।"
        } else {
            "तपाईंको सेसन ${Bs.neDigits(f.usagePercent)}% प्रयोग भयो।" +
                (f.usageResetsInMs?.let { " ${durationNe(it)}पछि रिसेट हुन्छ।" } ?: "")
        }
        is Command.Calc ->
            if (c.result.isNaN()) "शून्यले भाग जाँदैन।" else "${neNumber(number(c.result))} हुन्छ।"
        is Command.Convert -> "${neNumber(number(c.value))} ${unitName(c.to, Lang.NE)} हुन्छ।"
        is Command.Echo -> c.text
        is Command.Spell -> spellOut(c.text)
        is Command.DateIn -> resolve(c, f.now)?.let { (bs, ad) ->
            val bsText = "${Bs.neDigits(bs.year)} ${Bs.MONTH_NAMES_NE[bs.month - 1]} ${Bs.neDigits(bs.day)} गते"
            val adText = "${ad.format(DateTimeFormatter.ofPattern("MMMM", Locale.US))} " +
                "${Bs.neDigits(ad.dayOfMonth)}, ${Bs.neDigits(ad.year)}"
            if (c.nepali) "$bsText भनेको $adText हो।" else "$adText भनेको $bsText हो।"
        } ?: "त्यस्तो मिति छैन।"
        Command.Coin -> if (f.random.nextBoolean()) "चित।" else "पट।"
        is Command.Dice -> "${Bs.neDigits(roll(c, f.random))} आयो।"
        is Command.Pick -> "${Bs.neDigits(pick(c, f.random))}।"
        Command.Sleep -> "शुभरात्री।"
        Command.Wake -> "म ब्युँझिएँ।"
        Command.Dismiss -> if (f.noteDue) "हटाएँ।" else "हटाउनुपर्ने केही छैन।"
        Command.Thanks -> "स्वागत छ!"
        Command.Help -> "मलाई समय, मिति, टाइमर, फोकस, सम्झना, नोट, वा Claude के गर्दैछ भनेर सोध्न सक्नुहुन्छ। नमस्ते पनि भन्न सक्नुहुन्छ।"
        Command.Address -> f.address?.let { "मेरो ठेगाना: $it" } ?: "म Wi-Fi मा छैन।"
        is Command.Language -> if (c.lang == Lang.NE) "अब म नेपालीमा बोल्छु।" else "OK, I'll speak English."
        Command.Hello -> "सुनें!"
        is Command.Greet -> when (c.kind) {
            Greeting.HELLO -> "नमस्ते!"
            Greeting.MORNING -> "शुभ प्रभात!"
            Greeting.AFTERNOON -> "शुभ दिन!"
            Greeting.EVENING -> "शुभ साँझ!"
            Greeting.HOW_ARE_YOU -> "म सन्चै छु, धन्यवाद! तपाईंलाई कस्तो छ?"
            Greeting.FINE -> "सुनेर खुसी लाग्यो!"
            Greeting.NICE -> "तपाईंलाई भेटेर मलाई पनि खुसी लाग्यो!"
            Greeting.BACK -> "फेरि स्वागत छ!"
            Greeting.NAME -> "म NODE, तपाईंको डेस्क साथी।"
            Greeting.BYE -> "बाइ! फेरि भेटौंला।"
        }
        Command.Unknown -> "माफ गर्नुहोस्, बुझिनँ। मद्दत भन्नुहोस्।"
    }

    private fun moreEn(s: ClaudeSessions.Summary?) =
        if (s != null && s.others > 0) " And ${s.others} more." else ""

    /**
     * "node" -> "N O D E". Spaces between the letters are what makes a speech
     * engine read them one at a time rather than as a word. Words stay apart.
     */
    internal fun spellOut(text: String): String = text.trim().split(Regex("""\s+"""))
        .joinToString("  ") { word -> word.uppercase(Locale.ROOT).toCharArray().joinToString(" ") }

    /** Both guard against nonsense asked for out loud: a one-sided dice, a range the wrong way round. */
    private fun roll(c: Command.Dice, random: Random): Int = random.nextInt(1, c.sides.coerceIn(2, 1_000) + 1)

    private fun pick(c: Command.Pick, random: Random): Int {
        val from = minOf(c.from, c.to)
        val to = maxOf(c.from, c.to)
        return if (from == to) from else random.nextInt(from, to + 1)
    }

    /** A result as a person would say it: whole when it is whole, two places when it is not. */
    internal fun number(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /** "KARTIK" as it reads in a sentence: "Kartik". */
    private fun bsMonthEn(month: Int): String =
        Bs.MONTH_NAMES[month - 1].lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }

    /** The same digits in Devanagari. */
    internal fun neNumber(digits: String): String =
        digits.map { if (it in '0'..'9') '०' + (it - '0') else it }.joinToString("")

    internal fun durationEn(ms: Long): String {
        val h = ms / HOUR
        val m = ms % HOUR / MINUTE
        val s = ms % MINUTE / SECOND
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it == 1L) "" else "s"}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it == 1L) "" else "s"}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it == 1L) "" else "s"}" },
        ).joinToString(" ").ifEmpty { "0 seconds" }
    }

    internal fun durationNe(ms: Long): String {
        val h = ms / HOUR
        val m = ms % HOUR / MINUTE
        val s = ms % MINUTE / SECOND
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "${Bs.neDigits(it.toInt())} घण्टा" },
            m.takeIf { it > 0 }?.let { "${Bs.neDigits(it.toInt())} मिनेट" },
            s.takeIf { it > 0 }?.let { "${Bs.neDigits(it.toInt())} सेकेन्ड" },
        ).joinToString(" ").ifEmpty { "० सेकेन्ड" }
    }

    /** Sunday first, as java.time's day-of-week value modulo 7. */
    private val WEEKDAYS_NE = arrayOf("आइतबार", "सोमबार", "मंगलबार", "बुधबार", "बिहीबार", "शुक्रबार", "शनिबार")

    /** "5:30 PM". */
    internal fun clockEn(hour: Int, minute: Int): String =
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))

    /** "बेलुका ५:३०" -- Nepali tells the time by part of the day, not am and pm. */
    internal fun clockNe(hour: Int, minute: Int): String {
        val part = when (hour) {
            in 4..11 -> "बिहान"
            in 12..15 -> "दिउँसो"
            in 16..18 -> "बेलुका"
            else -> "राति"
        }
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$part ${Bs.neDigits(h12)}:${Bs.neDigits2(minute)}"
    }

    /** `at 5`, `for 5 30`, `at 5 30 pm` -- the colon is already gone by now. */
    private val CLOCK_EN = Regex("""\b(?:at|for) (\d{1,2})(?: (\d{2}))?(?: (am|pm|a m|p m))?\b""")

    private val TENS = setOf(20L, 30L, 40L, 50L)

    /** Worth one in "in a minute", worth nothing in "roll a dice". */
    private val ARTICLES = setOf("a", "an")

    /** English number words that may stand alone as minutes. */
    private val EN_BARE = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "fifteen", "twenty", "thirty", "forty", "fifty", "sixty", "ninety",
    )

    private val NUMBERS: Map<String, Long> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "ninety" to 90,
        "एक" to 1, "दुई" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5, "छ" to 6, "सात" to 7,
        "आठ" to 8, "नौ" to 9, "दस" to 10, "दश" to 10, "एघार" to 11, "बाह्र" to 12, "तेह्र" to 13,
        "चौध" to 14, "पन्ध्र" to 15, "सोह्र" to 16, "सत्र" to 17, "अठार" to 18, "उन्नाइस" to 19,
        "बीस" to 20, "बिस" to 20, "पच्चीस" to 25, "तीस" to 30, "पैंतीस" to 35, "चालीस" to 40,
        "पैंतालीस" to 45, "पचास" to 50, "साठी" to 60,
        // Romanized Nepali, as online recognition tends to write it.
        "ek" to 1, "dui" to 2, "tin" to 3, "teen" to 3, "char" to 4, "chaar" to 4, "panch" to 5, "paanch" to 5,
        "chha" to 6, "saat" to 7, "sat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "pandhra" to 15,
        "bis" to 20, "bees" to 20, "pachchis" to 25, "pachis" to 25, "tis" to 30, "tees" to 30,
        "chalis" to 40, "pachas" to 50, "saathi" to 60,
    ).mapValues { it.value.toLong() }

    private val NE_SECONDS = listOf("सेकेन्ड", "सेकेण्ड", "सेकन्ड", "sekend")
    private val NE_MINUTES = listOf("मिनेट", "मिनट", "मिनिट", "minet")
    private val NE_HOURS = listOf("घण्टा", "घन्टा", "घंटा", "ghanta", "ghantaa")
    private val NE_REMIND_WORDS = listOf("सम्झा", "सम्झना", "याद", "दिला", "मलाई", "पछि", "हरेक")
    private val NE_NOTE_WORDS = listOf("नोट", "लेख", "गरिदे", "राखिदे")
}
