package dev.node01.afterlife

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A question looked up on Wikipedia, for [Rephraser.answer] to answer from:
 * one search that returns the opening paragraphs of the top few articles,
 * cut down to the sentences that share the most words with the question --
 * the fewer words the model has to read, the sooner it answers. Only the
 * question leaves the phone, as the search, under a generic client name.
 */
object Lookup {

    /** Blocking, and never throws: null when nothing was found or it did not work. */
    fun notes(question: String): String? = runCatching {
        val url = URL(SEARCH + URLEncoder.encode(topic(question), "UTF-8"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // A generic client name: NODE never says whose phone it is.
            setRequestProperty("User-Agent", "NODE-Companion")
        }
        try {
            if (connection.responseCode != 200) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: return null
            // Pages come in no particular order; "index" is the search's own ranking.
            val ranked = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }
            pick(question, ranked.map { it.optString("extract") })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * What to search for: the question without its question words, so "who
     * wrote harry potter" finds Harry Potter rather than any page that says
     * "wrote". Small words that join others stay: "capital of nepal" finds
     * Kathmandu, where "capital nepal" finds capital punishment in Nepal.
     */
    internal fun topic(question: String): String =
        question.lowercase().split(NOT_A_WORD).filter { it.length > 1 && (it in JOINING || it !in STOP) && it !in SAME }
            .joinToString(" ").ifEmpty { question }

    /**
     * The top article's opening sentence, which says what it is about, and the
     * [MAX_SENTENCES] - 1 others of [extracts] that best share the question's
     * words, in the order they came; null if none shares any, as the search
     * then found something else. A word counts for less the more sentences
     * have it: in an article about Nepal, "capital" says more than "Nepal".
     */
    internal fun pick(question: String, extracts: List<String>): String? {
        val wanted = words(question)
        if (wanted.isEmpty()) return null
        // shortcut: splits after "Dr." or "St."; fine for picking sentences, not for quoting them.
        val sentences = extracts.flatMap { it.split(SENTENCE_END) }.map { it.trim() }.filter { it.length >= MIN_SENTENCE }
        val bags = sentences.map { words(it) }
        val weight = wanted.associateWith { w -> 1.0 / bags.count { w in it }.coerceAtLeast(1) }
        val scores = bags.map { bag -> bag.sumOf { weight[it] ?: 0.0 } }
        if (scores.none { it > 0.0 }) return null
        val best = sentences.indices.drop(1).filter { scores[it] > 0.0 }.sortedByDescending { scores[it] }.take(MAX_SENTENCES - 1)
        return (listOf(0) + best).sorted().joinToString(" ") { sentences[it] }.take(MAX_CHARS)
    }

    /**
     * What [s] is about, as word stems: everyday synonyms made one, then the
     * first [STEM] letters, so "capitals" finds "capital".
     */
    private fun words(s: String): Set<String> =
        s.lowercase().split(NOT_A_WORD).filter { it.length > 1 && it !in STOP }.map { SAME[it] ?: it.take(STEM) }.toSet()

    private const val SEARCH = "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
        "&redirects=1&generator=search&gsrlimit=2&prop=extracts&exintro=1&explaintext=1&exlimit=2&gsrsearch="
    private const val TIMEOUT_MS = 8_000
    // Two: the model reads about 8 words a second on the Redmi, and the answer waits for all of them.
    private const val MAX_SENTENCES = 2
    private const val STEM = 6
    private const val MIN_SENTENCE = 20
    private const val MAX_CHARS = 600
    // Not after a lone capital, so "J. K. Rowling" and "U.S." stay whole.
    private val SENTENCE_END = Regex("(?<=[.!?])(?<!\\b[A-Z]\\.)\\s+")
    private val NOT_A_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Words every question has, which say nothing about what it is asking. */
    private val STOP = setOf(
        "what", "who", "whom", "whose", "which", "when", "where", "why", "how", "is", "are", "was", "were",
        "be", "been", "the", "of", "an", "in", "on", "at", "to", "for", "and", "or", "it", "its", "do", "does",
        "did", "tell", "me", "about", "much", "many", "there", "that", "this", "by", "with", "from", "as",
        "has", "have", "had", "can", "could", "you", "your", "my",
    )

    /** Of [STOP], the words a search needs to keep the rest together. */
    private val JOINING = setOf("of", "in", "on", "at", "to", "for", "and", "or", "by", "with", "from", "as")

    // shortcut: a handful of the words spoken questions use and articles do not; add to it as questions miss.
    private val SAME = mapOf(
        "tall" to "height", "high" to "height", "higher" to "height", "highest" to "height", "elevation" to "height",
        "wrote" to "write", "written" to "write", "writer" to "write", "author" to "write", "authored" to "write",
        "born" to "birth", "died" to "death", "dies" to "death", "invented" to "invent", "inventor" to "invent",
        "founded" to "found", "founder" to "found", "old" to "age", "big" to "large", "biggest" to "large",
        "largest" to "large",
    )
}
