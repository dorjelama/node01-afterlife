package dev.node01.afterlife

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A ready-made open model ([MODEL]) run by Ollama on the user's own server on
 * the LAN ([SERVER]), never a cloud AI. Told NODE's commands and shown a few
 * [EXAMPLES], it rewrites a loosely worded English sentence into one the
 * keyword parser already knows -- "ping me in ten to stretch" becomes "remind
 * me in 10 minutes to stretch" -- and [Assistant.parse] still decides what that
 * means. So it can never do anything a spoken sentence could not already do. A
 * question about the world comes back as `ASK` ([asks]), for [answer] to answer
 * from what [Lookup] found; anything else said to NODE gets a short [chat] reply.
 *
 * Answers and chat share one conversation, carried on for [TALK_MS] after the
 * last reply, so "how old is he?" knows who was just talked about.
 *
 * Blocking and slow (seconds): call it off the main thread. One sentence at a time.
 */
class Rephraser(private val server: String = SERVER, private val model: String = MODEL) {

    /** Whether the last call got an answer from the server; false while it is off, unreachable or lacks [model]. */
    @Volatile
    var reachable = false
        private set

    /** The conversation in progress, as (role, text), and when it last replied; replaced once it is [TALK_MS] old. */
    private var talk: MutableList<Pair<String, String>>? = null
    private var talkAtMs = 0L

    // Every call sends the instructions again: Ollama keeps them read from the call before, so only the new
    // words cost time, and nothing said is seen by the next rewrite.

    @Synchronized
    fun rephrase(heard: String): String? {
        // One of its examples gets the answer it was shown, without asking: the model does not always copy them.
        known(heard)?.let { return clean(it) }
        return send(REWRITING + ("user" to heard), REWRITE_TOKENS)?.let(::clean)
    }

    /**
     * One short sentence answering [question] from [notes], to be spoken; null when the notes do not answer it.
     * A [followUp] carries on the conversation in progress; anything else starts a new one.
     */
    fun answer(question: String, notes: String, followUp: Boolean = false): String? =
        reply("Notes: $notes\n\nQuestion: $question", carryOn = followUp)

    /**
     * A short spoken reply to something said to NODE that is neither a command nor a question. After a question
     * the model can take it for one more and say UNKNOWN; then it is asked again, afresh.
     */
    fun chat(said: String): String? {
        val carried = inConversation
        return reply(said, carryOn = true) ?: if (carried) reply(said, carryOn = false) else null
    }

    /** The morning's greeting, one cheerful sentence made from [notes]. */
    fun brief(notes: String): String? = reply(
        // No "I" or "me" in it: the model copies them, and said "I have to fix the bike".
        "Notes: $notes\n\nGreet the listener good morning, then tell them the weather and everything they have " +
            "today, calling them you. One or two short, cheerful sentences.",
        carryOn = false,
    )

    /** Whether a conversation is still in progress, for a follow-up to carry on. */
    val inConversation: Boolean
        @Synchronized get() = talk != null && SystemClock.elapsedRealtime() - talkAtMs <= TALK_MS

    /**
     * [question] said again so it stands alone -- "how old is he" as "how old is Emmanuel Macron" -- from the
     * conversation in progress, for searching; null if there is none or the model gives nothing.
     */
    fun standalone(question: String): String? = if (!inConversation) null else reply(
        "Say my next question again so it makes sense on its own, naming who or what it is about. " +
            "Reply with the question only: $question",
        carryOn = true,
    )

    @Synchronized
    private fun reply(message: String, carryOn: Boolean): String? {
        val chat = talk?.takeIf { carryOn && inConversation } ?: mutableListOf<Pair<String, String>>().also { talk = it }
        val raw = send(listOf("system" to TALKING) + chat + ("user" to message), TALK_TOKENS) ?: return null
        chat += "user" to message
        chat += "assistant" to raw
        talkAtMs = SystemClock.elapsedRealtime()
        return clean(raw)?.takeIf { !asks(it) }
    }

    /** The model's reply to [messages], greedy so the same input always gets the same reply; null if the server gave none. */
    private fun send(messages: List<Pair<String, String>>, maxTokens: Int): String? = try {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            // Kept loaded: loading it again costs the next sentence half a minute on the server's CPU.
            .put("keep_alive", -1)
            .put("messages", JSONArray(messages.map { (role, text) -> JSONObject().put("role", role).put("content", text) }))
            .put("options", JSONObject().put("temperature", 0).put("top_k", 1).put("seed", 0).put("num_predict", maxTokens))
        val connection = (URL("$server/api/chat").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            // Not 200: most likely the model is not pulled on the server.
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .getJSONObject("message").getString("content")
                .also { reachable = true }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        reachable = false
        null
    }

    companion object {
        /** The user's own Ollama server on the LAN; it must listen on the LAN, not only 127.0.0.1 (README). */
        const val SERVER = "http://192.168.1.76:11434"

        /** qwen2.5:3b beat gemma3:4b there: as many right, 1.6 s a sentence against 31 (README). */
        const val MODEL = "qwen2.5:3b"

        // A few seconds is enough to find a server on the LAN; an answer can take most of a minute if it had to load.
        private const val CONNECT_MS = 3_000
        private const val READ_MS = 90_000

        /** Rewritten at start, and while the server is away, to see that it answers; it also loads the model there. */
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
            // Small talk, which gets a reply of its own: "i'm so bored" came out as a focus session.
            "i'm feeling a bit sad" to "UNKNOWN",
            "you're a cute little robot" to "UNKNOWN",
            "tell me something funny" to "UNKNOWN",
            // A follow-up is a question too: "how old is he" came out as the date.
            "how old is she" to "ASK",
            "and what's its population" to "ASK",
            // "Stop" got a chatty reply instead of quieting what was ringing.
            "stop" to "dismiss",
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
            |Only a clear request for one of these is a command. A question about the world: reply ASK.
            |Small talk, feelings, jokes and anything else: reply UNKNOWN.
        """.trimMargin()

        /** The instructions and examples every rewrite starts from. */
        private val REWRITING = listOf("system" to INSTRUCTIONS) +
            EXAMPLES.flatMap { (said, heard) -> listOf("user" to said, "assistant" to heard) }
        private const val REWRITE_TOKENS = 32

        /** How long a conversation is carried on after NODE last replied: long enough for a follow-up. */
        const val TALK_MS = 2 * 60_000L

        // Short: it is spoken, and every word costs a moment.
        // "Reply UNKNOWN if they do not answer it" was taken too far: it said UNKNOWN to the moon's
        // distance with the distance in the notes, and to "what's your favourite colour".
        private const val TALKING =
            "You are NODE, a small, friendly desk robot. Reply in one short sentence, to be spoken aloud. " +
                "Never promise to do anything. When a message starts with Notes, answer from the notes, and " +
                "reply UNKNOWN only if they say nothing about the question."
        private const val TALK_TOKENS = 60

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
