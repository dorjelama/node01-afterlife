package dev.node01.afterlife

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What NODE's model was asked and what it made of it, one dated line each, in
 * [FILE_NAME] beside the model. Every new line drops those older than
 * [KEEP_DAYS], so it never holds more than the last week. Read it with
 * `adb shell cat /sdcard/Android/data/dev.node01.companion/files/asked.log`.
 */
object AskedLog {

    const val FILE_NAME = "asked.log"
    const val KEEP_DAYS = 7L

    /** Blocking file work: call it off the main thread. [fileName] keeps another week-long log the same way. */
    fun add(dir: File, now: LocalDateTime, line: String, fileName: String = FILE_NAME) {
        runCatching {
            val file = File(dir, fileName)
            val old = if (file.isFile) file.readLines() else emptyList()
            file.writeText((pruned(old, now) + "${now.format(STAMP)}  $line").joinToString("\n", postfix = "\n"))
        }
    }

    /** [lines] less those older than [KEEP_DAYS] before [now], and any without a date. */
    internal fun pruned(lines: List<String>, now: LocalDateTime): List<String> {
        val cutoff = now.minusDays(KEEP_DAYS)
        return lines.filter { line ->
            runCatching { LocalDateTime.parse(line.take(STAMP_CHARS), STAMP) }.getOrNull()?.isAfter(cutoff) == true
        }
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val STAMP_CHARS = 19
}
