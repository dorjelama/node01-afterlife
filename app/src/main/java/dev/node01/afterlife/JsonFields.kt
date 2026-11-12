package dev.node01.afterlife

/**
 * Pulls a few top-level fields out of a JSON object, and nothing else. Strings
 * come back decoded; `true`, a number or `null` comes back as written.
 *
 * Hook events carry whole prompts and tool outputs, and NODE keeps only the
 * first part of a large body, so this has to cope with input cut off at any
 * point. It walks the top level only: a key of the same name nested inside
 * `tool_input` is skipped over, never matched. On anything it does not
 * understand it stops and returns what it has found so far.
 *
 * Hand-rolled because org.json is a stub in local unit tests, and this is the
 * part that most needs testing.
 */
object JsonFields {

    fun extract(json: String, wanted: Set<String>): Map<String, String> {
        val found = HashMap<String, String>()
        Reader(json).run {
            skipSpace()
            if (!take('{')) return found
            while (true) {
                skipSpace()
                if (take('}')) return found
                val key = readString() ?: return found
                skipSpace()
                if (!take(':')) return found
                skipSpace()
                if (peek() == '"') {
                    val value = readString() ?: return found
                    if (key in wanted) found[key] = value
                } else {
                    // true, false, null or a number, kept as written: a wanted
                    // flag reads back as "true" without a JSON parser. Objects
                    // and arrays are skipped whole, as before.
                    val plain = peek() != '{' && peek() != '['
                    val from = at
                    if (!skipValue()) return found
                    if (plain && key in wanted) found[key] = textSince(from)
                }
                skipSpace()
                if (!take(',')) return found
            }
        }
    }

    private class Reader(private val s: String) {
        private var i = 0

        /** How far the reader has come, for [textSince]. */
        val at get() = i

        /** What was read since [from], as it was written. */
        fun textSince(from: Int): String = s.substring(from, i).trim()

        fun peek(): Char? = s.getOrNull(i)

        fun take(c: Char): Boolean = (peek() == c).also { if (it) i++ }

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        /** At an opening quote. Null if the string is cut off or malformed. */
        fun readString(): String? {
            if (!take('"')) return null
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c != '\\' -> out.append(c)
                    i >= s.length -> return null
                    else -> when (val e = s[i++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 4 > s.length) return null
                            out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> out.append(e) // \" \\ \/
                    }
                }
            }
            return null
        }

        /**
         * Skips a number, literal, array or object, strings inside it
         * included. False if the input ends first.
         */
        fun skipValue(): Boolean {
            var depth = 0
            while (i < s.length) {
                when (s[i]) {
                    '"' -> {
                        readString() ?: return false
                        if (depth == 0) return true
                        continue
                    }
                    '{', '[' -> depth++
                    '}', ']' -> {
                        // A closing brace at depth 0 ends the enclosing object.
                        if (depth == 0) return true
                        depth--
                        if (depth == 0) {
                            i++
                            return true
                        }
                    }
                    ',' -> if (depth == 0) return true
                }
                i++
            }
            return false
        }
    }
}
