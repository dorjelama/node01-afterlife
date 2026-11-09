package dev.node01.afterlife

import java.time.LocalDateTime
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
    data class Note(val text: String) : Command
    data object ClaudeStatus : Command
    data object Sleep : Command
    data object Wake : Command
    data object Dismiss : Command
    data object Thanks : Command
    data object Help : Command
    data object Address : Command
    data class Language(val lang: Lang) : Command
    data object Unknown : Command
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
    )

    const val FOCUS_MS = 25 * 60_000L
    private const val SECOND = 1_000L
    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L

    /** Devanagari in the text means it was Nepali. */
    fun langOf(text: String): Lang = if (text.any { it in 'ऀ'..'ॿ' }) Lang.NE else Lang.EN

    fun parse(heard: String): Command {
        val t = normalise(heard)
        if (t.isBlank()) return Command.Unknown
        fun has(vararg words: String) = words.any { t.contains(it) }

        return when {
            // Language first, but "nepali date" is a date, not a request to switch.
            Regex("""\b(speak|talk|use|switch to|in) nepali\b""").containsMatchIn(t) || t == "nepali" ||
                (has("नेपालीमा") && has("बोल")) -> Command.Language(Lang.NE)
            Regex("""\b(speak|talk|use|switch to|in) english\b""").containsMatchIn(t) || t == "english" ||
                has("अंग्रेजी", "अङ्ग्रेजी", "इंग्लिस", "इङ्ग्लिस") -> Command.Language(Lang.EN)

            (has("cancel", "stop", "clear") && has("timer", "focus", "pomodoro", "reminder")) ||
                (has("रद्द", "बन्द", "रोक") && has("टाइमर", "फोकस")) -> Command.CancelTimer

            has("remind", "सम्झाउ", "सम्झना", "याद दिला") -> remind(t)

            has("timer", "टाइमर") -> Command.Timer(duration(t))
            has("focus", "pomodoro", "फोकस", "पोमोडोरो") -> Command.Focus(duration(t) ?: FOCUS_MS)

            Regex("""^(take a note|note|write down|remember)\b""").containsMatchIn(t) || has("नोट") ->
                Command.Note(noteText(t))

            has("claude", "cloud", "clod", "क्लड", "क्लाउड", "क्लोड") -> Command.ClaudeStatus

            has("time", "o'clock", "समय", "बज्यो", "बजे", "टाइम") -> Command.Time
            has("date", "what day", "today", "गते", "मिति", "तारिख", "कुन बार", "आज") -> Command.Date

            has("sleep", "good night", "goodnight", "सुत", "शुभरात्री", "शुभ रात्री") -> Command.Sleep
            has("wake", "उठ", "ब्युँझ") -> Command.Wake
            has("dismiss", "clear", "got it", "हटाउ", "ठिक छ", "ठीक छ") ||
                t == "ok" || t == "okay" || t == "हुन्छ" -> Command.Dismiss
            has("thank", "good job", "well done", "good boy", "nice", "धन्यवाद", "धन्यबाद", "स्याबास") ->
                Command.Thanks
            has("help", "what can you do", "मद्दत", "के गर्न सक्छ") -> Command.Help
            has("address", "url", "ठेगाना") || Regex("""\bip\b""").containsMatchIn(t) -> Command.Address
            else -> Command.Unknown
        }
    }

    /** What NODE says back, in [lang]. Side effects are the caller's; this is only words. */
    fun reply(command: Command, lang: Lang, f: Facts): String = if (lang == Lang.NE) replyNe(command, f) else replyEn(command, f)

    /** Shown and spoken when a timer runs out. */
    fun timeUp(lang: Lang) = if (lang == Lang.NE) "समय सकियो।" else "Time's up."

    /** Shown and spoken when a focus session ends. */
    fun focusDone(lang: Lang) = if (lang == Lang.NE) "फोकस सकियो। अब आराम गर्नुहोस्।" else "Focus done. Take a break."

    /** While the microphone is open. */
    fun listening(lang: Lang) = if (lang == Lang.NE) "सुन्दैछु…" else "Listening…"

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

    private fun remind(t: String): Command {
        val ms = duration(t)
        val text = if (langOf(t) == Lang.NE) {
            t.split(' ')
                .filterNot { w -> NE_REMIND_WORDS.any { w.startsWith(it) } || unitOf(w) != null || numberWord(w) != null }
                .joinToString(" ")
        } else {
            t.substringAfter("remind me", t)
                .replace(Regex("""\b(in|after)\s+[\w\s'-]*?\b(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b"""), " ")
                .replace(Regex("""^\s*(to|that|about)\s+"""), "")
                .replace(Regex("""\s+"""), " ")
        }.trim().ifEmpty { if (langOf(t) == Lang.NE) "सम्झना" else "Reminder" }
        return if (ms == null) Command.Note(text) else Command.Remind(ms, text)
    }

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
        is Command.Note -> if (c.text.isBlank()) "What should I note?" else "Noted: ${c.text}."
        Command.ClaudeStatus -> when (f.claude?.state) {
            null -> "No Claude sessions right now."
            ClaudeSessions.State.NEEDS_YOU -> "Claude needs you in ${f.claude.project}."
            ClaudeSessions.State.WORKING -> "Claude is working on ${f.claude.project}."
            ClaudeSessions.State.DONE -> "Claude has finished in ${f.claude.project}."
        } + moreEn(f.claude)
        Command.Sleep -> "Good night."
        Command.Wake -> "I'm awake."
        Command.Dismiss -> if (f.noteDue) "Dismissed." else "There's nothing to dismiss."
        Command.Thanks -> "You're welcome!"
        Command.Help -> "Ask me the time or the date, set a timer or a focus session, ask me to remind you, " +
            "take a note, or ask what Claude is doing."
        Command.Address -> f.address?.let { "My address is $it" } ?: "I'm not on Wi-Fi."
        is Command.Language -> if (c.lang == Lang.NE) "अब म नेपालीमा बोल्छु।" else "OK, I'll speak English."
        Command.Unknown -> "Sorry, I didn't catch that. Say help to hear what I can do."
    }

    private fun replyNe(c: Command, f: Facts): String = when (c) {
        Command.Time -> {
            val h = f.now.hour
            val part = when (h) {
                in 4..11 -> "बिहान"
                in 12..15 -> "दिउँसो"
                in 16..18 -> "बेलुका"
                else -> "राति"
            }
            val h12 = if (h % 12 == 0) 12 else h % 12
            "अहिले $part ${Bs.neDigits(h12)}:${Bs.neDigits2(f.now.minute)} बजेको छ।"
        }
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
        is Command.Note -> if (c.text.isBlank()) "के नोट गरूँ?" else "नोट गरेँ: ${c.text}।"
        Command.ClaudeStatus -> when (f.claude?.state) {
            null -> "अहिले Claude चलिरहेको छैन।"
            ClaudeSessions.State.NEEDS_YOU -> "Claude लाई तपाईं चाहिएको छ: ${f.claude.project}।"
            ClaudeSessions.State.WORKING -> "Claude ${f.claude.project} मा काम गर्दैछ।"
            ClaudeSessions.State.DONE -> "Claude ले ${f.claude.project} को काम सक्यो।"
        }
        Command.Sleep -> "शुभरात्री।"
        Command.Wake -> "म ब्युँझिएँ।"
        Command.Dismiss -> if (f.noteDue) "हटाएँ।" else "हटाउनुपर्ने केही छैन।"
        Command.Thanks -> "स्वागत छ!"
        Command.Help -> "मलाई समय, मिति, टाइमर, फोकस, सम्झना, नोट, वा Claude के गर्दैछ भनेर सोध्न सक्नुहुन्छ।"
        Command.Address -> f.address?.let { "मेरो ठेगाना: $it" } ?: "म Wi-Fi मा छैन।"
        is Command.Language -> if (c.lang == Lang.NE) "अब म नेपालीमा बोल्छु।" else "OK, I'll speak English."
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
    ).mapValues { it.value.toLong() }

    private val NE_SECONDS = listOf("सेकेन्ड", "सेकेण्ड", "सेकन्ड")
    private val NE_MINUTES = listOf("मिनेट", "मिनट", "मिनिट")
    private val NE_HOURS = listOf("घण्टा", "घन्टा", "घंटा")
    private val NE_REMIND_WORDS = listOf("सम्झा", "सम्झना", "याद", "दिला", "मलाई", "पछि")
    private val NE_NOTE_WORDS = listOf("नोट", "लेख", "गरिदे", "राखिदे")
}
