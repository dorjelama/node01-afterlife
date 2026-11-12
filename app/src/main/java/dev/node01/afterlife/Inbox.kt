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
    }

    data class Note(val dueMs: Long, val text: String, val kind: Kind = Kind.NOTE)

    private val notes = notes.sortedBy { it.dueMs }.toMutableList()

    val size get() = notes.size

    /** Returns the note as stored, or null if the text is empty. Oldest give way past [MAX_NOTES]. */
    fun add(text: String, delayMs: Long, nowMs: Long, kind: Kind = Kind.NOTE): Note? {
        val clean = clean(text) ?: return null
        val note = Note(nowMs + delayMs.coerceIn(0, MAX_DELAY_MS), clean, kind)
        notes += note
        notes.sortBy { it.dueMs }
        while (notes.size > MAX_NOTES) notes.removeAt(0)
        return note
    }

    /** The earliest note that is due, if any. */
    fun due(nowMs: Long): Note? = notes.firstOrNull()?.takeIf { it.dueMs <= nowMs }

    /** Dismisses the note [due] would return. */
    fun dismiss(nowMs: Long): Boolean = due(nowMs)?.let { notes.remove(it) } ?: false

    /** A timer or focus session still counting down. */
    fun timerRunning(nowMs: Long): Boolean = notes.any { it.kind == Kind.TIMER && it.dueMs > nowMs }

    /** How long the soonest timer still has to run, or null if none is. */
    fun timerLeft(nowMs: Long): Long? =
        notes.filter { it.kind == Kind.TIMER && it.dueMs > nowMs }.minOfOrNull { it.dueMs }?.minus(nowMs)

    /** Removes timers still counting down. True if there was one. */
    fun cancelTimers(nowMs: Long): Boolean = notes.removeAll { it.kind == Kind.TIMER && it.dueMs > nowMs }

    /** One note per line: due time, kind, text, tab-separated. [clean] keeps tabs and newlines out of the text. */
    fun encode(): String = notes.joinToString("\n") { "${it.dueMs}\t${it.kind}\t${it.text}" }

    companion object {
        const val MAX_CHARS = 140
        const val MAX_NOTES = 8
        const val MAX_DELAY_MS = 24 * 60 * 60_000L

        fun decode(stored: String?): Inbox = Inbox(
            stored.orEmpty().lines().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                val due = parts[0].toLongOrNull() ?: return@mapNotNull null
                // Lines from before notes had a kind are "due<TAB>text".
                val kind = parts.getOrNull(1)?.let { k -> Kind.values().firstOrNull { it.name == k } }
                val text = if (kind != null) parts.getOrNull(2) else line.substringAfter('\t')
                clean(text.orEmpty())?.let { Note(due, it, kind ?: Kind.NOTE) }
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
        internal fun clean(text: String): String? =
            text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
                .replace(Regex(" {2,}"), " ").trim().take(MAX_CHARS).trim()
                .takeIf { it.isNotEmpty() }
    }
}
