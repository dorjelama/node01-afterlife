package dev.node01.afterlife

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Whether you are at the desk, and where: the front camera's own face
 * detection, done by the camera hardware rather than by a library.
 *
 * Frames exist only to keep the camera running. Each one is handed straight
 * back, unread; nothing is stored, served or logged. Only the rectangle of
 * the largest face leaves this class, as a gaze direction.
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
) {
    @Volatile
    private var running = false

    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /** Only touched on [thread]. */
    private var lastReportMs = 0L

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
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)

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
