package dev.node01.afterlife

import kotlin.math.abs
import kotlin.math.max

/**
 * The shape of the face as plain numbers, so expressions can be eased into
 * one another and tested without a View.
 *
 * Sizes are multiples of the eye's base size. Lids are fractions of the eye's
 * current height. Gaze is -1..1 in screen terms: x to the right, y down.
 */
data class EyeParams(
    /** 0 is closed -- a single thick line -- and 1 fully open. Blinks multiply into this. */
    val open: Float = 1f,
    val width: Float = 1f,
    val height: Float = 1f,
    /** Upper lid coverage, 0..1: heavy lids read as sleepy. */
    val lid: Float = 0f,
    /**
     * Tilts the upper lid. Positive drops it toward the outer corners, which
     * reads as sad or tired; negative drops it toward the nose, which reads as cross.
     */
    val slant: Float = 0f,
    /** Lower lid pushed up, 0..1 of the eye's height, as a cheek does in a smile or a squint. */
    val squint: Float = 0f,
    /** A head tilt: positive makes the right eye bigger and higher, negative the left. */
    val tilt: Float = 0f,
    /**
     * How a closing eye's line bends: 0 stays flat, as in a blink; positive
     * arches it into the happy "^", negative sags it into the sleeping "◡".
     */
    val arc: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    /** 0 is the normal eye colour, 1 is the amber that means "needs you". */
    val amber: Float = 0f,
    /** The "!!" beside the eyes while Claude waits for approval: 0 hidden, 1 shown. */
    val exclaim: Float = 0f,
) {
    /** Moves a fraction [k] (0..1) of the way toward [target]. */
    fun approach(target: EyeParams, k: Float) = EyeParams(
        open = open + (target.open - open) * k,
        width = width + (target.width - width) * k,
        height = height + (target.height - height) * k,
        lid = lid + (target.lid - lid) * k,
        slant = slant + (target.slant - slant) * k,
        squint = squint + (target.squint - squint) * k,
        tilt = tilt + (target.tilt - tilt) * k,
        arc = arc + (target.arc - arc) * k,
        gazeX = gazeX + (target.gazeX - gazeX) * k,
        gazeY = gazeY + (target.gazeY - gazeY) * k,
        amber = amber + (target.amber - amber) * k,
        exclaim = exclaim + (target.exclaim - exclaim) * k,
    )

    /** Largest single difference, for deciding the face has settled. */
    fun distanceTo(o: EyeParams): Float {
        var d = abs(open - o.open)
        d = max(d, abs(width - o.width))
        d = max(d, abs(height - o.height))
        d = max(d, abs(lid - o.lid))
        d = max(d, abs(slant - o.slant))
        d = max(d, abs(squint - o.squint))
        d = max(d, abs(tilt - o.tilt))
        d = max(d, abs(arc - o.arc))
        d = max(d, abs(gazeX - o.gazeX))
        d = max(d, abs(gazeY - o.gazeY))
        d = max(d, abs(amber - o.amber))
        return max(d, abs(exclaim - o.exclaim))
    }
}

/** The face's held expressions. Gaze and colour are layered on by [Mood]. */
enum class Expression(val params: EyeParams) {
    NEUTRAL(EyeParams()),

    /** Alert or startled: a little bigger in both directions. */
    WIDE(EyeParams(width = 1.08f, height = 1.14f)),

    /** Eyes closed into happy "^ ^" arches. */
    HAPPY(EyeParams(open = 0f, arc = 1f)),

    /** Heavy lids that droop at the outer corners. */
    SLEEPY(EyeParams(lid = 0.5f, slant = 0.35f)),

    /** Asleep: eyes closed into soft "◡ ◡" curves. */
    CLOSED(EyeParams(open = 0f, arc = -0.9f)),

    /** Concentrating with Claude: narrowed. */
    FOCUSED(EyeParams(width = 1.06f, height = 0.62f, lid = 0.1f)),

    /** Listening: head tilted, one eye bigger than the other. */
    CURIOUS(EyeParams(tilt = 1f, height = 1.04f)),

    /** Inner corners raised, outer corners dropped. */
    SAD(EyeParams(height = 0.9f, lid = 0.12f, slant = 1f)),

    /** Lids pulled down toward the nose. */
    ANGRY(EyeParams(height = 0.88f, lid = 0.14f, slant = -1f)),

    /** Suspicious, or smug: lids down, cheeks up. */
    SQUINT(EyeParams(height = 0.92f, lid = 0.22f, squint = 0.25f)),
}

/**
 * One-shot animations, played over whatever expression is held and gone by
 * themselves when done. Each is a pure function of its own progress, so the
 * face needs no timers to run one.
 */
enum class Act(val ms: Long) {
    /** Eyes pop into hearts that beat, then back. */
    LOVE(2_600L),

    /** Woozy: the head lolls round under heavy, uneven lids, then shakes it off. */
    DIZZY(2_800L),

    /** One eye closes into a "^" for a moment. */
    WINK(800L),

    /** Happy "^ ^" eyes, bouncing. */
    JOY(1_500L),

    /** A quick shake of the head. */
    NO(750L),

    /** Looks left, then right, then back. */
    SCAN(2_400L),

    /** Z's float up from a sleeping face. */
    ZZZ(3_400L),
}
