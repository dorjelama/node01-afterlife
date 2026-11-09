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
    /** 0 is a closed line, 1 fully open. Blinks multiply into this. */
    val open: Float = 1f,
    val width: Float = 1f,
    val height: Float = 1f,
    /** Upper lid coverage, 0..1: heavy lids read as sleepy. */
    val lid: Float = 0f,
    /** Lower lid lift, 0..1: an arched underside reads as happy. */
    val smile: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    /** 0 is the normal grey-white, 1 is the amber that means "needs you". */
    val amber: Float = 0f,
) {
    /** Moves a fraction [k] (0..1) of the way toward [target]. */
    fun approach(target: EyeParams, k: Float) = EyeParams(
        open = open + (target.open - open) * k,
        width = width + (target.width - width) * k,
        height = height + (target.height - height) * k,
        lid = lid + (target.lid - lid) * k,
        smile = smile + (target.smile - smile) * k,
        gazeX = gazeX + (target.gazeX - gazeX) * k,
        gazeY = gazeY + (target.gazeY - gazeY) * k,
        amber = amber + (target.amber - amber) * k,
    )

    /** Largest single difference, for deciding the face has settled. */
    fun distanceTo(o: EyeParams): Float = max(
        max(max(abs(open - o.open), abs(width - o.width)), max(abs(height - o.height), abs(lid - o.lid))),
        max(max(abs(smile - o.smile), abs(gazeX - o.gazeX)), max(abs(gazeY - o.gazeY), abs(amber - o.amber))),
    )
}

/** The face's vocabulary. Gaze and colour are layered on by [Mood]. */
enum class Expression(val params: EyeParams) {
    NEUTRAL(EyeParams()),

    /** Alert or startled: a little bigger in both directions. */
    WIDE(EyeParams(width = 1.1f, height = 1.15f)),

    HAPPY(EyeParams(smile = 0.5f, height = 0.95f)),

    SLEEPY(EyeParams(lid = 0.55f)),

    /** Asleep: a short horizontal line. */
    CLOSED(EyeParams(open = 0f)),

    /** Concentrating with Claude: narrowed. */
    FOCUSED(EyeParams(width = 1.08f, height = 0.62f, lid = 0.1f)),
}
