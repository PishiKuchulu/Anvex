package com.anvex.core.android.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** List metadata for one direct-chat conversation (pin / rename / archive). */
@Serializable
data class ConversationMeta(
    val conversationId: String,
    val title: String?,
    val pinned: Boolean,
    val archived: Boolean,
)

private val Context.conversationMetaDataStore by preferencesDataStore(name = "conversation_meta")

/**
 * Direct-chat list metadata, kept in DataStore rather than Room: these are
 * per-conversation UI flags (a handful of small rows), and a Room table
 * would force a schema version bump plus a Room-generated schema snapshot
 * that this repo cannot produce outside a connected build. One JSON
 * document under a single key; reads decode leniently so a corrupt entry
 * degrades to "no metadata" instead of breaking the drawer. All
 * read-modify-writes decode inside [edit]'s transform, so concurrent
 * pin/rename/archive taps cannot lose each other's updates.
 */
class ConversationMetaStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val key = stringPreferencesKey("metas")

    /** Audit 02: last corrupt payload, kept for recovery instead of being silently dropped. */
    private val corruptBackupKey = stringPreferencesKey("metas_corrupt_backup")

    suspend fun all(): Map<String, ConversationMeta> {
        val raw = context.conversationMetaDataStore.data.first()[key] ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ConversationMeta>>(raw)
        }.getOrElse {
            // Audit 02: one corrupt decode used to make ALL conversation
            // titles/pins vanish with no trace. Preserve the raw payload
            // once under a backup key before degrading to empty.
            if (raw.isNotBlank()) {
                runCatching {
                    context.conversationMetaDataStore.edit { prefs ->
                        if (prefs[corruptBackupKey] == null) prefs[corruptBackupKey] = raw
                    }
                }
            }
            emptyMap()
        }
    }

    suspend fun upsert(meta: ConversationMeta) {
        context.conversationMetaDataStore.edit { prefs ->
            val current = decode(prefs[key])
            prefs[key] = json.encodeToString(current + (meta.conversationId to meta))
        }
    }

    /**
     * Atomic single-conversation update: [transform] runs inside the
     * DataStore edit against the CURRENT stored value (audit 02: callers
     * doing read-modify-write through [all] + [upsert] could lose a
     * concurrent rename/pin). A null transform result deletes the entry.
     */
    suspend fun update(conversationId: String, transform: (ConversationMeta?) -> ConversationMeta?) {
        context.conversationMetaDataStore.edit { prefs ->
            val current = decode(prefs[key])
            val next = transform(current[conversationId])
            val merged = if (next == null) current - conversationId else current + (conversationId to next)
            if (merged != current) prefs[key] = json.encodeToString(merged)
        }
    }

    suspend fun delete(conversationId: String) {
        context.conversationMetaDataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (conversationId in current) {
                prefs[key] = json.encodeToString(current - conversationId)
            }
        }
    }

    private fun decode(raw: String?): Map<String, ConversationMeta> =
        runCatching {
            if (raw.isNullOrBlank()) emptyMap()
            else json.decodeFromString<Map<String, ConversationMeta>>(raw)
        }.getOrDefault(emptyMap())
}
