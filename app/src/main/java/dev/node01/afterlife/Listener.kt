package dev.node01.afterlife

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * One utterance at a time, through the phone's own speech recognizer --
 * Google's, on this phone -- asked to work offline. It needs the offline
 * speech packs downloaded in the Google app; on Android 11 an app can only
 * prefer offline, not force it, so without the packs the recognizer may use
 * the network. NODE never sends audio anywhere itself.
 *
 * The microphone is open only between [listen] and the result: there is no
 * wake word and nothing listens in the background. Main thread only, as
 * SpeechRecognizer requires.
 */
class Listener(private val context: Context, private val events: Events) : RecognitionListener {

    interface Events {
        fun onListening()
        fun onHeard(partial: String)
        fun onFinal(text: String)

        /** [silence] is true when nothing was said, rather than something going wrong. */
        fun onFailed(reason: String, silence: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null

    /** The language of the current attempt. */
    private var primary = Lang.EN

    /**
     * Locales to try for [primary], in order: each has its own offline pack,
     * so English tries the one that last worked, the phone's own English,
     * then the common ones.
     */
    private var attempts = emptyList<String>()

    /**
     * Step 0 asks for the first locale together with the other language;
     * step n asks for locale n-1 alone. A missing pack moves to the next step.
     */
    private var step = 0

    private val tag get() = attempts[maxOf(0, step - 1)]

    /** Some locale on the way said its pack is missing, rather than unsupported. */
    private var packMissing = false

    /** The locale that last worked for each language, tried first next time. */
    private val working = HashMap<Lang, String>()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Listens in [primary], also allowing the other language where the recognizer can. */
    fun listen(primary: Lang) {
        this.primary = primary
        val system = Locale.getDefault().takeIf { it.language == Locale.forLanguageTag(primary.tag).language }
        // One locale, one attempt: each attempt makes the recognizer beep, and on
        // this phone a missing pack is the engine's own, not a wrong English variant.
        attempts = listOf(working[primary] ?: system?.toLanguageTag() ?: primary.tag)
        step = 1
        packMissing = false
        start()
    }

    private fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val tag = tag
        val other = if (primary == Lang.EN) Lang.NE else Lang.EN
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                // Not in the public API on Android 11, but Google's recognizer reads it.
                if (step == 0) putExtra(EXTRA_ADDITIONAL_LANGUAGES, arrayOf(other.tag))
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            },
        )
    }

    fun cancel() {
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) {
        working[primary] = tag
        events.onListening()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        first(partialResults)?.let(events::onHeard)
    }

    override fun onResults(results: Bundle?) = events.onFinal(first(results).orEmpty())

    override fun onError(error: Int) {
        if (error == ERROR_LANGUAGE_UNAVAILABLE) packMissing = true
        // A pack is missing -- the other language's, or this locale's: next step.
        if ((error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED) && step < attempts.size) {
            step++
            Log.d(TAG, "listen: no offline pack for that ($error), trying $tag alone")
            start()
            return
        }
        val silence = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
        val reason = when (error) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            ERROR_LANGUAGE_UNAVAILABLE ->
                "voice needs the offline ${if (primary == Lang.NE) "Nepali" else "English"} speech pack"
            // The last locale tried may be unsupported, but an earlier one only lacked its pack.
            ERROR_LANGUAGE_NOT_SUPPORTED -> if (packMissing) {
                "voice needs the offline ${if (primary == Lang.NE) "Nepali" else "English"} speech pack"
            } else {
                "${if (primary == Lang.NE) "Nepali" else "English"} speech not supported here"
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "voice needs microphone permission"
            SpeechRecognizer.ERROR_AUDIO -> "microphone error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "speech recognizer busy"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> "heard nothing"
            else -> "speech error $error"
        }
        Log.d(TAG, "listen: $reason ($error)")
        events.onFailed(reason, silence)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "NODE"
        const val EXTRA_ADDITIONAL_LANGUAGES = "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"

        /** English packs people commonly have, after the phone's own. */
        val ENGLISH = listOf("en-US", "en-GB", "en-IN")

        // Named in the API only from Android 12, but Google's recognizer sends them on 11 too.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
