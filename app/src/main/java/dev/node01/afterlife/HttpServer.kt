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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * NODE's door to the LAN: a small HTTP/1.1 server, one thread per connection.
 *
 * - `GET  /<key>/`        the page: send a desk note, set up Claude Code
 * - `POST /<key>/claude`  a Claude Code hook event, as the hook's own JSON
 * - `POST /<key>/note`    a desk note, as a form: `text`, `in` (minutes)
 * - `POST /<key>/face`    plays an animation or holds an expression, as a form: `play`
 * - `POST /<key>/say`     a line to read out once, as a form: `text`
 * - `POST /<key>/usage`   Claude's 5-hour session usage from the status line, as a form:
 *                         `pct` (0..100), `resets` (Unix seconds)
 * - `POST /<key>/task`    a task to keep until it is marked done, as a form: `title`, `desc`
 * - `POST /<key>/pc`      the PC's load, as a form: `cpu`, `ram`, `disk` (each 0..100)
 * - `GET  /<key>/cam`     the front camera's small live view, as MJPEG, for as long as it is held open
 * - `POST /<key>/mode`    what the phone is, as a form: `to` = `webcam`, `face`, or `release` to leave kiosk
 * - `GET  /<key>/webcam`  the webcam's page: `video` (MJPEG), `audio` (PCM) and `rec/` (clips) beside it
 * - `GET  /<key>/activity` the camera activities allowed, and today's log of them, as JSON
 * - `POST /<key>/activity` which camera activities NODE may notice, as a form: `watch` (comma-separated)
 * - `GET  /<key>/aquarium` the aquarium's page: its care, its days, the live view and training
 * - `GET  /<key>/tank`     the aquarium: its details, jobs, likely water state and the week's log, as JSON
 * - `POST /<key>/tank`     the aquarium's details, as a form: `name`, `litres`, `kind`, `livestock`, `every.<JOB>` (days)
 * - `POST /<key>/tank/done` an aquarium job done, as a form: `job` (`FEED`, `WATER_CHANGE`, ...)
 * - `GET  /<key>/tank/photo` the rear camera's newest picture of the tank, as a JPEG; 404 before the first
 * - `POST /<key>/tank/describe` takes a fresh picture of the tank now and has the model on the server describe it
 *                         (a minute or two)
 * - `POST /<key>/tank/label` what is really in that picture, as a form: `text`; kept beside it for training
 * - `GET  /<key>/data`     for DJSERVER's collector: the tank's finished clips and the last two days' training
 *                         files, with sizes, as JSON; `GET data/<path>` fetches one
 *
 * Every path sits under a random key shown on the phone's screen; anything
 * else is a 404. It is plain HTTP, so anyone able to sniff the Wi-Fi can read
 * the key and what is sent -- the camera views and the microphone included.
 * Home LAN only; never port-forward it. The front camera's view is only ever
 * live. The rear camera and the microphone run, and record clips to the phone,
 * only in webcam mode; in face mode `audio` stays silent, and `video` shows the tank while the rear camera
 * watches it.
 *
 * [onClaude], [onNote], [onFace], [onSay], [onUsage], [onTask], [onPc], [onServerTemp], [onMode], [activity], [onWatch], [tank],
 * [onTank], [onTankDone], [tankPhoto], [onTankDescribe] and [onTankLabel] are called on the connection's thread.
 *
 * One instance per session: [start], then [stop], then discard.
 */
