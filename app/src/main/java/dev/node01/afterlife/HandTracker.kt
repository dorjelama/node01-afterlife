package dev.node01.afterlife

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream

/**
 * Hands in front of the front camera, found on the phone by MediaPipe's hand
 * landmarker: [POINTS] points per hand, up to two hands. Nothing leaves the
 * phone. Frames come from [Presence]; one arriving while the last is still
 * being read is dropped, so the model sets the pace.
 *
 * [onHands] gets, on the main thread, every hand's points as x, y pairs, 0..1
 * across and down the frame as you see it -- mirrored, so your right hand is on
 * the right -- and the frame's width over its height. Empty when no hand is in
 * view.
 *
 * Loading the model takes a moment, so construct it only when the mode is
 * turned on, and [close] it when the mode is turned off.
 */
class HandTracker(private val context: Context, private val onHands: (FloatArray, Float) -> Unit) {

    @Volatile
    private var busy = false

    @Volatile
    private var closed = false

    @Volatile
    private var aspect = 4f / 3f

    private val landmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e ->
                busy = false
                Log.w(TAG, "hands: ${e.message}")
            }
            .build(),
    )

    /** False while a frame is still being read: the caller can skip copying the next one. */
    val ready: Boolean get() = !busy && !closed

    /** On the camera thread: an upright NV21 frame, [w] by [h], taken at [timeMs]. */
    fun offer(nv21: ByteArray, w: Int, h: Int, timeMs: Long) {
        if (!ready) return
        busy = true
        aspect = w.toFloat() / h
        try {
            val jpeg = ByteArrayOutputStream(JPEG_BUFFER_BYTES)
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, jpeg)
            val bitmap = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size())
            landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timeMs)
        } catch (e: RuntimeException) {
            // Closed under us, or a bad frame: it costs that frame only.
            busy = false
        }
    }

    private fun onResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        val points = FloatArray(hands.size * POINTS * 2)
        var n = 0
        for (hand in hands) {
            for (p in hand.take(POINTS)) {
                points[n++] = 1f - p.x()
                points[n++] = p.y()
            }
        }
        val a = aspect
        busy = false
        context.mainExecutor.execute { if (!closed) onHands(points, a) }
    }

    fun close() {
        closed = true
        runCatching { landmarker.close() }
    }

    companion object {
        private const val TAG = "NODE"
        private const val MODEL = "hand_landmarker.task"
        private const val JPEG_QUALITY = 90
        private const val JPEG_BUFFER_BYTES = 48 * 1024

        /** The wrist, then four joints up each finger from the thumb out: MediaPipe's own numbering. */
        const val POINTS = 21
    }
}
