package dev.node01.afterlife

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * One look at the aquarium through the rear camera, a few seconds long: open
 * it, let exposure and focus settle, keep one picture and a short burst of
 * small grey frames for movement, close it. The front camera must be closed
 * first -- this phone cannot run both.
 *
 * [onDone] gets the look, or null if the camera could not be had, on the main
 * thread. One look per instance.
 */
class TankCam(
    private val context: Context,
    /** The display's rotation, 0, 90, 180 or 270, so the picture comes out the right way up. */
    private val displayDegrees: Int,
    private val onDone: (Look?) -> Unit,
) {
    /** How far to turn the sensor's picture clockwise: a rear camera's turn runs against the display's. */
    private var rotation = 0

    /** The picture as a JPEG, its colours scaled right down, and the burst of grey frames. */
    class Look(val jpeg: ByteArray, val argb: IntArray, val frames: List<ByteArray>)

    private val thread = HandlerThread("node-tank-cam").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var startedMs = 0L
    private var nextFrameMs = 0L
    private var picture: ByteArray? = null
    private val frames = mutableListOf<ByteArray>()
    private var finished = false

    fun start() = handler.post {
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val rear = rearId(manager) ?: return@post finish(null)
            rotation = rotation(manager, rear, displayDegrees)
            val r = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 3)
            reader = r
            r.setOnImageAvailableListener({ onImage(it) }, handler)
            manager.openCamera(rear, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (finished) return d.close()
                    device = d
                    capture(d, r)
                }

                override fun onDisconnected(d: CameraDevice) = finish(null)
                override fun onError(d: CameraDevice, error: Int) = finish(null)
            }, handler)
            // A camera that never answers must not keep the face blind.
            handler.postDelayed({ finish(null) }, TIMEOUT_MS)
        } catch (e: Exception) {
            finish(null)
        }
    }

    /** Gives the camera back at once; [onDone] still comes, with null. */
    fun cancel() = handler.post { finish(null) }

    private fun capture(d: CameraDevice, r: ImageReader) {
        d.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(r.surface)),
                { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (finished) return s.close()
                        session = s
                        try {
                            // Preview: continuous autofocus and quick auto-exposure, which is all a look needs.
                            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                            s.setRepeatingRequest(request.build(), null, handler)
                            startedMs = SystemClock.elapsedRealtime()
                        } catch (e: Exception) {
                            finish(null)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = finish(null)
                },
            ),
        )
    }

    private fun onImage(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (finished || startedMs == 0L || now - startedMs < SETTLE_MS || now < nextFrameMs) return
            if (picture == null) picture = nv21(it)
            frames += smallLuma(it)
            nextFrameMs = now + FRAME_GAP_MS
        }
        // After the image is handed back: finishing closes its reader.
        if (frames.size >= BURST_FRAMES) finish(picture)
    }

    /** Closes everything, once; turns [nv21] into the look on this thread, and hands it over on the main one. */
    private fun finish(nv21: ByteArray?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        runCatching { session?.close() }
        runCatching { device?.close() }
        val look = nv21?.let { runCatching { look(it) }.getOrNull() }
        runCatching { reader?.close() }
        thread.quitSafely()
        context.mainExecutor.execute { onDone(look) }
    }

    private fun look(nv21: ByteArray): Look = picture(nv21, rotation).let { (jpeg, argb) -> Look(jpeg, argb, frames.toList()) }

    companion object {
        /** The rear camera's id, or null if there is none. */
        fun rearId(manager: CameraManager): String? = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        /** How far to turn the rear camera's picture clockwise to stand upright: a rear camera's turn runs against the display's. */
        fun rotation(manager: CameraManager, rear: String, displayDegrees: Int): Int {
            val sensor = manager.getCameraCharacteristics(rear).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            return ((sensor - displayDegrees) % 360 + 360) % 360
        }

        /** A [WIDTH]×[HEIGHT] NV21 frame as an upright JPEG, and its colours scaled right down for [TankLook.green]. */
        fun picture(nv21: ByteArray, rotation: Int): Pair<ByteArray, IntArray> {
            val upright = Presence.rotateNv21(nv21, WIDTH, HEIGHT, rotation)
            val (w, h) = if (rotation % 180 == 0) WIDTH to HEIGHT else HEIGHT to WIDTH
            val jpeg = ByteArrayOutputStream(JPEG_BUFFER_BYTES).also {
                YuvImage(upright, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, it)
            }.toByteArray()
            val small = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = SCALE })
            val argb = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            small.recycle()
            return jpeg to argb
        }

        /** Every [SCALE]th luma sample of every [SCALE]th row: an 80×45 grey frame, enough to see a fish move. */
        fun smallLuma(image: Image): ByteArray {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val w = WIDTH / SCALE
            val h = HEIGHT / SCALE
            return ByteArray(w * h) { i -> buffer.get((i / w) * SCALE * plane.rowStride + (i % w) * SCALE * plane.pixelStride) }
        }

        const val WIDTH = 1280
        const val HEIGHT = 720
        private const val SCALE = 16
        /** The width of [smallLuma]'s grey frames. */
        const val SMALL_WIDTH = WIDTH / SCALE
        /** Exposure and focus settling after the camera starts. */
        const val SETTLE_MS = 1_500L
        const val FRAME_GAP_MS = 200L
        /** Three seconds of movement at five frames a second. */
        private const val BURST_FRAMES = 15
        private const val TIMEOUT_MS = 10_000L
        private const val JPEG_QUALITY = 80
        private const val JPEG_BUFFER_BYTES = 256 * 1024
    }
}
