package com.anvex.core.android.runtime.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * all-MiniLM-L6-v2 embeddings via ONNX Runtime (P7/F6). The model file
 * is downloaded on first enable (never bundled); [create] returns null
 * when the model or the tokenizer vocab is missing, and callers surface
 * that as "RAG unavailable" rather than crashing.
 *
 * Inference: single-sequence runs on the CPU execution provider with a
 * bounded thread count; mean pooling over the attention mask, then L2
 * normalization (so vec0's L2 distance ranks like cosine).
 */
class OnnxEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: WordPieceTokenizer,
) : AutoCloseable {

    /** Embeds one text; null on any inference failure. */
    fun embed(text: String): FloatArray? = runCatching {
        val (ids, mask) = tokenizer.encode(text)
        val seq = ids.size
        val inputIds = arrayOf(LongArray(seq) { ids[it].toLong() })
        val attention = arrayOf(LongArray(seq) { mask[it].toLong() })
        val tokenTypes = arrayOf(LongArray(seq))
        val inputs = HashMap<String, OnnxTensor>(3)
        var hidden: Array<Array<FloatArray>>? = null
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, inputIds)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, attention)
            inputs["token_type_ids"] = OnnxTensor.createTensor(env, tokenTypes)
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                hidden = result[0].value as Array<Array<FloatArray>>
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
        val states = hidden?.get(0) ?: return@runCatching null
        val pooled = FloatArray(VecStore.EMBEDDING_DIM)
        var used = 0
        for (i in states.indices) {
            if (i < mask.size && mask[i] == 0) continue
            val row = states[i]
            if (row.size != VecStore.EMBEDDING_DIM) continue
            for (d in pooled.indices) pooled[d] += row[d]
            used++
        }
        if (used == 0) return@runCatching null
        for (d in pooled.indices) pooled[d] /= used
        VecStore.normalize(pooled)
    }.getOrNull()

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** Model + vocab handles; [modelFile] must exist (post-download). */
        fun create(modelFile: File, vocabLines: List<String>): OnnxEmbedder? = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            val session = env.createSession(modelFile.absolutePath, options)
            OnnxEmbedder(env, session, WordPieceTokenizer(vocabLines))
        }.getOrNull()
    }
}
