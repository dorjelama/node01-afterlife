package dev.node01.afterlife

import java.time.LocalDate

/**
 * What NODE may notice through its front camera, once you allow it on the
 * page: each is a thing the object detector names, held in view for [seenMs],
 * and then not noticed again for [againMs].
 */
enum class Doing(val labels: Set<String>, val seenMs: Long, val againMs: Long, val title: String, val raised: Boolean = false) {
    /** A cup or bottle raised to the face: low on the desk it is only standing there. */
    DRINK(setOf("cup", "bottle", "wine glass"), 1_000L, 10 * MINUTE_MS, "a drink", raised = true),
    PHONE(setOf("cell phone"), 20_000L, 15 * MINUTE_MS, "on the phone"),
    READING(setOf("book"), 60_000L, 30 * MINUTE_MS, "reading"),
}

private const val MINUTE_MS = 60_000L

/**
 * Which allowed [Doing] has just happened, from what the object detector
 * saw in each frame it read -- about one a second -- and how many times today.
 * A thing out of view for under [GAP_MS] still counts as held: detectors miss
 * the odd frame. Main thread only; pure, so the rules are unit-tested.
 */
class Activities {

    /** What the detector found: its label, and how far down the frame its middle is, 0 at the top to 1. */
    data class Seen(val label: String, val y: Float)

    var watched: Set<Doing> = emptySet()

    private val since = HashMap<Doing, Long>()
    private val lastSeen = HashMap<Doing, Long>()
    private val lastNoticed = HashMap<Doing, Long>()

    // shortcut: counts start again when NODE restarts; read them back from the log if that ever matters.
    private val today = HashMap<Doing, Int>()
    private var day: LocalDate? = null

    /** The activity [found] completes, with how many times today it has happened, or null. One at a time. */
    fun see(found: List<Seen>, nowMs: Long, date: LocalDate): Pair<Doing, Int>? {
        if (date != day) {
            today.clear()
            day = date
        }
        var happened: Pair<Doing, Int>? = null
        for (a in watched) {
            if (found.any { it.label in a.labels && (!a.raised || it.y < RAISED) }) {
                // Back after a gap -- or after you were away, and nothing was looked at -- it starts again.
                if (nowMs - (lastSeen[a] ?: NEVER) > GAP_MS) since[a] = nowMs
                lastSeen[a] = nowMs
            } else if (nowMs - (lastSeen[a] ?: NEVER) > GAP_MS) {
                since.remove(a)
            }
            val from = since[a] ?: continue
            if (happened == null && nowMs - from >= a.seenMs && nowMs - (lastNoticed[a] ?: NEVER) >= a.againMs) {
                lastNoticed[a] = nowMs
                happened = a to (today[a] ?: 0) + 1
                today[a] = happened.second
            }
        }
        return happened
    }

    companion object {
        /** Its middle in the top part of the frame: up at your face, not on the desk. */
        const val RAISED = 0.6f
        const val GAP_MS = 3_000L
        private const val NEVER = -1_000_000_000_000L

        /** "DRINK,PHONE", as the page sends it and prefs keep it; unknown names are ignored. */
        fun decode(s: String?): Set<Doing> =
            s.orEmpty().split(',').mapNotNull { n -> Doing.entries.firstOrNull { it.name == n.trim() } }.toSet()

        fun encode(watched: Set<Doing>): String = watched.joinToString(",") { it.name }
    }
}
