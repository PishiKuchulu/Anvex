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

    suspend fun all(): Map<String, ConversationMeta> =
        runCatching {
            json.decodeFromString<Map<String, ConversationMeta>>(
                context.conversationMetaDataStore.data.first()[key] ?: return emptyMap(),
            )
        }.getOrDefault(emptyMap())

    suspend fun upsert(meta: ConversationMeta) {
        context.conversationMetaDataStore.edit { prefs ->
            val current = decode(prefs[key])
            prefs[key] = json.encodeToString(current + (meta.conversationId to meta))
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
