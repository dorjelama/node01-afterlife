package dev.node01.afterlife

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * NODE's door to the LAN: a small HTTP/1.1 server, one thread per connection.
 *
 * - `GET  /<key>/`        the page: send a desk note, set up Claude Code
 * - `POST /<key>/claude`  a Claude Code hook event, as the hook's own JSON
 * - `POST /<key>/note`    a desk note, as a form: `text`, `in` (minutes)
 * - `POST /<key>/face`    plays an animation or holds an expression, as a form: `play`
 *
 * Every path sits under a random key shown on the phone's screen; anything
 * else is a 404. It is plain HTTP, so anyone able to sniff the Wi-Fi can read
 * the key and what is sent. Home LAN only; never port-forward it. There is no
 * camera route: what the camera sees never leaves the phone.
 *
 * [onClaude], [onNote] and [onFace] are called on the connection's thread.
 *
 * One instance per session: [start], then [stop], then discard.
 */
class HttpServer(
    private val key: String,
    private val page: ByteArray,
    private val onClaude: (Map<String, String>) -> Unit,
    private val onNote: (text: String, inMinutes: Int) -> Unit,
    private val onFace: (play: String) -> Unit,
) {
    @Volatile
    private var socket: ServerSocket? = null

    /** Throws if the port cannot be bound. */
    fun start() {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        socket = server
        thread(name = "node-http") {
            while (true) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break // closed by stop()
                }
                thread(name = "node-http-client") { serve(client) }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                client.soTimeout = REQUEST_TIMEOUT_MS
                val input = BufferedInputStream(client.getInputStream())
                val out = BufferedOutputStream(client.getOutputStream(), OUT_BUFFER_BYTES)
                val head = readHead(input) ?: return
                when (route(head.method, head.path, key)) {
                    Route.Page -> send(out, 200, "text/html; charset=utf-8", page)
                    Route.Claude -> readBody(head, input, out)?.let { body ->
                        respond(out, 204)
                        onClaude(JsonFields.extract(String(body, Charsets.UTF_8), ClaudeSessions.FIELDS))
                    }
                    Route.Note -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onNote(form["text"].orEmpty(), form["in"]?.toIntOrNull() ?: 0)
                    }
                    Route.Face -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onFace(form["play"].orEmpty())
                    }
                    Route.NotFound -> notFound(out)
                }
                out.flush()
            } catch (e: Exception) {
                // A client that hangs up or sends nonsense costs its own
                // connection only: uncaught, it would take NODE down with it.
            }
        }
    }

    /**
     * Keeps the first [KEEP_BYTES] of the body and reads past the rest. Hook
     * events after a tool call carry the tool's whole output, but the fields
     * NODE wants are all small. Null, with the reply already sent, if the body
     * cannot be taken.
     */
    private fun readBody(head: Head, input: InputStream, out: OutputStream): ByteArray? {
        if (head.chunked || head.contentLength < 0) return null.also { respond(out, 411) }
        if (head.contentLength > MAX_BODY_BYTES) return null.also { respond(out, 413) }
        if (head.expectContinue) {
            // curl waits a full second for this before sending a large body.
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        val kept = ByteArray(minOf(head.contentLength, KEEP_BYTES.toLong()).toInt())
        var got = 0
        while (got < kept.size) {
            val n = input.read(kept, got, kept.size - got)
            if (n < 0) return null
            got += n
        }
        var left = head.contentLength - kept.size
        val scratch = ByteArray(OUT_BUFFER_BYTES)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) return null
            left -= n
        }
        return kept
    }

    private fun notFound(out: OutputStream) = send(out, 404, "text/plain", "not found\n".toByteArray())

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray) {
        out.write(head(status, type, body.size.toLong()))
        out.write(body)
    }

    private fun respond(out: OutputStream, status: Int) {
        out.write(head(status, null, if (status == 204) -1 else 0))
        out.flush()
    }

    private fun head(status: Int, type: String?, length: Long): ByteArray = buildString {
        append("HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n")
        if (type != null) append("Content-Type: $type\r\n")
        if (length >= 0) append("Content-Length: $length\r\n")
        append("Cache-Control: no-store\r\n")
        append("Connection: close\r\n\r\n")
    }.toByteArray()

    sealed interface Route {
        data object Page : Route
        data object Claude : Route
        data object Note : Route
        data object Face : Route
        data object NotFound : Route
    }

    internal data class Head(
        val method: String,
        val path: String,
        /** -1 when the request did not say. */
        val contentLength: Long,
        val expectContinue: Boolean,
        val chunked: Boolean,
    )

    companion object {
        const val PORT = 8081

        private const val REQUEST_TIMEOUT_MS = 5_000
        private const val OUT_BUFFER_BYTES = 64 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100

        /** Of each body, only this much is kept. */
        const val KEEP_BYTES = 64 * 1024

        /** Bodies past this are refused unread. */
        const val MAX_BODY_BYTES = 16L * 1024 * 1024

        private val REASONS = mapOf(
            200 to "OK",
            204 to "No Content",
            404 to "Not Found",
            411 to "Length Required",
            413 to "Content Too Large",
        )

        /** Pure, so the key check is unit-tested. Wrong method is a 404 too. */
        internal fun route(method: String, path: String, key: String): Route {
            val prefix = "/$key/".toByteArray()
            val head = path.take(prefix.size).toByteArray()
            // Constant-time: the key is all that stands between the LAN and NODE.
            if (!MessageDigest.isEqual(head, prefix)) return Route.NotFound
            val rest = path.substring(prefix.size).substringBefore('?')
            return when {
                method == "GET" && rest == "" -> Route.Page
                method == "POST" && rest == "claude" -> Route.Claude
                method == "POST" && rest == "note" -> Route.Note
                method == "POST" && rest == "face" -> Route.Face
                else -> Route.NotFound
            }
        }

        /**
         * Reads the request line and headers, bounded so a misbehaving client
         * cannot grow memory. Null for anything malformed.
         */
        internal fun readHead(input: InputStream): Head? {
            val requestLine = input.readLine() ?: return null
            var length = -1L
            var expect = false
            var chunked = false
            repeat(MAX_HEADERS) {
                val header = input.readLine() ?: return null
                if (header.isEmpty()) {
                    val parts = requestLine.split(' ')
                    return if (parts.size == 3) Head(parts[0], parts[1], length, expect, chunked) else null
                }
                val value = header.substringAfter(':', "").trim()
                when (header.substringBefore(':').trim().lowercase()) {
                    "content-length" -> length = value.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
                    "expect" -> expect = value.equals("100-continue", ignoreCase = true)
                    "transfer-encoding" -> chunked = !value.equals("identity", ignoreCase = true)
                }
            }
            return null
        }

        /** `a=1&b=two+words` -> {a=1, b=two words}. Malformed escapes drop that pair. */
        internal fun parseForm(body: String): Map<String, String> =
            body.split('&').mapNotNull { pair ->
                if (pair.isEmpty()) return@mapNotNull null
                runCatching {
                    URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                        URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                }.getOrNull()
            }.toMap()

        private fun InputStream.readLine(): String? {
            val line = StringBuilder()
            while (line.length < MAX_LINE) {
                when (val b = read()) {
                    -1 -> return null
                    '\n'.code -> return line.trimEnd('\r').toString()
                    else -> line.append(b.toChar())
                }
            }
            return null
        }
    }
}
