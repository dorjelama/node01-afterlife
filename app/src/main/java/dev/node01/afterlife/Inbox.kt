package dev.node01.afterlife

/**
 * Notes sent to the desk from a browser, each due now or a few minutes from
 * now. Times are wall-clock milliseconds so a note survives a restart. Main
 * thread only; the caller persists [encode] and restores with [decode].
 */
class Inbox(notes: List<Note> = emptyList()) {

    data class Note(val dueMs: Long, val text: String)

    private val notes = notes.sortedBy { it.dueMs }.toMutableList()

    val size get() = notes.size

    /** Returns the note as stored. Oldest notes give way past [MAX_NOTES]. */
    fun add(text: String, inMinutes: Int, nowMs: Long): Note? {
        val clean = clean(text) ?: return null
        val note = Note(nowMs + inMinutes.coerceIn(0, MAX_MINUTES) * 60_000L, clean)
        notes += note
        notes.sortBy { it.dueMs }
        while (notes.size > MAX_NOTES) notes.removeAt(0)
        return note
    }

    /** The earliest note that is due, if any. */
    fun due(nowMs: Long): Note? = notes.firstOrNull()?.takeIf { it.dueMs <= nowMs }

    /** Dismisses the note [due] would return. */
    fun dismiss(nowMs: Long): Boolean = due(nowMs)?.let { notes.remove(it) } ?: false

    /** One note per line: due time, a tab, the text. [clean] keeps both out of the text. */
    fun encode(): String = notes.joinToString("\n") { "${it.dueMs}\t${it.text}" }

    companion object {
        const val MAX_CHARS = 140
        const val MAX_NOTES = 5
        const val MAX_MINUTES = 24 * 60

        fun decode(stored: String?): Inbox = Inbox(
            stored.orEmpty().lines().mapNotNull { line ->
                val due = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                clean(line.substringAfter('\t'))?.let { Note(due, it) }
            },
        )

        /** One line, no control characters, trimmed and capped. Null if nothing is left. */
        internal fun clean(text: String): String? =
            text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
                .replace(Regex(" {2,}"), " ").trim().take(MAX_CHARS).trim()
                .takeIf { it.isNotEmpty() }
    }
}
