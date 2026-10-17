package com.anvex.app.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Content received through the Android share sheet (Phase 3.2). MainActivity offers a
 * payload; Home asks which chat should receive it; the chat takes it once on open.
 * Shared files are copied into the app cache first, so they stay readable after the
 * sender's temporary grant expires.
 */
object IncomingShare {

    data class Payload(val text: String?, val uris: List<Uri>)

    private val _pending = MutableStateFlow<Payload?>(null)
    val pending: StateFlow<Payload?> = _pending.asStateFlow()

    @Volatile
    private var targetProjectId: String? = null

    fun offer(payload: Payload) {
        synchronized(this) {
            _pending.value = payload
            targetProjectId = null
        }
    }

    fun assignTo(projectId: String) {
        synchronized(this) { targetProjectId = projectId }
    }

    /** Returns the payload only for the chat it was assigned to, and clears it. */
    fun takeFor(projectId: String): Payload? = synchronized(this) {
        if (targetProjectId != projectId) return null
        val payload = _pending.value
        _pending.value = null
        targetProjectId = null
        payload
    }

    fun dismiss() {
        synchronized(this) {
            _pending.value = null
            targetProjectId = null
        }
    }
}
