package dev.node01.afterlife

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

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

    /** Working out an answer: [FaceView] streams the eyes' dots into a cat that walks until it is done. */
    THINKING(EyeParams()),
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

    // While Claude works, now and then, so the focused stare never sets.

    /** Reads three lines on the PC: along, back, along the next one down. */
    READ(3_400L),

    /** Looks up and away, eyes a little wider, then back to the PC. */
    THINK(2_200L),

    /** Narrows harder for a moment, then eases off. */
    NARROW(1_600L),

    /** Two small nods at the PC: got it. */
    NOD(1_100L),

    /** Head tilts, eyes squint a little: hmm, what is this? */
    HMM(2_000L),
}

/**
 * The cat NODE becomes while it thinks, as plain dots: each of [FRAMES] is a
 * pose's lit (col, row) offsets from its top-left, [W] by [H], facing right.
 * 0 to 3 are its walk. [CROUCH] and [SIT] are poses, each followed by the same
 * with the tail's tip moved, to flick it.
 */
object CatShape {
    const val W = 22
    const val H = 14
    const val CROUCH = 4
    const val SIT = 6

    /** Walking: head and ears, body, and a tail curling up behind. Legs are added per step. */
    private val BODY = listOf(
        "...............#...#..",
        "....##.........##.##..",
        "...#..#........#####..",
        "...#..........#######.",
        "..##..........####.##.",
        "..##..........#######.",
        "..##.###############..",
        "...###############....",
        "....##############....",
        ".....############.....",
    )

    /** Rows 1 and 2 with the tail's tip leant a dot forward, for every other pair of steps. */
    private val SWAY = listOf(".....##........##.##..", "...#...#.......#####..")

    /** Where each leg meets the body: back near, back far, front far, front near. */
    private val HIPS = intArrayOf(5, 7, 14, 16)
    private val LEG_BACK = intArrayOf(0, 0, -1, -1)
    private val LEG_DOWN = intArrayOf(0, 0, 0, 0)
    private val LEG_FORWARD = intArrayOf(0, 0, 1, 1)
    private const val LEG_ROWS = 4

    /** Low to the ground, tail straight out behind: about to pounce. */
    private val CROUCHING = listOf(
        "......................",
        "......................",
        "...............#...#..",
        "...............##.##..",
        "...............#####..",
        "..............#######.",
        "..............####.##.",
        "..............#######.",
        ".....###############..",
        "#..###############....",
        ".###.#############....",
        ".....############.....",
        ".....#.#.......#.#....",
        "....##.##......##.##..",
    )
    private val CROUCHING_FLICK = mapOf(9 to "...###############....", 10 to "####.#############....")

    /** Sitting up, tail curled along the ground. */
    private val SITTING = listOf(
        "...............#...#..",
        "...............##.##..",
        "...............#####..",
        "..............#######.",
        "..............####.##.",
        "..............#######.",
        "..............######..",
        "............#######...",
        "...........########...",
        "..........#########...",
        ".........##########...",
        "...#.....#######.##...",
        "...#.....#######.##...",
        "....############.###..",
    )
    private val SITTING_FLICK = mapOf(
        11 to "..#......#######.##...",
        12 to "..#......#######.##...",
        13 to "...#############.###..",
    )

    val FRAMES: List<IntArray> = listOf(
        listOf(LEG_BACK, LEG_FORWARD, LEG_BACK, LEG_FORWARD),
        List(4) { LEG_DOWN },
        listOf(LEG_FORWARD, LEG_BACK, LEG_FORWARD, LEG_BACK),
        List(4) { LEG_DOWN },
    ).mapIndexed { step, legs ->
        // The walk: legs reach, pass under, reach the other way, pass under.
        val rows = BODY.mapIndexed { r, body -> if (step >= 2 && r in 1..2) SWAY[r - 1] else body } +
            List(LEG_ROWS) { ".".repeat(W) }
        val grid = rows.map { StringBuilder(it) }
        for (leg in legs.indices) {
            val reach = legs[leg]
            for (r in 0 until LEG_ROWS) grid[BODY.size + r][HIPS[leg] + reach[r]] = '#'
            // The paw, pointing the way it walks.
            grid[BODY.size + LEG_ROWS - 1][HIPS[leg] + reach[LEG_ROWS - 1] + 1] = '#'
        }
        dots(grid.map { it.toString() })
    } + listOf(
        dots(CROUCHING), dots(flicked(CROUCHING, CROUCHING_FLICK)),
        dots(SITTING), dots(flicked(SITTING, SITTING_FLICK)),
    )

