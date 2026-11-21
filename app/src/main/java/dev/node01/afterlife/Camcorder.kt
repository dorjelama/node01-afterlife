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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * The rear camera and the microphone, each encoded twice: H.264 and AAC into
 * [recorder] for the loop clips, JPEG and raw PCM into the hubs for live
 * viewers. Both encoders are hardware; only the live JPEGs cost CPU, and they
 * are made only while somebody is watching.
 *
 * Camera callbacks run on the main thread -- the thread that calls [start] and
 * [stop] -- so camera state needs no locking. Each encoder is drained on its
 * own thread, and live frames are compressed on a third.
 *
 * One instance per session: [start], then [stop], then discard.
 */
class Camcorder(
    private val context: Context,
    private val recorder: LoopRecorder,
    private val jpegHub: Hub,
    private val pcmHub: Hub,
    private val onError: (String) -> Unit,
) {
    @Volatile
    private var running = false

    private var video: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var aac: MediaCodec? = null
    private var mic: AudioRecord? = null
    private var liveThread: HandlerThread? = null
    private var live: ImageReader? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val workers = mutableListOf<Thread>()

    /** Only touched on [liveThread]. */
    private var lastLiveMs = 0L

    /**
     * Throws if any part fails to come up. Call [stop] afterwards either way;
     * it releases whatever did start. The caller has checked CAMERA and
     * RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        running = true

        val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        this.video = video
        video.configure(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                // Clips can only be cut on a keyframe, so this is the cut granularity.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        val encoderSurface = video.createInputSurface()
        this.encoderSurface = encoderSurface
        video.start()

        val aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        this.aac = aac
        aac.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        aac.start()

        // CAMCORDER picks the microphone that faces the same way as the camera.
        val mic = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                MIC_BUFFER_BYTES,
            ),
        )
        this.mic = mic
        check(mic.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        mic.startRecording()

        val liveThread = HandlerThread("node-live").apply { start() }
        this.liveThread = liveThread
        val live = ImageReader.newInstance(LIVE_WIDTH, LIVE_HEIGHT, ImageFormat.YUV_420_888, 2)
        this.live = live
        live.setOnImageAvailableListener(::onLiveFrame, Handler(liveThread.looper))

        workers += thread(name = "node-video") { pumpVideo(video) }
        workers += thread(name = "node-audio") { pumpAudio(mic, aac) }

        val manager = context.getSystemService(CameraManager::class.java)
        val rear = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        // TEMPLATE_RECORD usually pins exposure to exactly 30 fps, which leaves a
        // dim room darker than it needs to be. Let it slow to 15 fps instead.
        val fpsRange = manager.getCameraCharacteristics(rear)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == FPS && it.lower >= MIN_FPS }
            ?.minByOrNull { it.lower }
        manager.openCamera(rear, context.mainExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                // stop() may have run while the camera was still opening.
                if (!running) return device.close()
                camera = device
                startCapture(device, listOf(encoderSurface, live.surface), fpsRange)
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

    /** Safe after a failed or partial [start]. Finalizes the clip in progress. */
    fun stop() {
        running = false
        session?.close()
        camera?.close()
        session = null
        camera = null

        // Both workers notice `running` within one chunk or one dequeue timeout.
        workers.forEach { it.join(JOIN_MS) }
        workers.clear()
        liveThread?.quitSafely()
        liveThread?.join(JOIN_MS)
        live?.close()

        video?.run {
            runCatching { stop() }
            release()
        }
        encoderSurface?.release()
        aac?.run {
            runCatching { stop() }
            release()
        }
        mic?.run {
            runCatching { stop() }
            release()
        }
        recorder.close()
    }

    private fun startCapture(device: CameraDevice, targets: List<Surface>, fpsRange: Range<Int>?) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (!running) return s.close()
                session = s
                try {
                    // TEMPLATE_RECORD: continuous video autofocus, steady exposure.
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        targets.forEach { addTarget(it) }
                        if (fpsRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    }.build()
                    s.setRepeatingRequest(request, null, null)
                } catch (e: Exception) {
                    fail("camera: ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = fail("camera refused the stream setup")
        }
        device.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                targets.map { OutputConfiguration(it) },
                context.mainExecutor,
                callback,
            ),
        )
    }

    private fun pumpVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) drain(codec, info, TIMEOUT_US, recorder::setVideoFormat, recorder::writeVideo)
        } catch (e: RuntimeException) {
            // Uncaught, any exception on any thread kills the whole app. Take
            // the retry path instead.
            fail("video encoder: ${e.message}")
        }
    }

    private fun pumpAudio(mic: AudioRecord, codec: MediaCodec) {
        val pcm = ByteArray(CHUNK_BYTES)
        val info = MediaCodec.BufferInfo()
        val stamp = AudioTimestamp()
        var framesRead = 0L
        var lastPtsUs = 0L
        try {
            while (running) {
                val n = mic.read(pcm, 0, pcm.size)
                // A dead microphone returns its error forever; recording goes on silent.
                if (n < 0) return fail("microphone error $n")
                if (n == 0) continue
                val frames = n / 2

                // When this chunk's first sample was captured, on the monotonic
                // clock the camera stamps encoder frames with. Asking the
                // microphone, rather than reading the time now, stays right when
                // a stall (a clip being finalized) makes reads arrive in a burst.
                val ptsNs = if (mic.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    stamp.nanoTime + (framesRead - stamp.framePosition) * 1_000_000_000L / SAMPLE_RATE
                } else {
                    System.nanoTime() - frames * 1_000_000_000L / SAMPLE_RATE
                }
                framesRead += frames
                // The muxer rejects a track whose timestamps go backwards.
                val ptsUs = maxOf(ptsNs / 1_000, lastPtsUs + 1)
                lastPtsUs = ptsUs

                pcmHub.publish(pcm.copyOf(n))

                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                // No free buffer means the encoder is backed up: drop 20 ms of
                // sound rather than stall the microphone.
                if (i >= 0) {
                    codec.getInputBuffer(i)!!.put(pcm, 0, n)
                    codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                }
                drain(codec, info, 0, recorder::setAudioFormat, recorder::writeAudio)
            }
        } catch (e: RuntimeException) {
            fail("audio encoder: ${e.message}")
        }
    }

    /** Hands every finished sample in [codec] to [write], returning once none is ready. */
    private fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long,
        setFormat: (MediaFormat) -> Unit,
        write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> setFormat(codec.outputFormat)
                i >= 0 -> {
                    // Codec config (SPS/PPS, AudioSpecificConfig) reaches the
                    // muxer through the output format instead.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        write(codec.getOutputBuffer(i)!!, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                }
            }
        }
    }

    /** Runs on [liveThread]. Skips all the work while nobody is watching. */
    private fun onLiveFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val now = SystemClock.elapsedRealtime()
        if (!jpegHub.hasSubscribers || now - lastLiveMs < LIVE_INTERVAL_MS) return image.close()
        lastLiveMs = now
        // Copy out and hand the buffer straight back before compressing: a held
        // buffer can stall the camera's whole request, recording included.
        val w = image.width
        val h = image.height
        try {
            val yuv = image.use { nv21(it) }
            val jpeg = ByteArrayOutputStream(JPEG_BUFFER_BYTES)
            YuvImage(yuv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), LIVE_QUALITY, jpeg)
            jpegHub.publish(jpeg.toByteArray())
        } catch (e: RuntimeException) {
            // A bad live frame costs that frame, never the recording.
            Log.w(TAG, "live frame dropped: ${e.message}")
        }
    }

    /**
     * Reported on the main thread, and re-checked there -- where [stop] runs --
     * so a failure racing a deliberate stop is dropped instead of reported.
     */
    private fun fail(reason: String) {
        Log.e(TAG, reason)
        context.mainExecutor.execute { if (running) onError(reason) }
    }

    private companion object {
        const val TAG = "NODE"

        // shortcut: fixed sizes, all offered by this phone's rear camera; pick
        // them from StreamConfigurationMap if NODE is ever ported.
        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val VIDEO_BITRATE = 8_000_000
        const val FPS = 30
        const val MIN_FPS = 15

        const val LIVE_WIDTH = 1280
        const val LIVE_HEIGHT = 720
        // ~15 fps is plenty for watching: every second frame at 30 fps, every
        // frame at 15. Not 66 ms -- 15 fps frames land a hair early and get skipped.
        const val LIVE_INTERVAL_MS = 50L
        const val LIVE_QUALITY = 70
        const val JPEG_BUFFER_BYTES = 128 * 1024

        const val SAMPLE_RATE = 48_000
        const val CHUNK_BYTES = SAMPLE_RATE / 50 * 2 // 20 ms of mono 16-bit PCM
        // 500 ms, so the muxer stall while a clip is finalized never overruns it.
        const val MIC_BUFFER_BYTES = SAMPLE_RATE / 2 * 2
        const val AUDIO_BITRATE = 128_000

        const val TIMEOUT_US = 10_000L
        const val JOIN_MS = 1_000L
    }
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
