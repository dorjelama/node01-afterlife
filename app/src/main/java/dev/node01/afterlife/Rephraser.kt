package dev.node01.afterlife

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/**
 * A ready-made open model (Qwen2.5 1.5B; any `.litertlm` file), run on the
 * phone. Told NODE's commands and shown a few [EXAMPLES], it rewrites a
 * loosely worded English sentence into one the keyword parser already knows --
 * "ping me in ten to stretch" becomes "remind me in 10 minutes to stretch" --
 * and [Assistant.parse] still decides what that means. So it can never do
 * anything a spoken sentence could not already do. A question about the world
 * comes back as `ASK` ([asks]), for [answer] to answer from what [Lookup] found.
 *
 * Blocking and slow (seconds): call it off the main thread. One sentence at a
 * time; [close] waits for a sentence in progress.
 */
class Rephraser private constructor(
    private val engine: Engine,
    private val rewriting: Conversation,
    private val answering: Conversation,
) : AutoCloseable {

    // Each call works on a copy of a primed conversation: its instructions are read once, at load,
    // and nothing said is seen by the next call.

    @Synchronized
    fun rephrase(heard: String): String? {
        // One of its examples gets the answer it was shown, without asking: the model does not always copy them.
        known(heard)?.let { return clean(it) }
        return runCatching { rewriting.clone().use { chat -> clean(chat.sendMessage(heard).text()) } }.getOrNull()
    }

    /** One short sentence answering [question] from [notes], to be spoken; null when the notes do not answer it. */
    @Synchronized
    fun answer(question: String, notes: String): String? = runCatching {
        answering.clone().use { chat ->
            clean(chat.sendMessage("Notes: $notes\n\nQuestion: $question").text())?.takeIf { !asks(it) }
        }
    }.getOrNull()

    private fun Message.text() = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    @Synchronized
    override fun close() {
        rewriting.close()
        answering.close()
        engine.close()
    }

    companion object {
        const val FILE_NAME = "node.litertlm"

        /** Rewritten once at load, so the first real sentence is not also the slowest. */
        const val WARM_UP = "is it getting late"

        /** What NODE knows, one example of each. Every line must parse: RephraserTest checks. */
        val COMMANDS = listOf(
            "what time is it", "what's the date", "set a timer for 10 minutes", "focus for 25 minutes",
            "cancel the timer", "remind me in 20 minutes to stretch", "remind me at 5 30 pm to call mum",
            "set an alarm for 7 am", "remind me every 30 minutes to drink water", "take a note buy milk",
            "what is claude doing", "how much of my session is left", "when does my session reset",
            "6 plus 2", "6 minus 2", "6 times 2", "6 divided by 2", "5 km in miles", "20 celsius in fahrenheit",
            "start a stopwatch", "how long", "louder", "quieter", "go to sleep", "wake up", "flip a coin",
            "pick a number between 1 and 10", "help", "sing", "dismiss",
        )

        /** Said, and what NODE should hear. Each said one must get past the keywords: RephraserTest checks. */
        val EXAMPLES = listOf(
            "how late is it" to "what time is it",
            "ping me in ten to stretch" to "remind me in 10 minutes to stretch",
            "nudge me after lunch to call mum" to "remind me at 1 pm to call mum",
            "get me up at half past six" to "set an alarm for 6 30 am",
            "jot down buy milk" to "take a note buy milk",
            "multiply 6 by 7" to "6 times 7",
            "who won the world cup" to "ASK",
            "um the thing yeah" to "UNKNOWN",
            "how tall is mount everest" to "ASK",
            "sorry i was talking to my brother" to "UNKNOWN",
            "pass me the remote" to "UNKNOWN",
            // Ones it got wrong on the phone: "cancel the timer", "what's the date", and a lookup.
            "count down three minutes" to "set a timer for 3 minutes",
            "my coffee is cold" to "UNKNOWN",
            "the dog ate it" to "UNKNOWN",
        )

        // A question is answered "ASK" alone, not repeated: every word the model writes costs half a second here.
        private val INSTRUCTIONS = """
            |You are the ears of NODE, a desk robot. Rewrite what the person said as the one NODE command that
            |means the same, with their numbers and words. Write numbers as digits. After lunch is 1 pm, this
            |evening 6 pm, tonight 8 pm, before bed 10 pm, in a bit 15 minutes. Reply with the command only.
            |
            |NODE's commands:
            |${COMMANDS.joinToString("\n")}
            |
            |A question about the world: reply ASK. Anything else: reply UNKNOWN.
        """.trimMargin()

        // Greedy: the same input always gets the same reply.
        private val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)

        private val REWRITING = ConversationConfig(
            systemInstruction = Contents.of(INSTRUCTIONS),
            initialMessages = EXAMPLES.flatMap { (said, heard) -> listOf(Message.user(said), Message.model(heard)) },
            samplerConfig = GREEDY,
            prefillPrefaceOnInit = true,
            maxOutputToken = 32,
        )

        // Short: it is spoken, and every word costs a moment.
        private val ANSWERING = ConversationConfig(
            systemInstruction = Contents.of(
                "You are NODE, a small desk robot. Answer the question in one short sentence, to be spoken " +
                    "aloud, using only the notes. If the notes do not answer it, reply UNKNOWN.",
            ),
            samplerConfig = GREEDY,
            prefillPrefaceOnInit = true,
            maxOutputToken = 40,
        )

        /**
         * The model in [dir], loaded and primed -- most of a minute -- or null
         * if there is none or it will not load. On the CPU: the Redmi's GPU
         * (Adreno 610) loads Qwen2.5 1.5B but answers nothing, 22 s at a time.
         */
        fun open(dir: File, cacheDir: File): Rephraser? {
            val model = File(dir, FILE_NAME).takeIf { it.isFile } ?: return null
            return runCatching {
                val engine = Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(), cacheDir = cacheDir.path))
                engine.initialize()
                runCatching { Rephraser(engine, engine.createConversation(REWRITING), engine.createConversation(ANSWERING)) }
                    .onFailure { engine.close() }
                    .getOrThrow()
            }.getOrNull()
        }

        /** The model's [raw] reply as one line -- a sentence for the parser, `ASK`, or an answer -- or null for `UNKNOWN`. */
        fun clean(raw: String): String? {
            val line = raw.lineSequence().map { it.trim().trim('"', '\'', '`').trim() }.firstOrNull { it.isNotEmpty() }
                ?: return null
            if (line.equals("UNKNOWN", ignoreCase = true)) return null
            return line.take(MAX_CHARS)
        }

        /** The answer [EXAMPLES] gives for [heard], capitals and punctuation aside, or null if it is not one of them. */
        fun known(heard: String): String? = KNOWN[plain(heard)]

        private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(SPACES, " ")
        private val SPACES = Regex(" +")
        private val KNOWN by lazy { EXAMPLES.associate { (said, heard) -> plain(said) to heard } }

        /** Whether a rewrite says what was said is a question about the world: `ASK`, or `ASK: ...` if it says more. */
        fun asks(rewrite: String): Boolean = rewrite.equals("ASK", ignoreCase = true) || rewrite.startsWith("ASK:", ignoreCase = true)

        private const val MAX_CHARS = 200
    }
}