    private fun flicked(rows: List<String>, changes: Map<Int, String>) = rows.mapIndexed { r, row -> changes[r] ?: row }

    private fun dots(rows: List<String>): IntArray {
        val out = ArrayList<Int>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch == '#') {
                    out += c
                    out += r
                }
            }
        }
        return out.toIntArray()
    }
}

/**
 * The cat at play, a step at a time, its left column kept between [lo] and
 * [hi]: it walks to and fro, stopping and turning at the edges, and now and
 * then pauses to flick its tail, sits a while, crouches and pounces, or turns
 * back for no reason. [random] makes every time different.
 */
class CatPlay(private val lo: Int, private val hi: Int, start: Int, private val random: Random) {
    /** Its left column. */
    var x = start.coerceIn(lo, hi)
        private set

    /** 1 facing right, -1 facing left. */
    var facing = 1
        private set

    /** Rows off the ground, mid-pounce. */
    var lift = 0
        private set

    /** Which of [CatShape.FRAMES] to show. */
    var frame = STANDING[0]
        private set

    private enum class Doing { WALK, STAND, SIT, CROUCH, LEAP }

    // Just formed: a moment standing, then off.
    private var doing = Doing.STAND
    private var left = 3
    private var held = 0

    fun step() {
        when (doing) {
            Doing.WALK -> walk()
            Doing.LEAP -> leap()
            Doing.STAND -> hold(STANDING)
            Doing.SIT -> hold(SITTING)
            Doing.CROUCH -> hold(CROUCHING)
        }
    }

    private fun walk() {
        lift = 0
        if (x + facing !in lo..hi) {
            // The edge of its patch: stop a moment, then back the other way.
            facing = -facing
            return start(Doing.STAND, 2 + random.nextInt(3))
        }
        x += facing
        frame = (frame + 1) % 4
        if (--left <= 0) choose()
    }

    /** Holding a pose, the tail's tip flicking every [FLICK_STEPS]; then on to whatever follows it. */
    private fun hold(pose: IntArray) {
        frame = pose[(held++ / FLICK_STEPS) % 2]
        if (--left > 0) return
        if (doing == Doing.CROUCH) return start(Doing.LEAP, LEAP_FRAME.size)
        // Up and off again, after a sit now and then the other way.
        if (doing == Doing.SIT && random.nextInt(3) == 0) facing = -facing
        start(Doing.WALK, 6 + random.nextInt(10))
    }

    private fun leap() {
        val k = LEAP_FRAME.size - left
        frame = LEAP_FRAME[k]
        lift = LEAP_LIFT[k]
        x = (x + LEAP_FORWARD[k] * facing).coerceIn(lo, hi)
        if (--left <= 0) start(Doing.WALK, 4 + random.nextInt(8))
    }

    private fun choose() {
        when (random.nextInt(12)) {
            0, 1 -> start(Doing.STAND, 4 + random.nextInt(5))
            2, 3, 4 -> start(Doing.SIT, 10 + random.nextInt(12))
            5, 6 -> start(Doing.CROUCH, 3 + random.nextInt(3))
            7 -> {
                // Changes its mind.
                facing = -facing
                start(Doing.STAND, 1)
            }
            else -> left = 6 + random.nextInt(10)
        }
    }

    private fun start(next: Doing, steps: Int) {
        doing = next
        left = steps
        held = 0
    }

    private companion object {
        /** Each pose and its flicked tail: standing is the walk's two legs-under steps. */
        val STANDING = intArrayOf(1, 3)
        val SITTING = intArrayOf(CatShape.SIT, CatShape.SIT + 1)
        val CROUCHING = intArrayOf(CatShape.CROUCH, CatShape.CROUCH + 1)
        const val FLICK_STEPS = 2

        // The pounce, step by step: legs stretch to spring, tuck in the air, stretch to land.
        val LEAP_FRAME = intArrayOf(0, 1, 3, 2)
        val LEAP_LIFT = intArrayOf(1, 3, 2, 0)
        val LEAP_FORWARD = intArrayOf(1, 2, 2, 1)
    }
}
