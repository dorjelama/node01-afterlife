package dev.node01.afterlife

import dev.node01.afterlife.HttpServer.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpServerTest {

    private val key = "abcdefghjk"

    @Test
    fun routesUnderTheKey() {
        assertEquals(Route.Page, HttpServer.route("GET", "/$key/", key))
        assertEquals(Route.Page, HttpServer.route("GET", "/$key/?x=1", key))
        assertEquals(Route.Claude, HttpServer.route("POST", "/$key/claude", key))
        assertEquals(Route.Note, HttpServer.route("POST", "/$key/note", key))
        assertEquals(Route.Face, HttpServer.route("POST", "/$key/face", key))
    }

    @Test
    fun wrongMethodIsNotFound() {
        assertEquals(Route.NotFound, HttpServer.route("POST", "/$key/", key))
        assertEquals(Route.NotFound, HttpServer.route("GET", "/$key/claude", key))
        assertEquals(Route.NotFound, HttpServer.route("PUT", "/$key/note", key))
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
        for (rest in listOf("video", "audio", "rec/", "claude/", "claude/../note", "../$key/note", "index.html")) {
            assertEquals(rest, Route.NotFound, HttpServer.route("POST", "/$key/$rest", key))
            assertEquals(rest, Route.NotFound, HttpServer.route("GET", "/$key/$rest", key))
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
