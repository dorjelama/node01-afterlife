package dev.node01.afterlife

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One aquarium and its care: what is in it, how often each [Job] is due, and
 * when each was last done. NODE brings up an overdue job, logs one you say you
 * did, and guesses from those habits how the water is doing -- never from a
 * water test, which it cannot do, so it never claims a number.
 *
 * Immutable: every change returns a new Tank. Times are wall-clock
 * milliseconds, so they survive a restart; the caller persists [encode].
 */
data class Tank(
    val name: String = "",
    val litres: Int = 0,
    val kind: String = DEFAULT_KIND,
    /** The fish, shrimp and snails, as you would say them. */
    val livestock: String = "",
    /** When NODE started keeping this tank: a job with no record counts from here. */
    val startMs: Long = 0L,
    val everyDays: Map<Job, Int> = emptyMap(),
    val lastMs: Map<Job, Long> = emptyMap(),
    /** When each job was last brought up, so an overdue one is brought up once a day, not every minute. */
    val raisedMs: Map<Job, Long> = emptyMap(),
    /** Feeding times over the last [KEEP_FEEDS_MS], oldest first. */
    val feeds: List<Long> = emptyList(),
) {
    /** The jobs of a low-tech planted tank, with how often each comes round by default. */
    enum class Job(val defaultDays: Int) {
        FEED(1), WATER_CHANGE(7), FERTILIZE(7), TRIM(14), FILTER(30),
    }

    /** How the water is likely doing, judged from care alone. */
    enum class Risk { LOW, BUILDING, HIGH }

    fun every(job: Job): Int = everyDays[job] ?: job.defaultDays

    /** When [job] comes due: [every] days after it was last done, or after NODE started keeping the tank. */
    fun dueMs(job: Job): Long = (lastMs[job] ?: startMs) + every(job) * DAY_MS

    /** Whole days since [job] was last done, or since NODE started keeping the tank. */
    fun daysSince(job: Job, nowMs: Long): Int = ((nowMs - (lastMs[job] ?: startMs)) / DAY_MS).toInt().coerceAtLeast(0)

    fun done(job: Job, nowMs: Long): Tank = copy(
        lastMs = lastMs + (job to nowMs),
        feeds = if (job == Job.FEED) feeds.filter { nowMs - it < KEEP_FEEDS_MS } + nowMs else feeds,
    )

    /** The feeding within [FEED_GAP_MS] before [nowMs], or null: another one so soon is likely too much food. */
    fun fedWithin(nowMs: Long): Long? = feeds.lastOrNull()?.takeIf { nowMs - it in 0 until FEED_GAP_MS }

    /** Jobs past due, the one due longest ago first. */
    fun overdue(nowMs: Long): List<Job> = Job.entries.filter { dueMs(it) <= nowMs }.sortedBy { dueMs(it) }

    /** The overdue job to bring up now: the first one not already brought up today, or null. */
    fun toRaise(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Job? {
        val today = day(nowMs, zone)
        return overdue(nowMs).firstOrNull { job -> raisedMs[job]?.let { day(it, zone) } != today }
    }

    fun raised(job: Job, nowMs: Long): Tank = copy(raisedMs = raisedMs + (job to nowMs))

    /**
     * How the water is likely doing, and why. From care habits only: water
     * changes against their interval, how often the fish are fed, and the
     * camera's word that the water looks [greener] than last week. No test
     * kit, so ammonia and nitrate are never measured.
     */
    fun risk(nowMs: Long, greener: Boolean = false): Pair<Risk, List<String>> {
        var score = 0
        val reasons = mutableListOf<String>()
        val since = daysSince(Job.WATER_CHANGE, nowMs)
        val every = every(Job.WATER_CHANGE)
        if (since >= every) {
            score += if (since >= 2 * every) 2 else 1
            reasons += "$since days since the last water change"
        }
        val perDay = feeds.count { nowMs - it in 0 until FEED_WINDOW_MS } * DAY_MS.toDouble() / FEED_WINDOW_MS
        if (perDay > MAX_FEEDS_A_DAY) {
            score++
            reasons += "fed about ${Math.round(perDay)} times a day"
        }
        if (greener) {
            score++
            reasons += "the water looks greener than last week"
        }
        val risk = when (score) {
            0 -> Risk.LOW
            1 -> Risk.BUILDING
            else -> Risk.HIGH
        }
        return risk to reasons
    }

    /**
     * What NODE knows about the tank, as notes for the model to answer "how is the tank?" from. [camera] is what the
     * rear camera last made of it, if anything, and [greener] its word that the water is turning green.
     */
    fun notes(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), camera: String? = null, greener: Boolean = false): String {
        val about = listOfNotNull(
            kind.ifBlank { null },
            litres.takeIf { it > 0 }?.let { "$it litres" },
            livestock.ifBlank { null }?.let { "with $it" },
        ).joinToString(", ")
        val today = day(nowMs, zone)
        val fedToday = feeds.filter { day(it, zone) == today }.map { clock(it, zone) }
        val jobs = Job.entries.filter { it != Job.FEED }.joinToString(" ") { job ->
            val last = lastMs[job]?.let { "${daysSince(job, nowMs)} days ago" } ?: "not recorded yet"
            "${JOB_NAMES.getValue(job)}: last $last, due every ${every(job)} days."
        }
        val (risk, why) = risk(nowMs, greener)
        return listOfNotNull(
            "The aquarium: $about.",
            if (fedToday.isEmpty()) "Not fed yet today." else "Fed today at ${fedToday.joinToString(" and ")}.",
            jobs,
            camera,
            "Water quality, guessed from care habits only, with no water tests: ${risk.name.lowercase()}" +
                (if (why.isEmpty()) "." else ", because " + why.joinToString(" and ") + "."),
        ).joinToString(" ")
    }

    /** One `key=value` per line; cleaned text holds no newlines. */
    fun encode(): String = buildList {
        add("name=$name")
        add("litres=$litres")
        add("kind=$kind")
        add("livestock=$livestock")
        add("start=$startMs")
        for ((job, days) in everyDays) add("every.${job.name}=$days")
        for ((job, ms) in lastMs) add("last.${job.name}=$ms")
        for ((job, ms) in raisedMs) add("raised.${job.name}=$ms")
        add("feeds=${feeds.joinToString(",")}")
    }.joinToString("\n")

    /**
     * This tank set up from the page's form: `name`, `litres`, `kind`, `livestock`, and `every.<JOB>` in days. A
     * missing or unreadable field keeps what was there.
     */
    fun setUp(form: Map<String, String>): Tank = copy(
        name = form["name"]?.let { Inbox.clean(it, MAX_TEXT).orEmpty() } ?: name,
        litres = form["litres"]?.trim()?.toIntOrNull()?.coerceIn(0, MAX_LITRES) ?: litres,
        kind = form["kind"]?.let { Inbox.clean(it, MAX_TEXT) ?: DEFAULT_KIND } ?: kind,
        livestock = form["livestock"]?.let { Inbox.clean(it, MAX_TEXT).orEmpty() } ?: livestock,
        everyDays = everyDays + Job.entries.mapNotNull { job ->
            form["every.${job.name}"]?.trim()?.toIntOrNull()?.coerceIn(1, MAX_DAYS)?.let { job to it }
        },
    )

    companion object {
        const val DEFAULT_KIND = "low-tech planted"
        const val DAY_MS = 24 * 3_600_000L

        /** A second feeding sooner than this gets a word about overfeeding. */
        const val FEED_GAP_MS = 4 * 3_600_000L
        private const val KEEP_FEEDS_MS = 7 * DAY_MS
        private const val FEED_WINDOW_MS = 3 * DAY_MS
        private const val MAX_FEEDS_A_DAY = 2.0
        private const val MAX_TEXT = 140
        private const val MAX_LITRES = 10_000
        private const val MAX_DAYS = 365

        /** How each job is named in the notes the model reads. */
        val JOB_NAMES = mapOf(
            Job.FEED to "Feeding", Job.WATER_CHANGE to "Water change", Job.FERTILIZE to "Fertiliser",
            Job.TRIM to "Plant trim", Job.FILTER to "Filter clean",
        )

        /** A new tank, kept from [nowMs]. */
        fun start(nowMs: Long) = Tank(startMs = nowMs)

        /** Null if nothing was stored: no tank has been set up. Unreadable lines are skipped. */
        fun decode(stored: String?): Tank? {
            if (stored.isNullOrBlank()) return null
            val fields = stored.lines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
            fun <T> perJob(prefix: String, read: (String) -> T?): Map<Job, T> = Job.entries.mapNotNull { job ->
                fields["$prefix.${job.name}"]?.let(read)?.let { job to it }
            }.toMap()
            return Tank(
                name = fields["name"].orEmpty(),
                litres = fields["litres"]?.toIntOrNull() ?: 0,
                kind = fields["kind"] ?: DEFAULT_KIND,
                livestock = fields["livestock"].orEmpty(),
                startMs = fields["start"]?.toLongOrNull() ?: 0L,
                everyDays = perJob("every") { it.toIntOrNull() },
                lastMs = perJob("last") { it.toLongOrNull() },
                raisedMs = perJob("raised") { it.toLongOrNull() },
                feeds = fields["feeds"].orEmpty().split(',').mapNotNull { it.toLongOrNull() },
            )
        }

        private fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        private fun clock(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let {
            Assistant.clockEn(it.hour, it.minute)
        }
    }
}
