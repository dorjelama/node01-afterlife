package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class AskedLogTest {

    private val now = LocalDateTime.of(2026, 11, 22, 1, 35, 40)

    @Test
    fun keepsAWeek() {
        val lines = listOf(
            "2026-11-14 09:00:00  \"too old\" -> UNKNOWN",
            "2026-11-15 01:35:41  \"just inside the week\" -> \"what time is it\"",
            "not a dated line",
            "2026-11-22 01:30:00  \"what is the capital of nepal\" -> ASK -> \"Kathmandu\"",
        )
        assertEquals(lines.subList(1, 2) + lines.subList(3, 4), AskedLog.pruned(lines, now))
    }

    @Test
    fun appendsDatedLinesAndDropsOldOnes() {
        val dir = Files.createTempDirectory("asked").toFile()
        try {
            File(dir, AskedLog.FILE_NAME).writeText("2026-11-01 10:00:00  \"long gone\" -> UNKNOWN\n")
            AskedLog.add(dir, now, "\"how late is it\" -> \"what time is it\" (2.2 s)")
            AskedLog.add(dir, now.plusMinutes(1), "\"um\" -> UNKNOWN (1.9 s)")
            assertEquals(
                listOf(
                    "2026-11-22 01:35:40  \"how late is it\" -> \"what time is it\" (2.2 s)",
                    "2026-11-22 01:36:40  \"um\" -> UNKNOWN (1.9 s)",
                ),
                File(dir, AskedLog.FILE_NAME).readLines(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
