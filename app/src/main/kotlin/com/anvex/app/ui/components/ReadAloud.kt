package com.anvex.app.ui.components

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Read-aloud for assistant replies (Phase 3.5). One utterance at a time; the id of
 * the message being spoken is exposed so the UI can show a Stop button on that bubble.
 */
class ReadAloud(context: Context) {

    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    private var ready = false
    private var queued: Pair<String, String>? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.getDefault()
                queued?.let { (id, text) -> queued = null; speak(id, text) }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (_speakingId.value == utteranceId) _speakingId.value = null
            }
            override fun onError(utteranceId: String?) {
                if (_speakingId.value == utteranceId) _speakingId.value = null
            }
        })
    }

    fun speak(id: String, text: String) {
        if (!ready) { queued = id to text; return }
        // 2026-11-03 audit: the engine language was pinned to the DEVICE
        // locale, so Persian replies were voiced by an English voice
        // (wrong phonemes, often unintelligible). Pick the utterance
        // locale from the text being spoken; if that voice is missing,
        // keep the previously set (device default) language.
        val locale = localeForText(text)
        if (locale != null && tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
            // Language switched for this utterance.
        }
        tts.speak(plainForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, id)
        _speakingId.value = id
    }

    /**
     * Persian/Arabic-script text is spoken with a Persian voice; anything
     * else returns null (keep the current engine language). Detection is
     * by Unicode block, so mixed replies follow their dominant script's
     * first strong run.
     */
    private fun localeForText(text: String): Locale? {
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.ARABIC ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B ||
                block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
            ) {
                return Locale.forLanguageTag("fa")
            }
            if (ch.isLetter()) return null
        }
        return null
    }

    fun stop() {
        tts.stop()
        _speakingId.value = null
    }

    fun shutdown() {
        queued = null
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /** Drops Markdown syntax so the engine does not read asterisks and backticks aloud. */
        internal fun plainForSpeech(markdown: String): String =
            markdown
                .replace(Regex("```[\\s\\S]*?```"), " code block. ")
                .replace(Regex("[*#`_>|]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
