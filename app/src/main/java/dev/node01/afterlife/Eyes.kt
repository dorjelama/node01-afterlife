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
 * The cat NODE becomes while it thinks, seen from the side: small and low,
 * round head, perky ears, tail up. Each of [FRAMES] is a pose's lit (col, row)
 * offsets from its top-left, [W] by [H], facing right. 0 to 3 are its walk;
 * [CROUCH], [SIT] and [LOAF] are poses, each followed by the same with the
 * tail's tip moved, to flick it; [JUMP], [LEAP] and [SPRAWL] are single poses.
 *
 * Walking, crouching, sitting and lying are stamped together from layers --
 * tail, body, legs, then the head -- so the face is the same in all of them:
 * '#' lights a dot, 'o' keeps one dark (the eye), '.' leaves it as it is.
 */
object CatShape {
    const val W = 24
    const val H = 16
    const val CROUCH = 4
    const val SIT = 6
    const val LOAF = 8
    const val JUMP = 10
    const val LEAP = 11
    const val SPRAWL = 12

    /** The head in profile: two ears, a round crown, the eye, and a little muzzle. */
    private val HEAD = listOf(
        ".#....#...",
        ".##..###..",
        ".########.",
        "##########",
        "######o###",
        "######o###",
        ".#########",
        "..######..",
    )

    /** Walking: a round body, and a tail curling up behind with its tip flicked forward. */
    private val BODY = listOf(
        "..#######..",
        ".#########.",
        "###########",
        "###########",
        ".#########.",
    )
    private val TAIL_UP = listOf(".##.", "#..#", "#...", "#...", "##..", ".##.", "..##")
    private val TAIL_UP_FLICK = listOf("..##", ".#..", "#...", "#...", "##..", ".##.", "..##")

    /** Each leg is two dots wide and three tall; its lower two rows reach back, stay under, or reach forward. */
    private val HIPS = intArrayOf(5, 8, 11, 13)
    private val BACK = intArrayOf(0, -1, -1)
    private val DOWN = intArrayOf(0, 0, 0)
    private val FORWARD = intArrayOf(0, 1, 1)
    private val STEPS = listOf(
        listOf(BACK, FORWARD, BACK, FORWARD),
        List(4) { DOWN },
        listOf(FORWARD, BACK, FORWARD, BACK),
        List(4) { DOWN },
    )

    /** Low to the ground, tail straight out behind: about to pounce. */
    private val CROUCH_BODY = listOf("..#########..", "#############", ".###########.")
    private val TAIL_LOW = listOf(".....", "#####")
    private val TAIL_LOW_FLICK = listOf("#....", ".####")

    /** Sitting up, front paws down, tail curled along the ground. */
    private val SIT_BODY = listOf(
        "....####..",
        "...######.",
        "..#######.",
        ".########.",
        "#########.",
        "#########.",
        ".########.",
    )
    private val SIT_PAWS = listOf("#.#", "#.#")
    private val TAIL_CURL = listOf("#......", "#......", "#######")
    private val TAIL_CURL_FLICK = listOf(".#.....", "#......", "#######")

    /** Lying down, paws tucked under, tail wrapped round. */
    private val LOAF_BODY = listOf(
        "..#########.",
        ".###########",
        "############",
        ".##########.",
    )
    private val TAIL_WRAP = listOf("#.....", "######")
    private val TAIL_WRAP_FLICK = listOf(".#....", "######")

    /** Springing up, front first. */
    private val JUMPING = listOf(
        "....................#..#",
        "...................##.##",
        "...................#####",
        "..................######",
        ".................###o###",
        "................####o###",
        "...............########.",
        "..............########..",
        ".............#######....",
        "............#######.....",
        "..##.......#######......",
        ".#..#.....#######.......",
        ".#.......######.##......",
        "..##....#####..##.......",
        "....####.##.............",
        ".........##.............",
    )

    /** In the air, stretched out from nose to tail. */
    private val LEAPING = listOf(
        "........................",
        "........................",
        "........................",
        "................#....#..",
        "................##..###.",
        "................########",
        "...............#########",
        "..............######o###",
        "###.....############o###",
        "...#####################",
        "........##############..",
        ".......##.#########.###.",
        "......##...........#..##",
        ".....##.................",
        "........................",
        "........................",
    )

    /** Lying flat out, legs stretched. */
    private val SPRAWLING = listOf(
        "........................",
        "........................",
        "........................",
        "........................",
        "........................",
        "........................",
        "........................",
        "........................",
        "...............#....#...",
        "...............##..###..",
        "...............########.",
        "..............##########",
        "#.....##############o###",
        "##..################o###",
        ".###################.###",
        "##..##...........####...",
    )

