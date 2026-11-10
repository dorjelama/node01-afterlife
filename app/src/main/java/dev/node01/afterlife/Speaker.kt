package dev.node01.afterlife

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * NODE's spoken voice: the phone's own text-to-speech. A voice installed on
 * the phone is always preferred; a language without one, such as Nepali here,
 * uses Google's online voice, and is shown but not spoken if there is none.
 *
 * One instance per session: create, then [shutdown]. Main thread only.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this, GOOGLE_TTS)
    private var ready = false
    private val voices = HashMap<Lang, Voice>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speech output unavailable ($status)")
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // A little higher and quicker than the stock voice: a small companion, not a newsreader.
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
        for (lang in Lang.values()) {
            voice(lang)?.let { voices[lang] = it }
        }
        ready = true
        // The engine loads a voice on first use, which held the first real line
        // back by seconds. Load it now, with a silent word.
        voices[Lang.EN]?.let { voice ->
            tts.voice = voice
            tts.speak(".", TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }, WARM_UP)
        }
        Log.d(TAG, "voices: " + Lang.values().joinToString { lang ->
            voices[lang]?.let { "$lang=${it.name}${if (it.isNetworkConnectionRequired) " (online)" else ""}" } ?: "$lang=none"
        })
    }

    /** False when no voice for [lang] is installed: the reply is then shown, not spoken. */
    fun canSpeak(lang: Lang) = ready && lang in voices

    /** Something is being said right now; small talk waits rather than cut it off. */
    val busy: Boolean get() = ready && tts.isSpeaking

    /** Says [text], cutting off anything being said -- or, if [after], once it has finished. */
    fun say(text: String, lang: Lang, after: Boolean = false) {
        val voice = voices[lang] ?: return
        if (!ready) return
        tts.voice = voice
        tts.speak(text, if (after) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }

    /**
     * The best voice for [lang]: one installed on the phone if there is one,
     * otherwise one of Google's online voices, which sends the words to
     * Google to be read out.
     */
    private fun voice(lang: Lang): Voice? {
        val locale = Locale.forLanguageTag(lang.tag)
        return runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { v -> v.locale.language == locale.language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features }
            .maxWithOrNull(
                compareBy<Voice>({ !it.isNetworkConnectionRequired }, { it.locale.country == locale.country }, { it.quality }),
            )
    }

    private companion object {
        const val TAG = "NODE"
        const val GOOGLE_TTS = "com.google.android.tts"
        const val UTTERANCE = "node"
        const val WARM_UP = "warm-up"
        const val PITCH = 1.25f
        const val RATE = 1.05f
    }
}
