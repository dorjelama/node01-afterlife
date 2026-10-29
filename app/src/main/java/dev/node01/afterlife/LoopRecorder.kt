package dev.node01.afterlife

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Loop recording. The encoded video and audio are cut into fixed-length MP4
 * clips in [dir], and the oldest clips are deleted whenever the folder passes
 * [CAP_BYTES] or the phone's free space drops under [MIN_FREE_BYTES].
 *
 * A clip is written as `NODE_….mp4.part` and renamed to `.mp4` only once it is
 * finalized. A clip cut short by a crash has no index and will not play, so it
 * stays a hidden `.part` -- still counted against the cap, and still there for
 * a recovery tool -- until it is pruned with the rest.
 *
 * Fed from two threads -- the video drain and the audio pump -- so every entry
 * point is synchronized.
 */
class LoopRecorder(private val dir: File) {

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var muxer: MediaMuxer? = null
    private var part: File? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var clipStartUs = 0L
    private var lastAudioUs = 0L

    @Synchronized
    fun setVideoFormat(format: MediaFormat) {
        videoFormat = format
    }

    @Synchronized
    fun setAudioFormat(format: MediaFormat) {
        audioFormat = format
    }

    /** Clips can only start on a keyframe, so rotation happens here. */
    @Synchronized
    fun writeVideo(sample: ByteBuffer, info: MediaCodec.BufferInfo) {
        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (keyFrame && (muxer == null || info.presentationTimeUs - clipStartUs >= CLIP_US)) {
            closeClip()
            prune()
            openClip(info.presentationTimeUs)
        }
        write(videoTrack, sample, info)
    }

    @Synchronized
    fun writeAudio(sample: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Audio from before this clip's first frame belonged to the last one,
        // which costs a few tens of milliseconds of sound at each cut. The
        // muxer also rejects a track whose timestamps go backwards.
        if (info.presentationTimeUs < clipStartUs || info.presentationTimeUs <= lastAudioUs) return
        lastAudioUs = info.presentationTimeUs
        write(audioTrack, sample, info)
    }

    /** Finalizes the clip in progress. The next session's encoders bring fresh formats. */
    @Synchronized
    fun close() {
        closeClip()
        videoFormat = null
        audioFormat = null
    }

    /** Finished clips, newest first. */
    fun finishedClips(): List<File> =
        dir.listFiles { f -> CLIP_NAME.matches(f.name) }
            .orEmpty()
            .sortedByDescending { it.name }

    /** A finished clip by name, or null. The name check is what stops path traversal. */
    fun finishedClip(name: String): File? =
        if (CLIP_NAME.matches(name)) File(dir, name).takeIf { it.isFile } else null

    private fun write(track: Int, sample: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        try {
            m.writeSampleData(track, sample, info)
        } catch (e: IllegalStateException) {
            // Drop this clip rather than the whole recording; the next
            // keyframe starts a new one.
            Log.e(TAG, "clip write failed: ${e.message}")
            closeClip()
        }
    }

    private fun openClip(startUs: Long) {
        val video = videoFormat ?: return
        val audio = audioFormat ?: return
        // Never fill the phone: if pruning could not free enough, do not record.
        if (dir.usableSpace < MIN_FREE_BYTES) {
            Log.w(TAG, "not recording: ${dir.usableSpace / 1_000_000} MB free")
            return
        }
        val name = CLIP_STAMP.format(LocalDateTime.now())
        val file = File(dir, name + PART)
        // A restart within the same second must not truncate the last clip.
        if (file.exists() || File(dir, name).exists()) return
        var m: MediaMuxer? = null
        try {
            m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrack = m.addTrack(video)
            audioTrack = m.addTrack(audio)
            m.start()
        } catch (e: Exception) {
            // Storage gone, or a format the muxer refuses. Retried on the next keyframe.
            Log.e(TAG, "cannot open $name: ${e.message}")
            m?.release()
            file.delete()
            return
        }
        muxer = m
        part = file
        clipStartUs = startUs
        lastAudioUs = 0L
        Log.i(TAG, "clip $name")
    }

    private fun closeClip() {
        val m = muxer ?: return
        val file = part
        muxer = null
        part = null
        val finalized = try {
            m.stop()
            true
        } catch (e: IllegalStateException) {
            // Throws when nothing was written, or the disk filled up.
            Log.w(TAG, "clip stop failed: ${e.message}")
            false
        }
        m.release()
        if (finalized && file != null && !file.renameTo(File(dir, file.name.removeSuffix(PART)))) {
            Log.w(TAG, "cannot finalize ${file.name}")
        }
    }

    private fun prune() {
        val clips = dir.listFiles { f -> ANY_CLIP.matches(f.name) }
            .orEmpty()
            .map { it.name to it.length() }
        for (name in clipsToDelete(clips, CAP_BYTES, dir.usableSpace, MIN_FREE_BYTES)) {
            if (File(dir, name).delete()) Log.i(TAG, "pruned $name") else Log.w(TAG, "cannot delete $name")
        }
    }

    companion object {
        /** The only files NODE will ever list or serve: finished clips. */
        val CLIP_NAME = Regex("""NODE_\d{8}_\d{6}\.mp4""")

        /** The only files NODE will ever delete: finished clips and unfinished `.part`s. */
        private val ANY_CLIP = Regex("""NODE_\d{8}_\d{6}\.mp4(\.part)?""")
        private const val PART = ".part"

        // Local time, so names sort chronologically and read naturally.
        private val CLIP_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("'NODE_'yyyyMMdd_HHmmss'.mp4'", Locale.US)

        /** Clip length. At ~1 MB/s each clip is about 300 MB. */
        const val CLIP_US = 5L * 60L * 1_000_000L

        /** About 2 hours of footage at 8 Mbps video + 128 kbps audio. */
        const val CAP_BYTES = 8_000_000_000L

        /** Free space the phone always keeps, whatever the cap says. */
        const val MIN_FREE_BYTES = 2_000_000_000L

        private const val TAG = "NODE"
    }
}

/**
 * Which clips to delete, oldest first, so the folder fits [capBytes] and the
 * phone keeps at least [minFreeBytes] free. Clip names sort chronologically.
 * Pure, so the one piece of NODE that deletes files is unit-tested.
 */
internal fun clipsToDelete(
    clips: List<Pair<String, Long>>,
    capBytes: Long,
    freeBytes: Long,
    minFreeBytes: Long,
): List<String> {
    val doomed = mutableListOf<String>()
    var total = clips.sumOf { it.second }
    var free = freeBytes
    for ((name, size) in clips.sortedBy { it.first }) {
        if (total <= capBytes && free >= minFreeBytes) break
        doomed += name
        total -= size
        free += size
    }
    return doomed
}
