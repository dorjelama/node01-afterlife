package dev.node01.afterlife

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * NODE's whole interface: a small HTTP/1.1 server on the LAN, one thread per
 * connection.
 *
 * Every path sits under a random key -- `/<key>/...`, shown on the phone's
 * screen -- so finding the port is not enough to open the camera or the
 * microphone. It is plain HTTP: anyone able to sniff the Wi-Fi can read the key
 * and the streams. Home LAN only; never port-forward it.
 *
 * One instance per session: [start], then [stop], then discard.
 */
class HttpServer(
    private val key: String,
    private val page: ByteArray,
    private val recorder: LoopRecorder,
    private val jpegHub: Hub,
    private val pcmHub: Hub,
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

    /** Live streams notice within a second and hang up. */
    fun stop() {
        socket?.close()
        socket = null
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                client.soTimeout = REQUEST_TIMEOUT_MS
                // Live chunks are small; Nagle would hold their tails for an ACK.
                client.tcpNoDelay = true
                val path = readGetPath(BufferedInputStream(client.getInputStream())) ?: return
                val out = BufferedOutputStream(client.getOutputStream(), OUT_BUFFER_BYTES)
                when (val r = route(path, key)) {
                    Route.Page -> send(out, 200, "text/html; charset=utf-8", page)
                    Route.Video -> streamVideo(out)
                    Route.Audio -> streamAudio(out)
                    Route.Clips -> send(out, 200, "text/html; charset=utf-8", clipList())
                    is Route.Clip -> sendClip(out, r.name)
                    Route.NotFound -> notFound(out)
                }
                out.flush()
            } catch (e: Exception) {
                // Usually the viewer closed the tab or left Wi-Fi range. Whatever a
                // client does, it costs that connection only: an uncaught exception
                // here would take the camera down with it.
            }
        }
    }

    /**
     * Each boundary goes out right after its frame, not ahead of the next one:
     * browsers only show a part once they see the boundary that ends it.
     */
    private fun streamVideo(out: OutputStream) {
        out.write(head(200, "multipart/x-mixed-replace; boundary=frame"))
        out.write(BOUNDARY)
        out.flush()
        relay(jpegHub) { jpeg ->
            out.write("Content-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
            out.write(jpeg)
            out.write(CRLF)
            out.write(BOUNDARY)
            out.flush()
        }
    }

    /** Raw signed 16-bit little-endian mono PCM at 48 kHz; the page plays it with Web Audio. */
    private fun streamAudio(out: OutputStream) {
        out.write(head(200, "application/octet-stream"))
        relay(pcmHub) { pcm ->
            out.write(pcm)
            out.flush()
        }
    }

    /** Copies [hub] to one viewer until it hangs up or the server stops. */
    private fun relay(hub: Hub, write: (ByteArray) -> Unit) {
        val queue = hub.subscribe()
        try {
            while (socket != null) {
                val chunk = queue.poll(1, TimeUnit.SECONDS) ?: continue
                write(chunk)
            }
        } finally {
            hub.unsubscribe(queue)
        }
    }

    // Clip names are fixed to [LoopRecorder.CLIP_NAME], so they need no HTML escaping.
    private fun clipList(): ByteArray = buildString {
        append("<!doctype html><html lang=en><meta charset=utf-8>")
        append("<meta name=viewport content=\"width=device-width, initial-scale=1\">")
        append("<title>NODE recordings</title>")
        append("<style>body{margin:0;padding:16px;background:#000;color:#c4c4c4;")
        append("font:14px/1.6 ui-monospace,monospace}a{color:#ffb000}li{margin:6px 0}</style>")
        append("<p><a href=\"../\">&larr; live</a></p>")
        val clips = recorder.finishedClips()
        if (clips.isEmpty()) append("<p>No finished clips yet.</p>")
        append("<ul>")
        for (f in clips) {
            append("<li><a href=\"${f.name}\" download>${f.name}</a> ${f.length() / 1_000_000} MB</li>")
        }
        append("</ul></html>")
    }.toByteArray()

    private fun sendClip(out: OutputStream, name: String) {
        val file = recorder.finishedClip(name) ?: return notFound(out)
        out.write(head(200, "video/mp4", file.length()))
        file.inputStream().use { it.copyTo(out, OUT_BUFFER_BYTES) }
    }

    private fun notFound(out: OutputStream) = send(out, 404, "text/plain", "not found\n".toByteArray())

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray) {
        out.write(head(status, type, body.size.toLong()))
        out.write(body)
    }

    private fun head(status: Int, type: String, length: Long = -1): ByteArray = buildString {
        append("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
        append("Content-Type: $type\r\n")
        if (length >= 0) append("Content-Length: $length\r\n")
        append("Cache-Control: no-store\r\n")
        append("Connection: close\r\n\r\n")
    }.toByteArray()

    sealed interface Route {
        data object Page : Route
        data object Video : Route
        data object Audio : Route
        data object Clips : Route
        data class Clip(val name: String) : Route
        data object NotFound : Route
    }

    companion object {
        const val PORT = 8080

        private const val REQUEST_TIMEOUT_MS = 5_000
        private const val OUT_BUFFER_BYTES = 64 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100
        private val CRLF = "\r\n".toByteArray()
        private val BOUNDARY = "--frame\r\n".toByteArray()

        /** Pure, so the key check and the path-traversal guard are unit-tested. */
        internal fun route(path: String, key: String): Route {
            val prefix = "/$key/".toByteArray()
            val head = path.take(prefix.size).toByteArray()
            // Constant-time: the key is all that stands between the LAN and the microphone.
            if (!MessageDigest.isEqual(head, prefix)) return Route.NotFound
            val rest = path.substring(prefix.size).substringBefore('?')
            val clip = rest.removePrefix("rec/")
            return when {
                rest == "" -> Route.Page
                rest == "video" -> Route.Video
                rest == "audio" -> Route.Audio
                rest == "rec/" -> Route.Clips
                rest.startsWith("rec/") && LoopRecorder.CLIP_NAME.matches(clip) -> Route.Clip(clip)
                else -> Route.NotFound
            }
        }

        /**
         * Reads the request head, bounded so a misbehaving client cannot grow
         * memory, and returns the path of a GET. Null for anything else.
         */
        private fun readGetPath(input: InputStream): String? {
            val requestLine = input.readLine() ?: return null
            // Drain the headers too: closing with unread input resets the
            // connection, and the browser may then drop the response.
            repeat(MAX_HEADERS) {
                val header = input.readLine() ?: return null
                if (header.isEmpty()) {
                    val parts = requestLine.split(' ')
                    return if (parts.size == 3 && parts[0] == "GET") parts[1] else null
                }
            }
            return null
        }

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
