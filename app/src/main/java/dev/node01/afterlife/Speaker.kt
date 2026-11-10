package dev.node01.afterlife

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * NODE's spoken voice: the phone's own text-to-speech, using only voices
 * installed on the phone. A language with no installed voice is shown on the
 * screen but not spoken, rather than sending the words to a server to be
 * read out.
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
        for (lang in Lang.values()) {
            offlineVoice(lang)?.let { voices[lang] = it }
        }
        ready = true
        Log.d(TAG, "voices on the phone: " + Lang.values().joinToString { "$it=${voices[it]?.name ?: "none"}" })
    }

    /** False when no voice for [lang] is installed: the reply is then shown, not spoken. */
    fun canSpeak(lang: Lang) = ready && lang in voices

    fun say(text: String, lang: Lang) {
        val voice = voices[lang] ?: return
        if (!ready) return
        tts.voice = voice
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }

    /** The best installed voice for [lang] that needs no network. */
    private fun offlineVoice(lang: Lang): Voice? {
        val language = Locale.forLanguageTag(lang.tag).language
        return runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { v ->
                v.locale.language == language &&
                    !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
            }
            .maxWithOrNull(compareBy<Voice>({ it.locale.country == Locale.forLanguageTag(lang.tag).country }, { it.quality }))
    }

    private companion object {
        const val TAG = "NODE"
        const val GOOGLE_TTS = "com.google.android.tts"
        const val UTTERANCE = "node"
    }
}
