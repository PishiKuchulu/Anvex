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
        tts.speak(plainForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, id)
        _speakingId.value = id
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
