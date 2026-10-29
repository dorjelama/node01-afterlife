package dev.node01.afterlife

import dev.node01.afterlife.HttpServer.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpServerTest {

    private val key = "abcdefghjk"
    private val clip = "NODE_20261029_120000.mp4"

    @Test
    fun routesUnderTheKey() {
        assertEquals(Route.Page, HttpServer.route("/$key/", key))
        assertEquals(Route.Video, HttpServer.route("/$key/video", key))
        assertEquals(Route.Video, HttpServer.route("/$key/video?1730000000", key))
        assertEquals(Route.Audio, HttpServer.route("/$key/audio", key))
        assertEquals(Route.Clips, HttpServer.route("/$key/rec/", key))
        assertEquals(Route.Clip(clip), HttpServer.route("/$key/rec/$clip", key))
    }

    @Test
    fun wrongOrMissingKeyIsNotFound() {
        for (path in listOf("/", "/video", "/zzzzzzzzzz/", "/abcdefghj/", "/$key", "/${key}x/", "/ABCDEFGHJK/")) {
            assertEquals(path, Route.NotFound, HttpServer.route(path, key))
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
            assertEquals(name, Route.NotFound, HttpServer.route("/$key/rec/$name", key))
        }
    }
}
