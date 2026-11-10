package dev.node01.afterlife

import kotlin.random.Random

/**
 * Moments the companion says something about, unprompted. An [urgent] one --
 * Claude needing you, or finishing -- is said even right after something
 * else, and waits its turn rather than being dropped.
 */
enum class Moment(val cooldownMs: Long, val urgent: Boolean = false) {
    HELLO(10 * 60_000L),

    /** The first hello of the morning. */
    MORNING(12 * 60 * 60_000L),
    NEEDS_YOU(20_000L, urgent = true),
    DONE(20_000L, urgent = true),
    KNOCK(60_000L),
    SHAKE(60_000L),
    PET(30_000L),
    GOODNIGHT(60 * 60_000L),
}

/**
 * The companion's small talk: a short line for a [Moment], or null when it
 * would be too chatty. Unprompted lines are at least [GAP_MS] apart, each
 * moment has its own cooldown, and the same words never come twice running.
 * Claude needing you, or finishing, is worth saying even right after
 * something else. Pure, so the rules are unit-tested; [pick] chooses among
 * the lines, randomly unless a test says otherwise.
 */
class Chatter(private val pick: (Int) -> Int = { Random.nextInt(it) }) {

    private val lastSaid = HashMap<Moment, Long>()
    private var lastAnyMs = NEVER
    private var lastLine: String? = null

    fun line(moment: Moment, lang: Lang, nowMs: Long): String? {
        if (!moment.urgent && nowMs - lastAnyMs < GAP_MS) return null
        if (nowMs - (lastSaid[moment] ?: NEVER) < moment.cooldownMs) return null
        val options = LINES.getValue(lang).getValue(moment)
        var i = pick(options.size).coerceIn(0, options.size - 1)
        if (options[i] == lastLine && options.size > 1) i = (i + 1) % options.size
        lastSaid[moment] = nowMs
        lastAnyMs = nowMs
        lastLine = options[i]
        return options[i]
    }

    companion object {
        /** At least this long between unprompted lines. */
        const val GAP_MS = 20_000L
        private const val NEVER = -1_000_000_000_000L

        val LINES: Map<Lang, Map<Moment, List<String>>> = mapOf(
            Lang.EN to mapOf(
                Moment.HELLO to listOf("Hi!", "Welcome back!", "Hello again!", "There you are!"),
                Moment.MORNING to listOf("Good morning!", "Morning!"),
                Moment.NEEDS_YOU to listOf("Hey! Claude needs you.", "Claude needs you."),
                Moment.DONE to listOf("All done!", "Claude is done."),
                Moment.KNOCK to listOf("Whoa!", "Who's there?", "Hey!"),
                Moment.SHAKE to listOf("Whoa, dizzy!", "Stop shaking me!"),
                Moment.PET to listOf("Hehe.", "Thank you!"),
                Moment.GOODNIGHT to listOf("Good night.", "Sleep well."),
            ),
            Lang.NE to mapOf(
                Moment.HELLO to listOf("नमस्ते!", "फेरि भेटियो!"),
                Moment.MORNING to listOf("शुभ प्रभात!"),
                Moment.NEEDS_YOU to listOf("Claude लाई तपाईं चाहियो।"),
                Moment.DONE to listOf("Claude ले काम सक्यो।", "सकियो!"),
                Moment.KNOCK to listOf("ओहो!", "को हो?"),
                Moment.SHAKE to listOf("रिँगटा लाग्यो!"),
                Moment.PET to listOf("हेहे।", "धन्यवाद!"),
                Moment.GOODNIGHT to listOf("शुभरात्री।"),
            ),
        )
    }
}
