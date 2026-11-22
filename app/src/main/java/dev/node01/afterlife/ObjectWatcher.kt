package dev.node01.afterlife

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * Cups, bottles, phones and books in front of the front camera, found on the
 * phone by MediaPipe's object detector (EfficientDet-Lite0, trained on COCO),
 * for [Activities]. Nothing leaves the phone and no frame is kept. Frames come
 * from [Presence]; one arriving while the last is still being read is dropped.
 *
 * [onSeen] gets, on the main thread, what each frame held. Construct it only
 * while some activity is allowed, and [close] it when none is.
 */
class ObjectWatcher(private val context: Context, private val onSeen: (List<Activities.Seen>) -> Unit) {

    @Volatile
    private var busy = false

    @Volatile
    private var closed = false

    private val detector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(MIN_SCORE)
            .setCategoryAllowlist(Doing.entries.flatMap { it.labels })
            .setResultListener { result, image -> onResult(result, image.height) }
            .setErrorListener { e ->
                busy = false
                Log.w(TAG, "objects: ${e.message}")
            }
            .build(),
    )

    /** False while a frame is still being read: the caller can skip copying the next one. */
    val ready: Boolean get() = !busy && !closed

    /** On the camera thread: an upright NV21 frame, [w] by [h], taken at [timeMs]. */
    fun offer(nv21: ByteArray, w: Int, h: Int, timeMs: Long) {
        if (!ready) return
        busy = true
        try {
            detector.detectAsync(BitmapImageBuilder(HandTracker.bitmap(nv21, w, h)).build(), timeMs)
        } catch (e: RuntimeException) {
            // Closed under us, or a bad frame: it costs that frame only.
            busy = false
        }
    }

    private fun onResult(result: ObjectDetectorResult, height: Int) {
        val seen = result.detections().mapNotNull { d ->
            d.categories().firstOrNull()?.let { Activities.Seen(it.categoryName(), d.boundingBox().centerY() / height) }
        }
        busy = false
        context.mainExecutor.execute { if (!closed) onSeen(seen) }
    }

    fun close() {
        closed = true
        runCatching { detector.close() }
    }

    companion object {
        private const val TAG = "NODE"
        private const val MODEL = "efficientdet_lite0.tflite"
        private const val MAX_RESULTS = 5
        private const val MIN_SCORE = 0.4f
    }
}
