package dev.node01.afterlife

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * NODE's sounds, synthesized once into static tracks: no sound files in the
 * repo and no permission needed. Rising for "needs you", falling and softer
 * for "done", a quick high blip when the microphone opens. They play as
 * system sounds, so the phone's own system volume and silent mode apply.
 *
 * One instance per session: [start], then [stop], then discard.
 */
class Chirp {

    private var attention: AudioTrack? = null
    private var done: AudioTrack? = null
    private var listen: AudioTrack? = null
    private var tune: AudioTrack? = null

    fun start() {
        attention = track(tone(880.0, 0.09) + tone(1320.0, 0.13), ATTENTION_GAIN)
        done = track(tone(1046.5, 0.09) + tone(784.0, 0.16), DONE_GAIN)
        listen = track(tone(1318.5, 0.05) + tone(1760.0, 0.07), LISTEN_GAIN)
        // Eight notes of its own, since it has no voice to sing with.
        tune = track(
            SONG.fold(ShortArray(0)) { pcm, (hz, beats) -> pcm + tone(hz, beats * BEAT) },
            DONE_GAIN,
        )
    }

    fun stop() {
        attention?.release()
        done?.release()
        listen?.release()
        tune?.release()
        attention = null
        done = null
        listen = null
        tune = null
    }

    /** Claude needs you, or a note is due. */
    fun attention() = play(attention)

    fun done() = play(done)

    /** The microphone is open. */
    fun listen() = play(listen)

    /** Asked to sing: a few notes, which is as much singing as a dot-matrix face can manage. */
    fun sing() = play(tune)

    /** How long the whole tune lasts, so the caller knows when it has finished. */
    val songMs: Long get() = (SONG.sumOf { it.second } * BEAT * 1000).toLong()

    private fun play(track: AudioTrack?) {
        track ?: return
        try {
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "chirp: ${e.message}")
        }
    }

    /** Null if the audio system refuses: NODE is then just silent. */
    private fun track(pcm: ShortArray, gain: Float): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .apply {
                write(pcm, 0, pcm.size)
                setVolume(gain)
            }
    }.onFailure { Log.w(TAG, "chirp unavailable: ${it.message}") }.getOrNull()

    private companion object {
        const val TAG = "NODE"
        const val RATE = 44_100
        const val ATTENTION_GAIN = 0.5f
        const val DONE_GAIN = 0.3f
        const val LISTEN_GAIN = 0.25f

        /** A beat, in seconds: brisk enough that the whole thing is over in two. */
        const val BEAT = 0.18

        /** C D E G E C, up and back: cheerful, and nobody's copyright. */
        val SONG = listOf(
            523.25 to 1.0, 587.33 to 1.0, 659.25 to 1.0, 783.99 to 1.5,
            659.25 to 1.0, 783.99 to 1.0, 1046.5 to 2.0,
        )

        /** A sine with short fades at both ends, so it does not click. */
        fun tone(hz: Double, seconds: Double): ShortArray {
            val n = (RATE * seconds).toInt()
            val fade = (RATE * 0.01).toInt()
            return ShortArray(n) { i ->
                val envelope = min(1.0, min(i, n - 1 - i) / fade.toDouble())
                (sin(2 * PI * hz * i / RATE) * envelope * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}
