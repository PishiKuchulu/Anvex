package com.anvex.core.android.runtime.rag

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Lifecycle of the downloadable embedding model (P7/F6). */
sealed interface RagModelState {
    data object NotDownloaded : RagModelState
    data class Downloading(val percent: Int) : RagModelState
    data object Ready : RagModelState
    data class Failed(val message: String) : RagModelState
}

/**
 * Owns the all-MiniLM-L6-v2 qint8_arm64 ONNX model (~23 MB): downloaded
 * on first enable from the pinned Hugging Face URL, SHA-256 verified
 * before use, never bundled in the APK (plan §6). The vocab ships as an
 * asset (231 KB) and is read from there.
 */
class RagModelManager(private val context: Context) {

    private val ragDir: File = File(context.filesDir, "rag").apply { mkdirs() }
    val modelFile: File = File(ragDir, MODEL_NAME)
    private val store: VecStore = VecStore(File(ragDir, "vectors.db").absolutePath)

    private val _state = MutableStateFlow<RagModelState>(
        if (modelFile.exists()) RagModelState.Ready else RagModelState.NotDownloaded,
    )
    val state: StateFlow<RagModelState> = _state

    fun vecStore(): VecStore = store

    /** Reads the bundled WordPiece vocab (assets/rag/vocab.txt). */
    fun vocabLines(): List<String> = runCatching {
        context.assets.open("rag/vocab.txt").bufferedReader().readLines()
    }.getOrDefault(emptyList())

    fun createEmbedder(): OnnxEmbedder? {
        if (!modelFile.exists()) return null
        val vocab = vocabLines()
        if (vocab.isEmpty()) return null
        return OnnxEmbedder.create(modelFile, vocab)
    }

    /** Downloads (or re-verifies) the model. True when Ready afterwards. */
    suspend fun ensureModel(): Boolean = withContext(Dispatchers.IO) {
        if (modelFile.exists() && sha256(modelFile) == MODEL_SHA256) {
            _state.value = RagModelState.Ready
            return@withContext true
        }
        _state.value = RagModelState.Downloading(0)
        val part = File(ragDir, "$MODEL_NAME.part")
        try {
            val request = Request.Builder().url(MODEL_URL).build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = RagModelState.Failed("HTTP ${response.code}")
                    return@withContext false
                }
                val body = response.body
                val total = body?.contentLength() ?: -1L
                var read = 0L
                body?.byteStream()?.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                _state.value = RagModelState.Downloading(
                                    ((read * 100) / total).toInt().coerceIn(0, 99),
                                )
                            }
                        }
                    }
                }
            }
            if (sha256(part) != MODEL_SHA256) {
                part.delete()
                _state.value = RagModelState.Failed("Model checksum mismatch")
                return@withContext false
            }
            part.renameTo(modelFile)
            _state.value = RagModelState.Ready
            true
        } catch (t: Throwable) {
            part.delete()
            _state.value = RagModelState.Failed(t.message ?: "Download failed")
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_NAME = "minilm-qint8-arm64.onnx"
        const val MODEL_URL =
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model_qint8_arm64.onnx"
        const val MODEL_SHA256 =
            "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474"
    }
}
