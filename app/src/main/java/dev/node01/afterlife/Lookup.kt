package dev.node01.afterlife

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A question looked up on Wikipedia, for [Rephraser.answer] to answer from:
 * one search that returns the opening paragraphs of the top few articles,
 * cut down to the sentences that share the most words with the question --
 * the fewer words the model has to read, the sooner it answers. A question
 * about now goes to Google News's headlines instead ([wantsNews]), which also
 * answer "what's the news?". Only the question leaves the phone, as the
 * search, under a generic client name.
 */
object Lookup {

    /** Blocking, and never throws: null when nothing was found or it did not work. */
    fun notes(question: String): String? = runCatching {
        val json = get(SEARCH + URLEncoder.encode(topic(question), "UTF-8")) ?: return null
        val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: return null
        // Pages come in no particular order; "index" is the search's own ranking.
        val ranked = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }
        pick(question, ranked.map { it.optString("extract") })
    }.getOrNull()

    /**
     * The top headlines, or the latest about [query], as Google News ranks
     * them; null if none came. Blocking, and never throws. Only the query
     * leaves the phone.
     */
    fun headlines(query: String? = null): List<String>? = runCatching {
        val url = if (query == null) "$NEWS?$EDITION" else "$NEWS/search?q=${URLEncoder.encode(query, "UTF-8")}&$EDITION"
        titles(get(url) ?: return null).ifEmpty { null }
    }.getOrNull()

    /** Headlines about [question], as notes for [Rephraser.answer]; null if none came. */
    fun newsNotes(question: String): String? =
        headlines(newsTopic(question))?.joinToString(". ")?.take(MAX_CHARS)

    /** Whether [question] is about what is happening now: one for the news, not an encyclopaedia. */
    fun wantsNews(question: String): Boolean = question.lowercase().split(NOT_A_WORD).any { it in NOW }

    /**
     * Whether [said] is worded as a question about the world -- "where was she
     * born", "how far is the moon" -- rather than about you or NODE, like
     * "what's your favourite colour" or "where's my phone".
     */
    fun isQuestion(said: String): Boolean {
        val w = said.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }.dropWhile { it in LEADING }
        if (w.isEmpty() || w.any { it in PERSONAL }) return false
        return w[0] in ASKING || (w[0] == "how" && w.getOrNull(1) in MEASURING) || (w[0] == "what" && w.getOrNull(1) in BEING)
    }

    /** Whether [question] leans on the one before it: "how old is he?". */
    fun refersBack(question: String): Boolean = question.lowercase().split(NOT_A_WORD).any { it in BACK }

    /** A news search wants the words that name things: "what tournament is on right now" is "tournament". */
    internal fun newsTopic(question: String): String =
        question.lowercase().split(NOT_A_WORD).filter { it.length > 1 && it !in STOP && it !in WHEN }
            .joinToString(" ").ifEmpty { question }

    /** The items' titles in an RSS feed, without the " - Publisher" Google News puts after each. */
    internal fun titles(rss: String): List<String> =
        ITEM_TITLE.findAll(rss).map { unescape(it.groupValues[1]).substringBeforeLast(" - ").trim() }
            .filter { it.isNotEmpty() }.take(MAX_HEADLINES).toList()

    private fun unescape(s: String) = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e]
        } ?: m.value
    }

    /** The body of a 200 reply, or null. Under a generic client name: NODE never says whose phone it is. */
    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "NODE-Companion")
        }
        try {
            if (connection.responseCode != 200) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

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
    private const val NEWS = "https://news.google.com/rss"

    /** Google News in English, as India reads it: the nearest edition to Kathmandu. Yours to change. */
    private const val EDITION = "hl=en-IN&gl=IN&ceid=IN:en"
    private const val MAX_HEADLINES = 5
    private val ITEM_TITLE = Regex("""<item>.*?<title>(?:<!\[CDATA\[)?(.*?)(?:]]>)?</title>""", RegexOption.DOT_MATCHES_ALL)
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

    /** Words that ask about now. Not "current": "the current president of France" is in the encyclopaedia. */
    private val NOW = setOf(
        "news", "latest", "today", "tonight", "now", "yesterday", "recent", "recently", "live", "score", "scores",
        "headlines", "happening", "week", "weekend",
    )

    /** Of those, the ones that only say when: "the cricket score" still wants its score. */
    private val WHEN = setOf("news", "latest", "today", "tonight", "now", "right", "yesterday", "recent", "recently", "headlines", "happening")

    private val LEADING = setOf("and", "so", "but", "then", "ok", "okay", "um", "hey")
    private val ASKING = setOf("who", "whom", "whose", "when", "where", "which", "why")
    private val MEASURING = setOf("far", "old", "tall", "big", "many", "much", "long", "high", "deep", "fast", "heavy", "large")
    private val BEING = setOf("is", "s", "are", "was", "were", "does", "did", "do")
    private val PERSONAL = setOf("you", "your", "yours", "yourself", "i", "me", "my", "mine", "myself", "we", "us", "our")

    /** Words that point back at what was just talked about. */
    private val BACK = setOf("he", "she", "it", "its", "they", "them", "their", "his", "her", "him", "there", "that")

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
        "has", "have", "had", "can", "could", "you", "your", "my", "away",
    )

    /** Of [STOP], the words a search needs to keep the rest together. */
    private val JOINING = setOf("of", "in", "on", "at", "to", "for", "and", "or", "by", "with", "from", "as")

    // shortcut: a handful of the words spoken questions use and articles do not; add to it as questions miss.
    private val SAME = mapOf(
        "tall" to "height", "high" to "height", "higher" to "height", "highest" to "height", "elevation" to "height",
        "wrote" to "write", "written" to "write", "writer" to "write", "author" to "write", "authored" to "write",
        "born" to "birth", "died" to "death", "dies" to "death", "invented" to "invent", "inventor" to "invent",
        "founded" to "found", "founder" to "found", "old" to "age", "big" to "large", "biggest" to "large",
        "largest" to "large", "far" to "distan", "distant" to "distan",
    )
}
