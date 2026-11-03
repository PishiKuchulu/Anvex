package com.anvex.core.android.runtime.pty

import com.anvex.core.android.terminal.VtEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A live PTY session (F3, P2): owns the master fd + child pid created by
 * [PtyBridge], pumps child output through an incremental UTF-8 decoder into
 * a [VtEmulator], and writes user input back. The emulator is the single
 * source of truth for what's on screen; the Compose layer re-renders from
 * it on every [onUpdate] tick.
 *
 * Lifecycle: [start] once, [close] when the screen leaves (SIGHUP to the
 * child, fd closed, reader cancelled). If the native bridge is unavailable
 * [start] returns false and the caller keeps the legacy pipe session.
 */
class PtySession(
    private val scope: CoroutineScope,
    val emulator: VtEmulator = VtEmulator(cols = 80, rows = 24),
    private val onUpdate: () -> Unit,
) {
    private var fd = -1
    private var pid = -1
    private var readerJob: Job? = null

    val running: Boolean get() = fd >= 0

    fun start(cwd: String, shellPath: String = "/system/bin/sh"): Boolean {
        if (!PtyBridge.available || running) return running
        val env = arrayOf(
            "PATH=/system/bin:/system/xbin",
            "HOME=$cwd",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
        )
        val created = PtyBridge.create(shellPath, cwd, env, emulator.rows, emulator.cols)
        if (created.size < 2) return false
        fd = created[0]
        pid = created[1]
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
        return true
    }

    private suspend fun readLoop() {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = ByteArray(8192)
        var pending = ByteArray(0)
        val charBuf = CharBuffer.allocate(8192)
        while (scope.isActive && fd >= 0) {
            val n = PtyBridge.read(fd, buffer)
            if (n <= 0) break // EOF / EIO: the child has exited
            val combined = pending + buffer.copyOf(n)
            val byteBuf = ByteBuffer.wrap(combined)
            while (true) {
                charBuf.clear()
                val result = decoder.decode(byteBuf, charBuf, false)
                charBuf.flip()
                if (charBuf.remaining() > 0) {
                    emulator.feed(charBuf.toString())
                }
                if (!result.isOverflow) break
            }
            pending = ByteArray(byteBuf.remaining()).also { byteBuf.get(it) }
            onUpdate()
        }
    }

    /** Raw write to the pty (command text, answers, control bytes). */
    fun write(text: String) {
        val target = fd
        if (target < 0) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        scope.launch(Dispatchers.IO) { PtyBridge.write(target, bytes) }
    }

    fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        if (fd >= 0) PtyBridge.resize(fd, rows, cols)
    }

    /** True while the foreground program reads with echo off (passwords). */
    fun echoOff(): Boolean = fd >= 0 && PtyBridge.echoOff(fd)

    fun close() {
        val oldFd = fd
        val oldPid = pid
        fd = -1
        pid = -1
        readerJob?.cancel()
        readerJob = null
        if (oldPid > 0) PtyBridge.signal(oldPid, SIGHUP)
        if (oldFd >= 0) PtyBridge.close(oldFd)
    }

    private companion object {
        const val SIGHUP = 1
    }
}
