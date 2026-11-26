package dev.node01.afterlife

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.Image
import android.os.SystemClock
import java.io.File

/**
 * The rear camera on the tank for as long as its light is on, beside the front
 * camera (this phone runs both): 720p clips with no sound into [videoDir],
 * upright live pictures into [live] for the page while someone watches, and
 * from the frames, once a minute, a [TankCam.Look] -- a picture and a minute
 * of small grey frames -- for [onMinute] on the main thread.
 *
 * [start] throws if the camera or the encoder will not come up; [stop] after,
 * either way. One session per instance.
 */
class TankWatch(
    private val context: Context,
    live: Hub,
    displayDegrees: Int,
    videoDir: File,
    private val onMinute: (TankCam.Look) -> Unit,
    onError: (String) -> Unit,
) {
    private val rotation = context.getSystemService(CameraManager::class.java).let { m ->
        TankCam.rearId(m)?.let { TankCam.rotation(m, it, displayDegrees) } ?: 0
    }
    private val recorder = LoopRecorder(videoDir, VIDEO_CAP_BYTES, withAudio = false, orientationHint = rotation)
    private val camcorder = Camcorder(
        context, recorder, live, pcmHub = null, onError = onError,
        videoWidth = TankCam.WIDTH, videoHeight = TankCam.HEIGHT, videoBitrate = VIDEO_BITRATE,
        onFrame = ::onFrame, liveRotation = rotation,
    )

    // Only touched on the camera's live thread.
    private var settledMs = 0L
    private var minuteFromMs = 0L
    private var nextFrameMs = 0L
    private val frames = ArrayList<ByteArray>()

    /** A picture asked for out of turn, taken from the next settled frame. */
    @Volatile
    private var pending: ((TankCam.Look) -> Unit)? = null

    fun start() = camcorder.start()

    fun stop() = camcorder.stop()

    /** A picture now, rather than at the minute: [then] gets it, with the minute's frames so far, on the main thread. */
    fun capture(then: (TankCam.Look) -> Unit) {
        pending = then
    }

    private fun onFrame(image: Image) {
        val now = SystemClock.elapsedRealtime()
        // The first frames are dark while exposure finds its level.
        if (settledMs == 0L) settledMs = now + TankCam.SETTLE_MS
        if (now < settledMs) return
        pending?.let { then ->
            pending = null
            val (jpeg, argb) = TankCam.picture(nv21(image), rotation)
            val look = TankCam.Look(jpeg, argb, frames.toList() + TankCam.smallLuma(image))
            context.mainExecutor.execute { then(look) }
        }
        if (now < nextFrameMs) return
        if (minuteFromMs == 0L) minuteFromMs = now
        nextFrameMs = now + TankCam.FRAME_GAP_MS
        frames += TankCam.smallLuma(image)
        if (now - minuteFromMs < MINUTE_MS) return
        // Once a minute, on this thread: the picture costs a tenth of a second, the recording none.
        val (jpeg, argb) = TankCam.picture(nv21(image), rotation)
        val look = TankCam.Look(jpeg, argb, frames.toList())
        frames.clear()
        minuteFromMs = now
        context.mainExecutor.execute { onMinute(look) }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        // About 0.9 GB an hour; three days of a tank lit twelve hours a day.
        const val VIDEO_BITRATE = 2_000_000
        const val VIDEO_CAP_BYTES = 32_000_000_000L
    }
}
