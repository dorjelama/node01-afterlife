package dev.node01.afterlife

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Whether you are at the desk, and where: the front camera's own face
 * detection, done by the camera hardware rather than by a library.
 *
 * Frames exist to keep the camera running, and are handed straight back
 * unread -- unless someone has the page's camera view open. Then a few a
 * second are turned upright, compressed to JPEG and handed to [live] for
 * that viewer; still nothing is stored or logged. The frames are the small
 * ones face detection already runs on, so the view is low resolution by
 * construction. While hand tracking is on, frames also go to [hands], which
 * reads them on the phone. Otherwise only the rectangle of the largest face
 * leaves this class, as a gaze direction.
 *
 * Camera callbacks and results run on a private thread; [onFace] and
 * [onNoFace] are delivered on the main thread, at most every [REPORT_MS].
 *
 * One instance per session: [start], then [stop], then discard.
 */
class Presence(
    private val context: Context,
    /** Display rotation in degrees: 0, 90, 180 or 270. */
    private val displayDegrees: Int,
    private val onFace: (x: Float, y: Float) -> Unit,
    private val onNoFace: () -> Unit,
    private val onError: (String) -> Unit,
    /** The page's camera view: filled only while it has a viewer. */
    private val live: Hub,
) {
    @Volatile
    private var running = false

    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /** Hand tracking, while that mode is on: frames are read on the phone for it, and go nowhere else. */
    @Volatile
    var hands: HandTracker? = null

    /** Only touched on [thread]. */
    private var lastReportMs = 0L
    private var lastLiveMs = 0L

    /**
     * Throws if the front camera cannot do face detection at all. Call [stop]
     * afterwards either way. The caller has checked CAMERA.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } ?: error("no front camera")
        val chars = manager.getCameraCharacteristics(id)

        val modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.toList().orEmpty()
        val mode = when {
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE in modes -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL in modes -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
            else -> error("front camera has no face detection")
        }
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: error("no sensor size")
        val rotation = (chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) + displayDegrees
        // The camera only needs to run; the smallest frames cost the least.
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width >= MIN_FRAME_WIDTH }
            ?.minByOrNull { it.width * it.height }
            ?: error("no small camera size")
        // The lowest frame rate on offer: faces do not need more, and the phone stays cool.
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= MIN_FPS }
            ?.minWithOrNull(compareBy({ it.upper }, { it.lower }))

        val thread = HandlerThread("node-face").apply { start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        this.reader = reader
        reader.setOnImageAvailableListener({ onFrame(it, rotation) }, handler)

        val results = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                val now = SystemClock.elapsedRealtime()
                val faces = result.get(CaptureResult.STATISTICS_FACES)
                // No face is reported less often: nothing changes while nobody is there.
                val interval = if (faces.isNullOrEmpty()) NO_FACE_REPORT_MS else REPORT_MS
                if (now - lastReportMs < interval) return
                lastReportMs = now
                val face = faces?.maxByOrNull { it.bounds.width() * it.bounds.height() }
                if (face == null) {
                    report { onNoFace() }
                } else {
                    val (x, y) = toGaze(
                        face.bounds.exactCenterX() / active.width(),
                        face.bounds.exactCenterY() / active.height(),
                        rotation,
                    )
                    report { onFace(x, y) }
                }
            }
        }

        manager.openCamera(id, context.mainExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                // stop() may have run while the camera was still opening.
                if (!running) return device.close()
                camera = device
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(reader.surface)),
                        context.mainExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!running) return s.close()
                                session = s
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, mode)
                                        if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                                    }.build()
                                    s.setRepeatingRequest(request, results, handler)
                                } catch (e: Exception) {
                                    fail("camera: ${e.message}")
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) = fail("camera refused the stream setup")
                        },
                    ),
                )
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                fail("camera taken by another app")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                fail("camera error $error")
            }
        })
    }

    /** Safe after a failed or partial [start]. */
    fun stop() {
        running = false
        session?.close()
        camera?.close()
        session = null
        camera = null
        thread?.quitSafely()
        thread?.join(JOIN_MS)
        thread = null
        reader?.close()
        reader = null
    }

    /**
     * On [thread]. Handed straight back, unread, unless someone is watching
     * or hand tracking is ready for a frame. [rotation] turns the frame
     * upright, as a photo from this camera would be.
     */
    private fun onFrame(reader: ImageReader, rotation: Int) {
        val image = reader.acquireLatestImage() ?: return
        val now = SystemClock.elapsedRealtime()
        val tracker = hands?.takeIf { it.ready }
        val watched = live.hasSubscribers && now - lastLiveMs >= LIVE_INTERVAL_MS
        if (tracker == null && !watched) return image.close()
        val w = image.width
        val h = image.height
        try {
            // Copied out first, so the camera has its buffer back before the compressing starts.
            val upright = rotateNv21(image.use { nv21(it) }, w, h, rotation)
            val (uw, uh) = if (((rotation % 360) + 360) % 180 == 0) w to h else h to w
            tracker?.offer(upright, uw, uh, now)
            if (!watched) return
            lastLiveMs = now
            val jpeg = ByteArrayOutputStream(JPEG_BUFFER_BYTES)
            YuvImage(upright, ImageFormat.NV21, uw, uh, null).compressToJpeg(Rect(0, 0, uw, uh), LIVE_QUALITY, jpeg)
            live.publish(jpeg.toByteArray())
        } catch (e: RuntimeException) {
            // A bad frame costs that frame, never the face detection.
            Log.w(TAG, "camera view frame dropped: ${e.message}")
        }
    }

    private fun report(block: () -> Unit) {
        context.mainExecutor.execute { if (running) block() }
    }

    /**
     * Reported on the main thread, and re-checked there -- where [stop] runs --
     * so a failure racing a deliberate stop is dropped instead of reported.
     */
    private fun fail(reason: String) {
        Log.e(TAG, reason)
        context.mainExecutor.execute { if (running) onError(reason) }
    }

    companion object {
        private const val TAG = "NODE"

        private const val MIN_FRAME_WIDTH = 320
        private const val MIN_FPS = 10
        const val REPORT_MS = 100L
        const val NO_FACE_REPORT_MS = 500L
        private const val JOIN_MS = 1_000L

        /** The camera view: about 10 frames a second, not 100 ms, or frames that land a hair early are skipped. */
        private const val LIVE_INTERVAL_MS = 90L
        private const val LIVE_QUALITY = 70
        private const val JPEG_BUFFER_BYTES = 32 * 1024

        /**
         * An NV21 frame [w] by [h], turned clockwise by [degrees] (any multiple
         * of 90); a quarter turn swaps its width and height. Luma moves pixel by
         * pixel, and each chroma pair, which covers two by two pixels, as one.
         */
        internal fun rotateNv21(src: ByteArray, w: Int, h: Int, degrees: Int): ByteArray {
            val d = ((degrees % 360) + 360) % 360
            if (d == 0) return src
            // Where (x, y) of a cw by ch plane lands in the turned plane, as an index.
            fun to(x: Int, y: Int, cw: Int, ch: Int) = when (d) {
                90 -> x * ch + (ch - 1 - y)
                180 -> (ch - 1 - y) * cw + (cw - 1 - x)
                else -> (cw - 1 - x) * ch + y
            }
            val out = ByteArray(src.size)
            for (y in 0 until h) for (x in 0 until w) out[to(x, y, w, h)] = src[y * w + x]
            val luma = w * h
            for (y in 0 until h / 2) {
                for (x in 0 until w / 2) {
                    val from = luma + (y * (w / 2) + x) * 2
                    val at = luma + to(x, y, w / 2, h / 2) * 2
                    out[at] = src[from]
                    out[at + 1] = src[from + 1]
                }
            }
            return out
        }

        /**
         * YUV_420_888 to the NV21 layout YuvImage wants, honouring row and pixel
         * strides: this phone's Qualcomm HAL pads rows and interleaves chroma
         * (pixelStride 2), other vendors hand back planar chroma (pixelStride 1).
         */
        private fun nv21(image: Image): ByteArray {
            val w = image.width
            val h = image.height
            val out = ByteArray(w * h * 3 / 2)
            val (yPlane, uPlane, vPlane) = image.planes
            var pos = 0
            val y = yPlane.buffer
            for (row in 0 until h) {
                y.position(row * yPlane.rowStride)
                y.get(out, pos, w)
                pos += w
            }
            val u = uPlane.buffer
            val v = vPlane.buffer
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    out[pos++] = v.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                    out[pos++] = u.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                }
            }
            return out
        }

        /**
         * Flip either axis if, on a new phone or mount, the eyes look away from
         * you instead of at you. Right for the Redmi Note 8 in landscape.
         */
        const val GAZE_FLIP_X = false
        const val GAZE_FLIP_Y = false

        /**
         * A face centre in sensor coordinates (0..1 across the active array) to
         * a gaze in screen terms, -1..1 with x to the right and y down, as seen
         * by you facing the screen.
         *
         * The sensor point is turned clockwise by [rotationDegrees] -- sensor
         * orientation plus display rotation, the same angle that would turn a
         * front-camera photo upright -- then mirrored, because the front
         * camera sees you the other way round from how you see the screen.
         */
        internal fun toGaze(sx: Float, sy: Float, rotationDegrees: Int): Pair<Float, Float> {
            val x = sx * 2f - 1f
            val y = sy * 2f - 1f
            val (rx, ry) = when (((rotationDegrees % 360) + 360) % 360) {
                90 -> -y to x
                180 -> -x to -y
                270 -> y to -x
                else -> x to y
            }
            val gx = -rx
            return (if (GAZE_FLIP_X) -gx else gx) to (if (GAZE_FLIP_Y) -ry else ry)
        }
    }
}
