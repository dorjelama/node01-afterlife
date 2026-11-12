package dev.node01.afterlife

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    data class Note(val text: String) : Command
    data object ClaudeStatus : Command

    /** "How much of my session is left?", "when does my session reset?" */
    data object SessionUsage : Command
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
    )

    const val FOCUS_MS = 25 * 60_000L
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
        fun has(vararg words: String) = words.any { t.contains(it) }

        /** Whole words only: short romanized Nepali like "sut" must not match inside English. */
        fun word(vararg words: String) = words.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }

        return when {
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
            .replace(Regex("""\b(in|after)\s+[\w\s'-]*?\b(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b"""), " ")
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
            if (bs == null) "$day." else {
                val month = Bs.MONTH_NAMES[bs.month - 1].lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
                "$day. In Bikram Sambat, $month ${bs.day}, ${bs.year}."
            }
        }
        is Command.Timer -> c.ms?.let { "Timer set for ${durationEn(it)}." }
            ?: "For how long? Say, for example, timer five minutes."
        is Command.Focus -> "Focus for ${durationEn(c.ms)}. I'll tell you when to take a break."
        Command.CancelTimer -> if (f.timerRunning) "Timer cancelled." else "No timer is running."
        is Command.Remind -> "OK. In ${durationEn(c.ms)}, I'll remind you: ${c.text}."
        is Command.RemindAt -> "OK. At ${clockEn(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)}, " +
            "I'll remind you: ${c.text}."
        is Command.Alarm -> "Alarm set for ${clockEn(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)}. " +
            "Tap me to turn it off."
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
        Command.CancelTimer -> if (f.timerRunning) "टाइमर रद्द गरेँ।" else "कुनै टाइमर चलिरहेको छैन।"
        is Command.Remind -> "हुन्छ, ${durationNe(c.ms)}पछि सम्झाउँछु: ${c.text}।"
        is Command.RemindAt ->
            "हुन्छ, ${clockNe(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)} बजे सम्झाउँछु: ${c.text}।"
        is Command.Alarm ->
            "${clockNe(dueAt(f.now, c.hour, c.minute, c.exact).hour, c.minute)} बजे उठाउँछु। बन्द गर्न छुनुहोस्।"
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
    private val NE_REMIND_WORDS = listOf("सम्झा", "सम्झना", "याद", "दिला", "मलाई", "पछि")
    private val NE_NOTE_WORDS = listOf("नोट", "लेख", "गरिदे", "राखिदे")
}
