package com.anvex.core.android.runtime.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A chunk ready to store: [embedding] is L2-normalized float32[384]. */
data class RagChunk(
    val chunkId: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is RagChunk && chunkId == other.chunkId
    override fun hashCode(): Int = chunkId.hashCode()
}

/** One KNN hit from [VecStore.query]. [distance] is L2 over unit vectors. */
data class RagHit(
    val chunkId: String,
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val distance: Float,
    val text: String,
)

/**
 * Typed wrapper over the libanvex-vec JNI protocol (P7/F6). All calls are
 * blocking SQLite work — callers run them on an IO dispatcher. When the
 * native library is unavailable every call degrades to empty/false.
 */
class VecStore(private val dbPath: String) {

    val available: Boolean get() = VecBridge.loaded

    fun version(): String? =
        if (!available) null else runCatching { VecBridge.nativeVecVersion() }.getOrNull()

    fun fileHash(project: String, path: String): String? =
        if (!available) null
        else runCatching { VecBridge.nativeFileHash(dbPath, project, path) }.getOrNull()

    /** Atomically replaces the stored chunks of one file. */
    fun replaceFile(
        project: String,
        path: String,
        fileHash: String,
        chunks: List<RagChunk>,
    ): Boolean {
        if (!available) return false
        val packed = packEmbeddings(chunks.map { it.embedding })
        val stored = runCatching {
            VecBridge.nativeReplaceFile(
                dbPath, project, path, fileHash,
                chunks.map { it.chunkId }.toTypedArray(),
                chunks.map { it.startLine }.toIntArray(),
                chunks.map { it.endLine }.toIntArray(),
                chunks.map { it.text }.toTypedArray(),
                packed,
            )
        }.getOrDefault(-1)
        return stored == chunks.size
    }

    fun query(project: String, queryEmbedding: FloatArray, k: Int): List<RagHit> {
        if (!available || queryEmbedding.size != EMBEDDING_DIM) return emptyList()
        val raw = runCatching {
            VecBridge.nativeQuery(dbPath, project, packEmbeddings(listOf(queryEmbedding)), k)
        }.getOrNull() ?: return emptyList()
        return parseHits(raw)
    }

    fun count(project: String): Int =
        if (!available) 0 else runCatching { VecBridge.nativeCount(dbPath, project) }.getOrDefault(0)

    fun clearProject(project: String) {
        if (!available) return
        runCatching { VecBridge.nativeClearProject(dbPath, project) }
    }

    companion object {
        const val EMBEDDING_DIM = 384

        /** Packs float vectors as concatenated little-endian float32. */
        fun packEmbeddings(vectors: List<FloatArray>): ByteArray {
            val buffer = ByteBuffer.allocate(vectors.size * EMBEDDING_DIM * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (vector in vectors) {
                require(vector.size == EMBEDDING_DIM) { "embedding must be $EMBEDDING_DIM floats" }
                for (value in vector) buffer.putFloat(value)
            }
            return buffer.array()
        }

        /** Parses the native line protocol (see vec_bridge.cpp). */
        fun parseHits(raw: String): List<RagHit> {
            if (raw.isBlank()) return emptyList()
            val hits = ArrayList<RagHit>()
            for (line in raw.split('\n')) {
                val fields = line.split('\t')
                if (fields.size < 6) continue
                hits += RagHit(
                    chunkId = unescape(fields[0]),
                    path = unescape(fields[1]),
                    startLine = fields[2].toIntOrNull() ?: 0,
                    endLine = fields[3].toIntOrNull() ?: 0,
                    distance = fields[4].toFloatOrNull() ?: Float.MAX_VALUE,
                    text = unescape(fields.subList(5, fields.size).joinToString("\t")),
                )
            }
            return hits
        }

        fun unescape(value: String): String {
            if (!value.contains('\\')) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        'n' -> { out.append('\n'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        'r' -> { out.append('\r'); i += 2 }
                        '\\' -> { out.append('\\'); i += 2 }
                        else -> { out.append(c); i++ }
                    }
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }

        /** L2-normalizes [vector] in place-copy; zero vectors pass through. */
        fun normalize(vector: FloatArray): FloatArray {
            var sum = 0.0
            for (v in vector) sum += v.toDouble() * v.toDouble()
            val norm = kotlin.math.sqrt(sum)
            if (norm <= 0.0) return vector.copyOf()
            return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
        }
    }
}