    val FRAMES: List<IntArray> = List(STEPS.size) { step ->
        pose {
            stamp(if (step >= 2) TAIL_UP_FLICK else TAIL_UP, 1, 1)
            stamp(BODY, 4, 8)
            STEPS[step].forEachIndexed { leg, reach ->
                reach.forEachIndexed { r, dx -> stamp(listOf("##"), HIPS[leg] + dx, 13 + r) }
            }
            stamp(HEAD, 13, 2)
        }
    } + listOf(false, true).map { flick ->
        pose {
            stamp(if (flick) TAIL_LOW_FLICK else TAIL_LOW, 0, 10)
            stamp(CROUCH_BODY, 3, 11)
            for (hip in HIPS) {
                stamp(listOf("##"), hip, 14)
                stamp(listOf("###"), hip, 15)
            }
            stamp(HEAD, 13, 5)
        }
    } + listOf(false, true).map { flick ->
        pose {
            stamp(if (flick) TAIL_CURL_FLICK else TAIL_CURL, 3, 13)
            stamp(SIT_BODY, 9, 8)
            stamp(SIT_PAWS, 15, 14)
            stamp(HEAD, 12, 1)
        }
    } + listOf(false, true).map { flick ->
        pose {
            stamp(if (flick) TAIL_WRAP_FLICK else TAIL_WRAP, 0, 14)
            stamp(LOAF_BODY, 4, 12)
            stamp(HEAD, 13, 6)
        }
    } + listOf(JUMPING, LEAPING, SPRAWLING).map { whole -> pose { stamp(whole, 0, 0) } }

    private class Canvas {
        val grid = Array(H) { BooleanArray(W) }

        fun stamp(layer: List<String>, x: Int, y: Int) {
            layer.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch ->
                    if (y + r in 0 until H && x + c in 0 until W && ch != '.') grid[y + r][x + c] = ch == '#'
                }
            }
        }
    }

    private fun pose(draw: Canvas.() -> Unit): IntArray {
        val canvas = Canvas().apply(draw)
        val out = ArrayList<Int>()
        for (r in 0 until H) {
            for (c in 0 until W) {
                if (canvas.grid[r][c]) {
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
 * then pauses to flick its tail, sits a while, lies down, stretches out flat,
 * hops, crouches and pounces, or turns back for no reason. [random] makes
 * every time different.
 */
class CatPlay(private val lo: Int, private val hi: Int, start: Int, private val random: Random) {
    /** Its left column. */
    var x = start.coerceIn(lo, hi)
        private set

    /** 1 facing right, -1 facing left. */
    var facing = 1
        private set

    /** Rows off the ground, mid-jump. */
    var lift = 0
        private set

    /** Which of [CatShape.FRAMES] to show. */
    var frame = STANDING[0]
        private set

    private enum class Doing { WALK, STAND, SIT, LOAF, SPRAWL, CROUCH, MOVES }

    /** One step of a set move: the pose, how high off the ground, and how far along. */
    private class Move(val frame: Int, val lift: Int, val forward: Int)

    // Just formed: a moment standing, then off.
    private var doing = Doing.STAND
    private var left = 3
    private var held = 0
    private var moves = POUNCE

    fun step() {
        when (doing) {
            Doing.WALK -> walk()
            Doing.MOVES -> move()
            Doing.STAND -> hold(STANDING)
            Doing.SIT -> hold(SITTING)
            Doing.LOAF -> hold(LOAFING)
            Doing.SPRAWL -> hold(SPRAWLING)
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
        if (doing == Doing.CROUCH) return play(POUNCE)
        // Up and off again, after a rest now and then the other way.
        if (doing != Doing.STAND && random.nextInt(3) == 0) facing = -facing
        start(Doing.WALK, 6 + random.nextInt(10))
    }

    private fun move() {
        val m = moves[moves.size - left]
        frame = m.frame
        lift = m.lift
        x = (x + m.forward * facing).coerceIn(lo, hi)
        if (--left <= 0) start(Doing.WALK, 4 + random.nextInt(8))
    }

    private fun choose() {
        when (random.nextInt(12)) {
            0, 1 -> start(Doing.STAND, 4 + random.nextInt(5))
            2, 3 -> start(Doing.SIT, 10 + random.nextInt(12))
            4 -> start(Doing.LOAF, 14 + random.nextInt(14))
            5 -> start(Doing.SPRAWL, 8 + random.nextInt(8))
            6, 7 -> start(Doing.CROUCH, 3 + random.nextInt(3))
            8 -> play(HOP)
            9 -> {
                // Changes its mind.
                facing = -facing
                start(Doing.STAND, 1)
            }
            else -> left = 6 + random.nextInt(10)
        }
    }

    private fun play(next: List<Move>) {
        moves = next
        start(Doing.MOVES, next.size)
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
        val LOAFING = intArrayOf(CatShape.LOAF, CatShape.LOAF + 1)
        val SPRAWLING = intArrayOf(CatShape.SPRAWL, CatShape.SPRAWL)
        const val FLICK_STEPS = 2

        /** Springs off, flies stretched out, lands walking. */
        val POUNCE = listOf(
            Move(CatShape.JUMP, 1, 1),
            Move(CatShape.LEAP, 3, 2),
            Move(CatShape.LEAP, 2, 2),
            Move(2, 0, 1),
        )

        /** A hop straight up on the spot, for the fun of it. */
        val HOP = listOf(
            Move(CatShape.CROUCH, 0, 0),
            Move(CatShape.JUMP, 2, 0),
            Move(CatShape.JUMP, 3, 0),
            Move(CatShape.JUMP, 1, 0),
            Move(1, 0, 0),
        )
    }
}
