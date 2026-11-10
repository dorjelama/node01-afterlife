package dev.node01.afterlife

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * One utterance at a time, through the phone's own speech recognizer --
 * Google's, on this phone.
 *
 * Offline first: on-device recognition needs the Google app's offline model
 * for the language. When that is missing NODE falls back to Google's online
 * recognition, which sends the sentence to Google to be turned into text, and
 * says so on screen. After a miss it goes straight online for
 * [OFFLINE_RETRY_MS] -- every attempt makes the recognizer beep -- then tries
 * offline again, in case the model has arrived.
 *
 * The microphone is open only between [listen] and the result: there is no
 * wake word and nothing listens in the background. Main thread only, as
 * SpeechRecognizer requires.
 */
class Listener(private val context: Context, private val events: Events) : RecognitionListener {

    interface Events {
        /** [online] is true when the sentence will go to Google to be recognized. */
        fun onListening(online: Boolean)
        fun onHeard(partial: String)
        fun onFinal(text: String)

        /**
         * [silence] is true when nothing was said, rather than something going
         * wrong; [busy] when the recognizer was still finishing a previous
         * request, which is worth retrying in a moment.
         */
        fun onFailed(reason: String, silence: Boolean, busy: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    private var primary = Lang.EN
    private var tag = primary.tag
    private var online = false
    private var quiet = false

    /** Loudest the microphone got this session, for telling a quiet voice from a misheard one. */
    private var peakDb = -100f

    /** Until then, skip straight to online: offline failed a moment ago. */
    private var offlineRetryAtMs = 0L

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Streams this class muted to hide the recognizer's beep, to unmute again. */
    private val muted = mutableListOf<Int>()
    private val unmute = Runnable { restoreBeep() }

    /**
     * The beep is a notification sound, and this phone will not let an app mute
     * the notification stream outright (that is a Do Not Disturb change). It
     * will let it turn the volume down: [hush] does that while hands-free
     * listening runs, and [unhush] puts the volume back.
     */
    private var hushedFrom = -1

    fun hush() {
        if (hushedFrom >= 0) return
        runCatching {
            val was = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            if (was > 0) {
                audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                hushedFrom = was
            }
        }.onFailure { Log.d(TAG, "listen: cannot turn the notification volume down: ${it.message}") }
    }

    fun unhush() {
        if (hushedFrom < 0) return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, hushedFrom, 0) }
        hushedFrom = -1
    }

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens in [primary]: the phone's own variant for English, so en-GB here.
     * [quiet] hides the recognizer's start beep, for listening that restarts
     * every few seconds in the background.
     */
    fun listen(primary: Lang, quiet: Boolean = false) {
        this.primary = primary
        this.quiet = quiet
        val system = Locale.getDefault().takeIf { it.language == Locale.forLanguageTag(primary.tag).language }
        tag = system?.toLanguageTag() ?: primary.tag
        online = SystemClock.elapsedRealtime() < offlineRetryAtMs
        start()
    }

    private fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        if (quiet) muteBeep()
        peakDb = -100f
        val other = if (primary == Lang.EN) Lang.NE else Lang.EN
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
                // Online, Google can hear either language in one go. Not in the public
                // API on Android 11, but its recognizer reads it. Offline it would need
                // a second model, and a missing one fails the whole request.
                if (online) putExtra(EXTRA_ADDITIONAL_LANGUAGES, arrayOf(other.tag))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            },
        )
    }

    fun cancel() {
        recognizer?.cancel()
        restoreBeep()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        restoreBeep()
        unhush()
    }

    /**
     * The recognizer beeps as it starts, on one of these streams. Mute them for
     * that moment only -- never one the user had muted already -- with a
     * fallback in case the recognizer never gets as far as listening.
     */
    private fun muteBeep() {
        for (stream in BEEP_STREAMS) {
            runCatching {
                if (!audio.isStreamMute(stream)) {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    muted += stream
                }
            }
        }
        main.removeCallbacks(unmute)
        main.postDelayed(unmute, BEEP_FALLBACK_MS)
    }

    private fun restoreBeep() {
        main.removeCallbacks(unmute)
        for (stream in muted) runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        muted.clear()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        // The beep plays as listening begins: unmute just after it.
        if (muted.isNotEmpty()) {
            main.removeCallbacks(unmute)
            main.postDelayed(unmute, BEEP_MS)
        }
        // Offline works now: no need to skip it next time.
        if (!online) offlineRetryAtMs = 0L
        Log.d(TAG, "listen: $tag ${if (online) "online" else "offline"}")
        events.onListening(online)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        first(partialResults)?.let(events::onHeard)
    }

    override fun onResults(results: Bundle?) {
        Log.d(TAG, "listen: heard words (peak %.0f dB)".format(peakDb))
        events.onFinal(first(results).orEmpty())
    }

    override fun onError(error: Int) {
        restoreBeep()
        val noModel = error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED
        if (!online && noModel) {
            offlineRetryAtMs = SystemClock.elapsedRealtime() + OFFLINE_RETRY_MS
            online = true
            Log.d(TAG, "listen: no offline model for $tag ($error), listening online")
            // Asked again straight from this callback, the recognizer is still busy
            // with the request it just refused.
            main.postDelayed({ start() }, RETRY_DELAY_MS)
            return
        }
        val language = if (primary == Lang.NE) "Nepali" else "English"
        // ERROR_CLIENT is a second tap cancelling the listen: not worth reporting either.
        val silence = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_CLIENT
        val reason = when (error) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "voice needs the internet, or the offline $language speech pack"
            ERROR_LANGUAGE_UNAVAILABLE, ERROR_LANGUAGE_NOT_SUPPORTED -> "$language speech is not available"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "voice needs microphone permission"
            SpeechRecognizer.ERROR_AUDIO -> "microphone error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "speech recognizer busy"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> "heard nothing"
            SpeechRecognizer.ERROR_CLIENT -> "cancelled"
            else -> "speech error $error"
        }
        Log.d(TAG, "listen: $reason ($error, ${if (online) "online" else "offline"}, peak %.0f dB)".format(peakDb))
        events.onFailed(reason, silence, busy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) {
        if (rmsdB > peakDb) peakDb = rmsdB
    }
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "NODE"
        const val EXTRA_ADDITIONAL_LANGUAGES = "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"

        /** After offline fails, how long to go straight online before trying offline again. */
        const val OFFLINE_RETRY_MS = 10 * 60_000L

        /** Before asking again online, once offline has refused. */
        const val RETRY_DELAY_MS = 250L

        /** Where the recognizer's start beep may play. */
        val BEEP_STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)

        /** How long after listening starts the beep is over. */
        const val BEEP_MS = 500L

        /** Unmute anyway after this, if listening never starts. */
        const val BEEP_FALLBACK_MS = 2_500L

        // Named in the API only from Android 12, but Google's recognizer sends them on 11 too.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
