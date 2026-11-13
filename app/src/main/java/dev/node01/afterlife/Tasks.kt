package dev.node01.afterlife

/**
 * Tasks sent from the page: a title and a few lines about it, kept until you
 * mark them done on the phone. Unlike a desk note a task is never due -- it
 * waits for you to ask. Main thread only; the caller persists [encode] and
 * restores with [decode].
 */
class Tasks(tasks: List<Task> = emptyList()) {

    /** [id] is when it was added, made unique, so marking one done can never take another. */
    data class Task(val id: Long, val title: String, val description: String)

    private val tasks = tasks.toMutableList()

    /** Oldest first. */
    val all: List<Task> get() = tasks

    val size get() = tasks.size

    /**
     * Returns the task as stored, or null if the title is empty or [MAX_TASKS]
     * are already open: a full list refuses the new one rather than silently
     * dropping one you still have to do.
     */
    fun add(title: String, description: String, nowMs: Long): Task? {
        if (tasks.size >= MAX_TASKS) return null
        val t = Inbox.clean(title) ?: return null
        val id = maxOf(nowMs, (tasks.maxOfOrNull { it.id } ?: Long.MIN_VALUE) + 1)
        return Task(id, t, Inbox.clean(description, MAX_DESCRIPTION).orEmpty()).also { tasks += it }
    }

    fun complete(id: Long): Boolean = tasks.removeAll { it.id == id }

    /** One task per line: id, title, description, tab-separated; cleaned text holds no tabs or newlines. */
    fun encode(): String = tasks.joinToString("\n") { "${it.id}\t${it.title}\t${it.description}" }

    companion object {
        const val MAX_TASKS = 30
        const val MAX_DESCRIPTION = 500

        fun decode(stored: String?): Tasks = Tasks(
            stored.orEmpty().lines().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                val title = Inbox.clean(parts.getOrNull(1).orEmpty()) ?: return@mapNotNull null
                Task(id, title, Inbox.clean(parts.getOrNull(2).orEmpty(), MAX_DESCRIPTION).orEmpty())
            },
        )
    }
}
