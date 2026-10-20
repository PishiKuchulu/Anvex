package com.anvex.core.android.artifacts

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v4.6: turns a deterministic canvas animation page into a REAL MP4 file,
 * entirely on-device. The page implements `window.__anvexSeek(ms)`; the
 * renderer loads it in an off-screen WebView at the exact output size, seeks
 * it frame by frame, captures each frame, and H.264-encodes the sequence
 * with MediaCodec + MediaMuxer. No audio track is produced.
 *
 * This exists because a video request previously had no valid output path:
 * the model could only write an HTML page, so "make a video" delivered an
 * index.html that described a video instead of a video file.
 */
object VideoRenderer {

    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val SEEK_TIMEOUT_MS = 5_000L
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /**
     * Renders [htmlFile] to [output] (an .mp4 path that must not exist yet).
     * Throws with a user-readable message when the page does not implement
     * the frame contract or encoding fails. [onProgress] receives short
     * status lines suitable for the chat status area.
     */
    suspend fun render(
        context: Context,
        htmlFile: File,
        output: File,
        width: Int,
        height: Int,
        fps: Int,
        durationSec: Int,
        onProgress: (String) -> Unit = {},
    ) {
        require(htmlFile.isFile) { "Render page not found: ${htmlFile.name}" }
        val totalFrames = (fps * durationSec).coerceAtLeast(1)
        var webView: WebView? = null
        try {
            webView = withContext(Dispatchers.Main) {
                createWebView(context.applicationContext, htmlFile, width, height)
            }
            val seekType = withContext(Dispatchers.Main) {
                evalJs(webView, "typeof window.__anvexSeek")
            }
            require(seekType?.contains("function") == true) {
                "The render page does not implement window.__anvexSeek(ms), so frames cannot be captured deterministically."
            }
            val encoder = Mp4Encoder(width, height, fps, output)
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(width * height)
                try {
                    for (frame in 0 until totalFrames) {
                        val tMs = frame * 1000L / fps
                        withContext(Dispatchers.Main) {
                            evalJs(webView, "window.__anvexSeek($tMs); 0")
                            webView.draw(Canvas(bitmap))
                        }
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        encoder.encodeFrame(pixels, frame)
                        if (frame % 30 == 0) {
                            onProgress("Rendering video… frame ${frame + 1}/$totalFrames")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                encoder.finish()
            } catch (t: Throwable) {
                encoder.abort()
                throw t
            }
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { webView?.destroy() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebView(context: Context, htmlFile: File, width: Int, height: Int): WebView {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = false
        webView.setBackgroundColor(Color.BLACK)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, width, height)
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!cont.isCompleted) cont.resume(true)
                    }
                }
                webView.loadUrl("file://${htmlFile.absolutePath}")
                cont.invokeOnCancellation { runCatching { webView.stopLoading() } }
            }
        }
        require(loaded == true) { "The render page did not finish loading in time." }
        // Give late script setup one beat, then re-assert the exact layout size
        // (page load can reset the view size on some WebView builds).
        delay(60)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, width, height)
        return webView
    }

    /** Evaluates JS on the WebView (call on Main); returns the string result or null on timeout. */
    private suspend fun evalJs(webView: WebView, script: String): String? =
        withTimeoutOrNull(SEEK_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(script) { result ->
                    if (!cont.isCompleted) cont.resume(result)
                }
            }
        }

    /** H.264 encoder feeding a MediaMuxer, one bitmap frame at a time (YUV420 flexible input). */
    private class Mp4Encoder(
        private val width: Int,
        private val height: Int,
        private val fps: Int,
        output: File,
    ) {
        private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
        private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val bufferInfo = MediaCodec.BufferInfo()
        private var trackIndex = -1
        private var muxerStarted = false
        private var released = false

        init {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceIn(1_000_000, 12_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun encodeFrame(pixels: IntArray, frameIndex: Int) {
            while (true) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val image = codec.getInputImage(inputIndex)
                    if (image != null) {
                        fillYuv(image, pixels)
                    } else {
                        codec.getInputBuffer(inputIndex)?.clear()
                    }
                    val ptsUs = frameIndex * 1_000_000L / fps
                    codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = false)
        }

        fun finish() {
            // Signal end of stream with an empty input buffer, then drain to EOS.
            while (true) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = true)
            release()
        }

        fun abort() = release()

        private fun release() {
            if (released) return
            released = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }

        private fun drain(endOfStream: Boolean) {
            var draining = true
            while (draining) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0 && muxerStarted) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                            }
                        }
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outIndex, false)
                        if (eos) draining = false
                    }
                    else -> draining = false
                }
                if (!endOfStream && outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) draining = false
            }
        }

        /**
         * Converts ARGB pixels into the encoder's YUV_420_888 image, honoring
         * each plane's row/pixel stride (flexible format — the layout is the
         * device's choice, never assumed). BT.601 limited range; chroma is
         * averaged over each 2×2 block before conversion.
         */
        private fun fillYuv(image: android.media.Image, pixels: IntArray) {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            for (row in 0 until height) {
                val yRowOffset = row * yPlane.rowStride
                var px = row * width
                for (col in 0 until width) {
                    val rgb = pixels[px++]
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yBuffer.put(yRowOffset + col * yPlane.pixelStride, y.coerceIn(0, 255).toByte())
                }
            }
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            for (row in 0 until chromaHeight) {
                val uRowOffset = row * uPlane.rowStride
                val vRowOffset = row * vPlane.rowStride
                for (col in 0 until chromaWidth) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    for (dy in 0..1) {
                        var px = (row * 2 + dy) * width + col * 2
                        for (dx in 0..1) {
                            val rgb = pixels[px++]
                            rSum += (rgb shr 16) and 0xFF
                            gSum += (rgb shr 8) and 0xFF
                            bSum += rgb and 0xFF
                        }
                    }
                    val r = rSum / 4
                    val g = gSum / 4
                    val b = bSum / 4
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuffer.put(uRowOffset + col * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                    vBuffer.put(vRowOffset + col * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