class HttpServer(
    private val key: String,
    private val page: ByteArray,
    private val onClaude: (Map<String, String>) -> Unit,
    private val onNote: (text: String, inMinutes: Int) -> Unit,
    private val onFace: (play: String) -> Unit,
    private val onSay: (text: String) -> Unit,
    private val onUsage: (SessionUsage) -> Unit,
    private val onTask: (title: String, description: String) -> Unit,
    private val onPc: (PcStats) -> Unit,
    /** The AI server's CPU temperature, in whole degrees C, as its cron job reports it. */
    private val onServerTemp: (celsius: Int) -> Unit,
    /** JPEG frames for the camera view, filled by [Presence] only while someone is watching. */
    private val cam: Hub,
    private val webcamPage: ByteArray,
    /** The aquarium's own page, beside the main one. */
    private val aquariumPage: ByteArray,
    private val recorder: LoopRecorder,
    private val video: Hub,
    private val audio: Hub,
    private val onMode: (to: String) -> Unit,
    /** The camera activities allowed and today's log of them, as JSON; on the connection's thread. */
    private val activity: () -> String,
    private val onWatch: (watch: String) -> Unit,
    /** The aquarium, its jobs and its log, as JSON; on the connection's thread. */
    private val tank: () -> String,
    private val onTank: (form: Map<String, String>) -> Unit,
    private val onTankDone: (job: String) -> Unit,
    /** The newest picture of the tank as a JPEG, or null if there is none yet; on the connection's thread. */
    private val tankPhoto: () -> ByteArray?,
    private val onTankDescribe: () -> Unit,
    private val onTankLabel: (text: String) -> Unit,
    /** For DJSERVER's collector: the tank's files to fetch, with their sizes, as JSON; on the connection's thread. */
    private val dataList: () -> String,
    /** One of those files, by a path [DATA_PATH] has already checked, or null if it is gone. */
    private val dataFile: (path: String) -> java.io.File?,
) {
    @Volatile
    private var socket: ServerSocket? = null

    /** Throws if the port cannot be bound, leaving nothing open behind it. */
    fun start() {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT))
        } catch (e: IOException) {
            server.close()
            throw e
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
                // Camera frames are small; Nagle would hold their tails for an ACK.
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream())
                val out = BufferedOutputStream(client.getOutputStream(), OUT_BUFFER_BYTES)
                val head = readHead(input) ?: return
                when (val r = route(head.method, head.path, key)) {
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
                    Route.Say -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onSay(form["text"].orEmpty())
                    }
                    Route.Usage -> readBody(head, input, out)?.let { body ->
                        val usage = SessionUsage.parse(parseForm(String(body, Charsets.UTF_8)))
                        respond(out, 204)
                        if (usage != null) onUsage(usage)
                    }
                    Route.Task -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onTask(form["title"].orEmpty(), form["desc"].orEmpty())
                    }
                    Route.Pc -> readBody(head, input, out)?.let { body ->
                        val stats = PcStats.parse(parseForm(String(body, Charsets.UTF_8)))
                        respond(out, 204)
                        if (stats != null) onPc(stats)
                    }
                    Route.ServerTemp -> readBody(head, input, out)?.let { body ->
                        val c = parseForm(String(body, Charsets.UTF_8))["temp"]?.trim()?.toFloatOrNull()
                            ?.takeIf { it.isFinite() && it in 0f..150f }?.toInt()
                        respond(out, 204)
                        if (c != null) onServerTemp(c)
                    }
                    Route.Cam -> streamJpeg(out, cam)
                    Route.Mode -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onMode(form["to"].orEmpty())
                    }
                    Route.Webcam -> send(out, 200, "text/html; charset=utf-8", webcamPage)
                    Route.Aquarium -> send(out, 200, "text/html; charset=utf-8", aquariumPage)
                    Route.Video -> streamJpeg(out, video)
                    Route.Audio -> {
                        // Raw signed 16-bit little-endian mono PCM at 48 kHz; the page plays it with Web Audio.
                        out.write(head(200, "application/octet-stream", -1))
                        relay(audio) { pcm ->
                            out.write(pcm)
                            out.flush()
                        }
                    }
                    Route.Activity -> send(out, 200, "application/json", activity().toByteArray())
                    Route.Watch -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onWatch(form["watch"].orEmpty())
                    }
                    Route.Tank -> send(out, 200, "application/json", tank().toByteArray())
                    Route.TankPhoto -> tankPhoto()?.let { send(out, 200, "image/jpeg", it) } ?: notFound(out)
                    Route.TankDescribe -> readBody(head, input, out)?.let {
                        respond(out, 204)
                        onTankDescribe()
                    }
                    Route.TankLabel -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onTankLabel(form["text"].orEmpty())
                    }
                    Route.TankSetUp -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onTank(form)
                    }
                    Route.TankDone -> readBody(head, input, out)?.let { body ->
                        val form = parseForm(String(body, Charsets.UTF_8))
                        respond(out, 204)
                        onTankDone(form["job"].orEmpty())
                    }
                    Route.DataList -> send(out, 200, "application/json", dataList().toByteArray())
                    is Route.DataFile -> sendFile(out, dataFile(r.path))
                    Route.Clips -> send(out, 200, "text/html; charset=utf-8", clipList())
                    is Route.Clip -> sendClip(out, r.name)
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

    /**
     * A camera's view for an `<img>`, until the viewer hangs up or the server
     * stops. While it is subscribed, and only then, the camera compresses
     * frames. Each boundary goes out right after its frame, not ahead of the
     * next one: browsers only show a part once they see the boundary that ends it.
     */
    private fun streamJpeg(out: OutputStream, hub: Hub) {
        out.write(head(200, "multipart/x-mixed-replace; boundary=frame", -1))
        out.write(BOUNDARY)
        out.flush()
        relay(hub) { jpeg ->
            out.write("Content-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
            out.write(jpeg)
            out.write(CRLF)
            out.write(BOUNDARY)
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
        append("<p><a href=\"../webcam\">&larr; live</a></p>")
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

    private fun sendFile(out: OutputStream, file: java.io.File?) {
        if (file == null) return notFound(out)
        val type = when (file.extension) {
            "jpg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> "text/plain; charset=utf-8"
        }
        out.write(head(200, type, file.length()))
        file.inputStream().use { it.copyTo(out, OUT_BUFFER_BYTES) }
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
        data object Say : Route
        data object Usage : Route
        data object Task : Route
        data object Pc : Route
        data object ServerTemp : Route
        data object Cam : Route
        data object Mode : Route
        data object Webcam : Route
        data object Aquarium : Route
        data object Video : Route
        data object Audio : Route
        data object Activity : Route
        data object Watch : Route
        data object Tank : Route
        data object TankSetUp : Route
        data object TankDone : Route
        data object TankPhoto : Route
        data object TankDescribe : Route
        data object TankLabel : Route
        data object DataList : Route
        data class DataFile(val path: String) : Route
        data object Clips : Route
        data class Clip(val name: String) : Route
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

        private val CRLF = "\r\n".toByteArray()
        private val BOUNDARY = "--frame\r\n".toByteArray()

        private val REASONS = mapOf(
            200 to "OK",
            204 to "No Content",
            404 to "Not Found",
            411 to "Length Required",
            413 to "Content Too Large",
        )

        /** The only files the collector may fetch: the tank's finished clips and its training data. */
        val DATA_PATH = Regex("""tank-video/NODE_\d{8}_\d{6}\.mp4|training/(\d{8}/(\d{6}\.jpg|minutes\.csv)|labels\.jsonl|days\.csv)""")

        /** Pure, so the key check is unit-tested. Wrong method is a 404 too. */
        internal fun route(method: String, path: String, key: String): Route {
            val prefix = "/$key/".toByteArray()
            val head = path.take(prefix.size).toByteArray()
            // Constant-time: the key is all that stands between the LAN and NODE.
            if (!MessageDigest.isEqual(head, prefix)) return Route.NotFound
            val rest = path.substring(prefix.size).substringBefore('?')
            val clip = rest.removePrefix("rec/")
            return when {
                method == "GET" && rest == "" -> Route.Page
                method == "POST" && rest == "claude" -> Route.Claude
                method == "POST" && rest == "note" -> Route.Note
                method == "POST" && rest == "face" -> Route.Face
                method == "POST" && rest == "say" -> Route.Say
                method == "POST" && rest == "usage" -> Route.Usage
                method == "POST" && rest == "task" -> Route.Task
                method == "POST" && rest == "pc" -> Route.Pc
                method == "POST" && rest == "server" -> Route.ServerTemp
                method == "GET" && rest == "cam" -> Route.Cam
                method == "POST" && rest == "mode" -> Route.Mode
                method == "GET" && rest == "webcam" -> Route.Webcam
                method == "GET" && rest == "aquarium" -> Route.Aquarium
                method == "GET" && rest == "video" -> Route.Video
                method == "GET" && rest == "audio" -> Route.Audio
                method == "GET" && rest == "activity" -> Route.Activity
                method == "POST" && rest == "activity" -> Route.Watch
                method == "GET" && rest == "tank" -> Route.Tank
                method == "POST" && rest == "tank" -> Route.TankSetUp
                method == "POST" && rest == "tank/done" -> Route.TankDone
                method == "GET" && rest == "tank/photo" -> Route.TankPhoto
                method == "POST" && rest == "tank/describe" -> Route.TankDescribe
                method == "POST" && rest == "tank/label" -> Route.TankLabel
                method == "GET" && rest == "data" -> Route.DataList
                // The fixed pattern is the path-traversal guard, as for clips.
                method == "GET" && rest.startsWith("data/") && DATA_PATH.matches(rest.removePrefix("data/")) ->
                    Route.DataFile(rest.removePrefix("data/"))
                method == "GET" && rest == "rec/" -> Route.Clips
                // The fixed name pattern is the path-traversal guard.
                method == "GET" && rest.startsWith("rec/") && LoopRecorder.CLIP_NAME.matches(clip) -> Route.Clip(clip)
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
