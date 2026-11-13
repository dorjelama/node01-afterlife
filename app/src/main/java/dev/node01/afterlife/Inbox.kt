package dev.node01.afterlife

/**
 * Things waiting to be shown: desk notes from a browser, and reminders,
 * timers and focus sessions set by voice. Each is due at a moment; times are
 * wall-clock milliseconds so they survive a restart. Main thread only; the
 * caller persists [encode] and restores with [decode].
 */
class Inbox(notes: List<Note> = emptyList()) {

    enum class Kind {
        /** From the web page: silent in a dark room, like every other alert. */
        NOTE,

        /** "Remind me in 10 minutes to stretch." */
        REMINDER,

        /** A timer or focus session. "Cancel the timer" removes these. */
        TIMER,

        /** "Wake me at seven": rings on until it is tapped off, dark room or not. */
        ALARM,
    }

    /** [repeatMs] above zero is a nudge that comes round again: "every 45 minutes". */
    data class Note(val dueMs: Long, val text: String, val kind: Kind = Kind.NOTE, val repeatMs: Long = 0L)

    private val notes = notes.sortedBy { it.dueMs }.toMutableList()

    val size get() = notes.size

    /** Returns the note as stored, or null if the text is empty. Oldest give way past [MAX_NOTES]. */
    fun add(text: String, delayMs: Long, nowMs: Long, kind: Kind = Kind.NOTE, repeatMs: Long = 0L): Note? {
        val clean = clean(text) ?: return null
        val note = Note(nowMs + delayMs.coerceIn(0, MAX_DELAY_MS), clean, kind, repeatMs.coerceIn(0, MAX_DELAY_MS))
        notes += note
        notes.sortBy { it.dueMs }
        while (notes.size > MAX_NOTES) notes.removeAt(0)
        return note
    }

    /** The earliest note that is due, if any. */
    fun due(nowMs: Long): Note? = notes.firstOrNull()?.takeIf { it.dueMs <= nowMs }

    /**
     * The due note's turn is over, whether you dismissed it or were not there
     * to see it: a nudge comes round again, anything else is gone for good.
     */
    fun dismiss(nowMs: Long): Boolean {
        val note = due(nowMs) ?: return false
        notes.remove(note)
        if (note.repeatMs > 0) {
            notes += note.copy(dueMs = nowMs + note.repeatMs)
            notes.sortBy { it.dueMs }
        }
        return true
    }

    /** A timer or focus session still counting down. */
    fun timerRunning(nowMs: Long): Boolean = notes.any { it.kind == Kind.TIMER && it.dueMs > nowMs }

    /** Anything "cancel" would call off: a running timer, or a nudge that keeps coming back. */
    fun cancellable(nowMs: Long): Boolean = timerRunning(nowMs) || notes.any { it.repeatMs > 0 }

    /** How long the soonest timer still has to run, or null if none is. */
    fun timerLeft(nowMs: Long): Long? =
        notes.filter { it.kind == Kind.TIMER && it.dueMs > nowMs }.minOfOrNull { it.dueMs }?.minus(nowMs)

    /**
     * Calls off what is still to come: timers counting down and nudges that
     * repeat. One "cancel" for both, so there is nothing to remember about
     * which word stops which.
     */
    fun cancelTimers(nowMs: Long): Boolean =
        notes.removeAll { (it.kind == Kind.TIMER && it.dueMs > nowMs) || it.repeatMs > 0 }

    /**
     * One note per line: due time, kind, repeat, text, tab-separated. [clean]
     * keeps tabs and newlines out of the text, so the text is always the last
     * field and can never be mistaken for one of the others.
     */
    fun encode(): String = notes.joinToString("\n") { "${it.dueMs}\t${it.kind}\t${it.repeatMs}\t${it.text}" }

    companion object {
        const val MAX_CHARS = 140
        const val MAX_NOTES = 8
        const val MAX_DELAY_MS = 24 * 60 * 60_000L

        fun decode(stored: String?): Inbox = Inbox(
            stored.orEmpty().lines().mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                val due = parts[0].toLongOrNull() ?: return@mapNotNull null
                val kind = parts.getOrNull(1)?.let { k -> Kind.values().firstOrNull { it.name == k } }
                // Four fields is this version. Three is from before notes repeated,
                // two from before they had a kind at all; both still load.
                val repeat = if (parts.size == 4) parts[2].toLongOrNull() ?: 0L else 0L
                val text = when {
                    parts.size == 4 -> parts[3]
                    kind != null -> parts.getOrNull(2)
                    else -> line.substringAfter('\t')
                }
                clean(text.orEmpty())?.let { Note(due, it, kind ?: Kind.NOTE, repeat) }
            },
        )

        /**
         * Time left, for the corner of the face: `4:05` under an hour, ticking
         * in seconds, and whole minutes beyond that -- a long wait does not
         * need a second hand. Rounded up, so it reads 0:01 until it fires.
         */
        fun countdown(msLeft: Long): String {
            val seconds = ((msLeft + 999) / 1_000).coerceAtLeast(0)
            if (seconds >= 3_600) return "${seconds / 60}"
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }

        /** One line, no control characters, trimmed and capped. Null if nothing is left. */
        internal fun clean(text: String, maxChars: Int = MAX_CHARS): String? =
            text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
                .replace(Regex(" {2,}"), " ").trim().take(maxChars).trim()
                .takeIf { it.isNotEmpty() }
    }
}
