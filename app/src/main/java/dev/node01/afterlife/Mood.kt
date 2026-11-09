package dev.node01.afterlife

/** Where the eyes point. */
sealed interface Gaze {
    data class At(val x: Float, val y: Float) : Gaze

    /** Small random glances around the room: nobody to look at. */
    data object Wander : Gaze

    /** Mostly at one point, with a glance at another now and then. */
    data class Mostly(val x: Float, val y: Float, val glanceX: Float, val glanceY: Float) : Gaze
}

/** Text under the eyes. Formatted by the activity, which owns the strings. */
sealed interface Caption {
    data class Note(val text: String) : Caption
    data class NeedsYou(val project: String, val others: Int) : Caption
    data class Done(val project: String, val others: Int) : Caption
}

/** Backlight levels. FULL means the phone's own brightness setting. */
enum class Light { FULL, DOZE, MIN }

/** Everything shown, decided in one place. */
data class Look(
    val expression: Expression,
    val gaze: Gaze,
    val amber: Boolean,
    /** A "!!" beside the eyes: Claude is waiting for your approval. */
    val exclaim: Boolean,
    /** True while a desk knock is fresh; the face hops once as it starts. */
    val startled: Boolean,
    val caption: Caption?,
    val light: Light,
)

/**
 * NODE's whole personality, as a pure function of what it senses. The
 * activity gathers [Inputs]; [decide] says what to show. Durations arrive
 * as "ago" values so this needs no clock and is tested directly.
 */
object Mood {

    data class Inputs(
        val present: Boolean,
        /** How long you have been in view; 0 when away. */
        val presentForMs: Long,
        /** How long since you were last seen; 0 when present. */
        val awayForMs: Long,
        /** How long the absence before this presence lasted. */
        val lastAbsenceMs: Long,
        val faceX: Float,
        val faceY: Float,
        val dark: Boolean,
        val knockAgoMs: Long,
        val petAgoMs: Long,
        val claude: ClaudeSessions.Summary?,
        val note: String?,
    )

    fun decide(i: Inputs): Look {
        val c = i.claude
        val caption: Caption? = when {
            i.note != null -> Caption.Note(i.note)
            c?.state == ClaudeSessions.State.NEEDS_YOU -> Caption.NeedsYou(c.project, c.others)
            c?.state == ClaudeSessions.State.DONE -> Caption.Done(c.project, c.others)
            else -> null
        }
        val amber = caption is Caption.Note || caption is Caption.NeedsYou
        // Shown whenever Claude waits on you, even while a desk note has the caption.
        val exclaim = c?.state == ClaudeSessions.State.NEEDS_YOU
        val startled = i.knockAgoMs < KNOCK_MS

        val face = Gaze.At(i.faceX, i.faceY)
        val atYou = if (i.present) face else AHEAD
        val pc = Gaze.At(PC_SIDE * PC_GAZE_X, PC_GAZE_Y)

        val (expression, gaze) = when {
            startled -> Expression.WIDE to atYou
            amber -> Expression.WIDE to atYou
            i.petAgoMs < PET_MS -> Expression.HAPPY to atYou
            c?.state == ClaudeSessions.State.DONE && !c.seen ->
                Expression.HAPPY to (if (i.present) face else Gaze.Wander)
            i.present && i.presentForMs < GREET_MS && i.lastAbsenceMs >= SEARCH_MS -> Expression.WIDE to face
            c?.state == ClaudeSessions.State.WORKING ->
                Expression.FOCUSED to (if (i.present) Gaze.Mostly(pc.x, pc.y, face.x, face.y) else pc)
            i.present -> (if (i.dark) Expression.SLEEPY else Expression.NEUTRAL) to face
            i.dark -> Expression.CLOSED to RESTING
            i.awayForMs < SEARCH_MS -> Expression.NEUTRAL to Gaze.Wander
            i.awayForMs < SLEEP_MS -> Expression.SLEEPY to DROOP
            else -> Expression.CLOSED to RESTING
        }

        val light = when {
            // Visible from across the room -- but not a beacon in a dark one.
            amber -> if (i.dark) Light.DOZE else Light.FULL
            i.present -> Light.FULL
            expression == Expression.CLOSED -> Light.MIN
            i.awayForMs < SEARCH_MS -> Light.FULL
            else -> Light.DOZE
        }
        return Look(expression, gaze, amber, exclaim, startled, caption, light)
    }

    /** A face seen this recently still counts as present: detection flickers. */
    const val FACE_GRACE_MS = 1_500L

    const val KNOCK_MS = 1_200L
    const val PET_MS = 1_500L

    /** How long the wide-eyed hello lasts after a real absence. */
    const val GREET_MS = 1_500L

    /** Away this long: stop searching the room and get sleepy. Also what counts as a real absence. */
    const val SEARCH_MS = 2 * 60_000L

    /** Away this long: asleep, backlight at the panel minimum. */
    const val SLEEP_MS = 10 * 60_000L

    /**
     * Which side of NODE the PC is on, as seen by you facing the screen:
     * 1 for right, -1 for left. The eyes look that way while Claude works.
     */
    const val PC_SIDE = 1f
    private const val PC_GAZE_X = 0.8f
    private const val PC_GAZE_Y = 0.25f

    private val AHEAD = Gaze.At(0f, 0f)
    private val DROOP = Gaze.At(0f, 0.3f)
    private val RESTING = Gaze.At(0f, 0.35f)
}
