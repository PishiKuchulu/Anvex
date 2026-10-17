package com.anvex.core.android.fs

import android.os.FileObserver
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * v4.0 (GOAL 2): recursive project file watcher.
 *
 * Built on android.os.FileObserver (the Linux inotify wrapper — the only watcher
 * primitive that works across API 26+ without extra dependencies; java.nio
 * WatchService is not implemented on Android). Direct observation is recursive on
 * API 29+; on API 26-28 we register observers for the root plus every existing
 * subdirectory and refresh the registration tree when directories are created.
 *
 * Events are de-duplicated (path + kind) with a short window because Android
 * delivers many granular inotify events per edit.
 */
class ProjectFileObserver(
    private val root: File,
    private val onEvent: (path: String, kind: Kind) -> Unit,
) : AutoCloseable {

    enum class Kind { CREATED, MODIFIED, DELETED, MOVED }

    private val observers = ArrayList<FileObserver>()
    private val recent = LinkedHashMap<String, Long>()

    /** Test-visible event stream (harness subscribes through the bridge). */
    private val _events = MutableSharedFlow<Pair<String, Kind>>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Pair<String, Kind>> = _events

    fun start() {
        stop()
        observeTree(root)
    }

    fun stop() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
    }

    override fun close() = stop()

    private fun observeTree(dir: File) {
        if (!dir.isDirectory) return
        registerObserver(dir)
        dir.listFiles()?.filter { it.isDirectory && it.name != ".git" }?.forEach { observeTree(it) }
    }

    private fun registerObserver(dir: File) {
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        val relativeRoot = root
        val observer = object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, rawPath: String?) {
                if (rawPath.isNullOrBlank()) return
                val file = File(dir, rawPath)
                val rel = file.relativeTo(relativeRoot).path.replace(File.separatorChar, '/')
                if (rel.startsWith(".git/") || rel == ".git") return
                val kind = when (event and 0x0FFF) {
                    FileObserver.CREATE -> Kind.CREATED
                    FileObserver.MODIFY -> Kind.MODIFIED
                    FileObserver.DELETE, FileObserver.DELETE_SELF -> Kind.DELETED
                    FileObserver.MOVED_FROM -> Kind.DELETED
                    FileObserver.MOVED_TO -> Kind.CREATED
                    else -> return
                }
                if (kind == Kind.CREATED && file.isDirectory) {
                    // Grow the observer tree for newly created directories (API 26-28).
                    registerObserver(file)
                    observeTree(file)
                }
                if (deduplicate(rel, kind)) {
                    _events.tryEmit(rel to kind)
                    onEvent(rel, kind)
                }
            }
        }
        observer.startWatching()
        observers.add(observer)
    }

    private fun deduplicate(path: String, kind: Kind): Boolean {
        val key = "$path:${kind.name}"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val last = recent[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) return false
            recent[key] = now
            if (recent.size > 512) {
                val cutoff = now - DEDUP_WINDOW_MS
                recent.entries.removeIf { it.value < cutoff }
            }
        }
        return true
    }

    companion object {
        const val DEDUP_WINDOW_MS = 400L
    }
}
