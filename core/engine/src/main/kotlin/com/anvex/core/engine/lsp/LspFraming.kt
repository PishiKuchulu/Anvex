package com.anvex.core.engine.lsp

/**
 * LSP base-protocol framing (P9/F2c): messages are JSON payloads
 * preceded by `Content-Length: N` headers terminated by CRLFCRLF.
 * [encode] builds one frame; [Decoder] incrementally splits a byte
 * stream back into JSON payloads, tolerating partial frames and
 * several frames per chunk. Pure and JVM-tested.
 */
object LspFraming {

    fun encode(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        return header + body
    }

    class Decoder {
        private var buffer = ByteArray(0)

        /** Feeds bytes; returns every complete JSON payload now available. */
        fun feed(chunk: ByteArray): List<String> {
            if (chunk.isNotEmpty()) buffer += chunk
            val out = ArrayList<String>()
            while (true) {
                val headerEnd = indexOfHeaderEnd(buffer)
                if (headerEnd < 0) break
                val header = String(buffer, 0, headerEnd, Charsets.US_ASCII)
                val length = contentLength(header) ?: break
                val bodyStart = headerEnd + 4
                if (buffer.size < bodyStart + length) break
                out += String(buffer, bodyStart, length, Charsets.UTF_8)
                buffer = buffer.copyOfRange(bodyStart + length, buffer.size)
            }
            return out
        }

        private fun contentLength(header: String): Int? {
            for (line in header.split("\r\n")) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                    return parts[1].trim().toIntOrNull()
                }
            }
            return null
        }

        private fun indexOfHeaderEnd(bytes: ByteArray): Int {
            var i = 0
            while (i + 3 < bytes.size) {
                if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                    bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
                ) {
                    return i
                }
                i++
            }
            return -1
        }
    }
}
