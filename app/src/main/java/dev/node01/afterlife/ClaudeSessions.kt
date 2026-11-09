package dev.node01.afterlife

/**
 * What every Claude Code session on the PC is doing, rebuilt from the events
 * its hooks POST to NODE. Main thread only.
 *
 * Only four fields of a hook event are ever looked at -- the event name, the
 * session id, the working folder and the notification type. Prompts and tool
 * data never reach this class.
 *
 * Hooks are best-effort: an event can be lost, and a killed terminal sends no
 * SessionEnd. So every state expires rather than being claimed forever.
 */
class ClaudeSessions {

    enum class State { WORKING, DONE, NEEDS_YOU }

    /**
     * The one thing worth showing. [others] counts further sessions in the same
     * state; [seen] is whether you have looked at NODE since it got there.
     */
    data class Summary(val state: State, val project: String, val others: Int, val seen: Boolean)

    private class Session(
        var state: State,
        var project: String,
        var changedMs: Long,
        var seenMs: Long?,
    )

    private val sessions = LinkedHashMap<String, Session>()

    /**
     * Applies one hook event. Returns the session's new state when the event
     * moved it into a different one -- the moment worth a chirp -- or null.
     */
    fun onEvent(fields: Map<String, String>, nowMs: Long): State? {
        val id = fields[SESSION_ID] ?: return null
        val event = fields[EVENT] ?: return null
        if (event == "SessionEnd") {
            sessions.remove(id)
            return null
        }
        val current = sessions[id]
        val next = when (event) {
            "UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolUseFailure" -> State.WORKING
            "PermissionRequest" -> State.NEEDS_YOU
            "Stop" -> State.DONE
            "Notification" -> when (fields[NOTIFICATION_TYPE]) {
                in NEEDS_YOU_TYPES -> State.NEEDS_YOU
                // Claude went quiet without a Stop reaching us. Never downgrade
                // a pending approval to "done".
                "idle_prompt" -> if (current?.state == State.WORKING) State.DONE else null
                else -> null
            }
            else -> null
        } ?: return null

        val project = projectOf(fields[CWD])
        if (current == null) {
            sessions[id] = Session(next, project, nowMs, null)
            return next
        }
        current.project = project
        if (current.state == next) {
            // Still working: keep it from expiring. A repeated Stop is not news.
            current.changedMs = nowMs
            return null
        }
        current.state = next
        current.changedMs = nowMs
        current.seenMs = null
        return next
    }

    /**
     * You have been in view since [inViewSinceMs]. A change counts as seen once
     * you have had it in view for [LOOK_MS] -- so "done" arriving while you sit
     * there still gets its moment, instead of being seen the instant it lands.
     */
    fun acknowledge(nowMs: Long, inViewSinceMs: Long) {
        for (s in sessions.values) {
            if (s.seenMs == null && maxOf(s.changedMs, inViewSinceMs) + LOOK_MS <= nowMs) s.seenMs = nowMs
        }
    }

    /** Most urgent first: needs you, then done, then working. Null when idle. */
    fun summary(nowMs: Long): Summary? {
        sessions.values.removeAll { expired(it, nowMs) }
        val top = sessions.values.maxWithOrNull(
            compareBy<Session>({ rank(it.state) }, { if (it.seenMs == null) 1 else 0 }, { it.changedMs }),
        ) ?: return null
        val others = sessions.values.count { it !== top && it.state == top.state }
        return Summary(top.state, top.project, others, top.seenMs != null)
    }

    private fun expired(s: Session, nowMs: Long): Boolean = when (s.state) {
        // No event for this long: the session was closed or the PC slept.
        State.WORKING -> nowMs - s.changedMs > WORKING_EXPIRY_MS
        // Seen, and the caption has had its moment.
        State.DONE -> s.seenMs?.let { nowMs - it > DONE_LINGER_MS } ?: (nowMs - s.changedMs > STALE_MS)
        State.NEEDS_YOU -> nowMs - s.changedMs > STALE_MS
    }

    private fun rank(state: State) = when (state) {
        State.WORKING -> 0
        State.DONE -> 1
        State.NEEDS_YOU -> 2
    }

    companion object {
        const val EVENT = "hook_event_name"
        const val SESSION_ID = "session_id"
        const val CWD = "cwd"
        const val NOTIFICATION_TYPE = "notification_type"

        /** The only fields NODE reads from a hook event. */
        val FIELDS = setOf(EVENT, SESSION_ID, CWD, NOTIFICATION_TYPE)

        /** Notifications that mean Claude is blocked on you. */
        private val NEEDS_YOU_TYPES = setOf("permission_prompt", "elicitation_dialog", "agent_needs_input")

        /** Working with no event this long reads as unknown, not busy. */
        const val WORKING_EXPIRY_MS = 20 * 60_000L

        /** In view this long after a change, and it counts as seen. */
        const val LOOK_MS = 2_000L

        /** How long "done" stays on screen after you have seen it. */
        const val DONE_LINGER_MS = 10_000L

        /** Nothing is claimed for longer than this, seen or not. */
        const val STALE_MS = 12 * 60 * 60_000L

        /** "C:\Users\me\code\node01" -> "node01". Never more than one line, never too long for the caption. */
        internal fun projectOf(cwd: String?): String =
            cwd?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.filterNot { it.isISOControl() }?.take(MAX_PROJECT_CHARS)
                ?.takeIf { it.isNotBlank() } ?: "claude"

        private const val MAX_PROJECT_CHARS = 40
    }
}
