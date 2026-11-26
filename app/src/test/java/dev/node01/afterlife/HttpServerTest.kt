package dev.node01.afterlife

import dev.node01.afterlife.HttpServer.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpServerTest {

    private val key = "abcdefghjk"
    private val clip = "NODE_20261029_120000.mp4"

    @Test
    fun routesUnderTheKey() {
        assertEquals(Route.Page, HttpServer.route("GET", "/$key/", key))
        assertEquals(Route.Page, HttpServer.route("GET", "/$key/?x=1", key))
        assertEquals(Route.Claude, HttpServer.route("POST", "/$key/claude", key))
        assertEquals(Route.Activity, HttpServer.route("GET", "/$key/activity", key))
        assertEquals(Route.Watch, HttpServer.route("POST", "/$key/activity", key))
        assertEquals(Route.Tank, HttpServer.route("GET", "/$key/tank", key))
        assertEquals(Route.Aquarium, HttpServer.route("GET", "/$key/aquarium", key))
        assertEquals(Route.TankSetUp, HttpServer.route("POST", "/$key/tank", key))
        assertEquals(Route.TankDone, HttpServer.route("POST", "/$key/tank/done", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/tank/done", key))
        assertEquals(Route.TankPhoto, HttpServer.route("GET", "/$key/tank/photo", key))
        assertEquals(Route.TankDescribe, HttpServer.route("POST", "/$key/tank/describe", key))
        assertEquals(Route.TankLabel, HttpServer.route("POST", "/$key/tank/label", key))
        assertEquals(Route.DataList, HttpServer.route("GET", "/$key/data", key))
        assertEquals(Route.DataFile("training/20261126/172722.jpg"), HttpServer.route("GET", "/$key/data/training/20261126/172722.jpg", key))
        assertEquals(Route.DataFile("training/20261126/minutes.csv"), HttpServer.route("GET", "/$key/data/training/20261126/minutes.csv", key))
        assertEquals(Route.DataFile("training/labels.jsonl"), HttpServer.route("GET", "/$key/data/training/labels.jsonl", key))
        assertEquals(Route.DataFile("training/days.csv"), HttpServer.route("GET", "/$key/data/training/days.csv", key))
        assertEquals(Route.DataFile("tank-video/NODE_20261126_172520.mp4"), HttpServer.route("GET", "/$key/data/tank-video/NODE_20261126_172520.mp4", key))
        assertEquals("no way out", Route.NotFound, HttpServer.route("GET", "/$key/data/training/../shared_prefs/node.xml", key))
        assertEquals("unfinished clips stay", Route.NotFound, HttpServer.route("GET", "/$key/data/tank-video/NODE_20261126_172520.mp4.part", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/data/training/20261126/../../x.jpg", key))
        assertEquals(Route.Note, HttpServer.route("POST", "/$key/note", key))
        assertEquals(Route.Face, HttpServer.route("POST", "/$key/face", key))
        assertEquals(Route.Say, HttpServer.route("POST", "/$key/say", key))
        assertEquals(Route.Usage, HttpServer.route("POST", "/$key/usage", key))
        assertEquals(Route.Task, HttpServer.route("POST", "/$key/task", key))
        assertEquals(Route.Pc, HttpServer.route("POST", "/$key/pc", key))
        assertEquals(Route.Cam, HttpServer.route("GET", "/$key/cam", key))
        assertEquals("the page's cache-buster", Route.Cam, HttpServer.route("GET", "/$key/cam?1731500000000", key))
        assertEquals(Route.Mode, HttpServer.route("POST", "/$key/mode", key))
        assertEquals(Route.Webcam, HttpServer.route("GET", "/$key/webcam", key))
        assertEquals(Route.Video, HttpServer.route("GET", "/$key/video", key))
        assertEquals(Route.Video, HttpServer.route("GET", "/$key/video?1730000000", key))
        assertEquals(Route.Audio, HttpServer.route("GET", "/$key/audio", key))
        assertEquals(Route.Clips, HttpServer.route("GET", "/$key/rec/", key))
        assertEquals(Route.Clip(clip), HttpServer.route("GET", "/$key/rec/$clip", key))
    }

    @Test
    fun wrongMethodIsNotFound() {
        assertEquals(Route.NotFound, HttpServer.route("POST", "/$key/", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/claude", key))
        assertEquals(Route.NotFound, HttpServer.route("PUT", "/$key/note", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/say", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/task", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/pc", key))
        assertEquals(Route.NotFound, HttpServer.route("POST", "/$key/cam", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/mode", key))
        assertEquals(Route.NotFound, HttpServer.route("POST", "/$key/video", key))
        assertEquals(Route.NotFound, HttpServer.route("POST", "/$key/rec/$clip", key))
    }

    @Test
    fun wrongOrMissingKeyIsNotFound() {
        for (path in listOf("/", "/claude", "/zzzzzzzzzz/claude", "/abcdefghj/", "/$key", "/${key}x/", "/ABCDEFGHJK/")) {
            assertEquals(path, Route.NotFound, HttpServer.route("POST", path, key))
            assertEquals(path, Route.NotFound, HttpServer.route("GET", path, key))
        }
    }

    @Test
    fun noOtherPathsUnderTheKey() {
        for (rest in listOf("video/", "rec", "webcam/", "cam/", "camera", "claude/", "claude/../note", "../$key/note", "index.html")) {
            assertEquals(rest, Route.NotFound, HttpServer.route("POST", "/$key/$rest", key))
            assertEquals(rest, Route.NotFound, HttpServer.route("GET", "/$key/$rest", key))
        }
    }

    @Test
    fun clipNamesCannotEscapeTheFolder() {
        for (name in listOf(
            "../shared_prefs/node.xml",
            "%2e%2e/x",
            "$clip/../x",
            "x.mp4",
            "NODE_20261029_120000.mp4.part",
            "/etc/hosts",
        )) {
            assertEquals(name, Route.NotFound, HttpServer.route("GET", "/$key/rec/$name", key))
        }
    }

    @Test
    fun readsTheRequestHead() {
        val head = HttpServer.readHead(
            (
                "POST /$key/claude HTTP/1.1\r\n" +
                    "Host: 192.168.1.50:8081\r\n" +
                    "Content-Type: application/json\r\n" +
                    "content-length: 1234\r\n" +
                    "Expect: 100-continue\r\n\r\n{\"x\":1}"
                ).byteInputStream(),
        )!!
        assertEquals("POST", head.method)
        assertEquals("/$key/claude", head.path)
        assertEquals(1234L, head.contentLength)
        assertTrue(head.expectContinue)
        assertFalse(head.chunked)
    }

    @Test
    fun headWithoutLengthOrWithChunksIsMarked() {
        val get = HttpServer.readHead("GET /$key/ HTTP/1.1\r\n\r\n".byteInputStream())!!
        assertEquals(-1L, get.contentLength)
        val chunked = HttpServer.readHead(
            "POST /$key/claude HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n".byteInputStream(),
        )!!
        assertTrue(chunked.chunked)
        val negative = HttpServer.readHead(
            "POST /$key/claude HTTP/1.1\r\nContent-Length: -5\r\n\r\n".byteInputStream(),
        )!!
        assertEquals(-1L, negative.contentLength)
    }

    @Test
    fun malformedOrCutOffHeadsAreRejected() {
        assertNull(HttpServer.readHead("".byteInputStream()))
        assertNull(HttpServer.readHead("GET /$key/ HTTP/1.1\r\nHost: x\r\n".byteInputStream()))
        assertNull(HttpServer.readHead("GARBAGE\r\n\r\n".byteInputStream()))
        // One endless line never grows past the cap.
        assertNull(HttpServer.readHead(("GET /" + "a".repeat(100_000)).byteInputStream()))
    }

    @Test
    fun parsesForms() {
        assertEquals(
            mapOf("text" to "back in 10 · ok", "in" to "5"),
            HttpServer.parseForm("text=back+in+10+%C2%B7+ok&in=5"),
        )
        assertEquals(mapOf("text" to ""), HttpServer.parseForm("text="))
        // A broken escape loses that pair only.
        assertEquals(mapOf("in" to "5"), HttpServer.parseForm("text=%ZZ&in=5"))
        assertEquals(emptyMap<String, String>(), HttpServer.parseForm(""))
    }
}
