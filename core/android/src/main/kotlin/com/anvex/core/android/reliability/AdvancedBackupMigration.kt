package com.anvex.core.android.reliability

import com.anvex.core.android.KeystoreCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Versioned migration pipeline for encrypted recovery snapshots and portable envelopes. */
class AdvancedBackupMigration {
    @Serializable data class Envelope(val version: Int, val payload: String)
    data class Inspection(val valid: Boolean, val version: Int, val payloadChars: Int)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun inspect(encoded: String): Inspection = runCatching {
        val env = json.decodeFromString<Envelope>(KeystoreCrypto.decrypt(encoded))
        require(env.version in 1..CURRENT_VERSION)
        Inspection(true, env.version, env.payload.length)
    }.getOrElse { Inspection(false, 0, 0) }

    fun migrate(encoded: String): String {
        val env = json.decodeFromString<Envelope>(KeystoreCrypto.decrypt(encoded))
        require(env.version in 1..CURRENT_VERSION)
        var payload = env.payload; var version = env.version
        while (version < CURRENT_VERSION) { payload = migratePayload(version, payload); version++ }
        return KeystoreCrypto.encrypt(json.encodeToString(Envelope(CURRENT_VERSION, payload)))
    }

    /** Migrates the real automatic-snapshot format used by ReliabilityRepository. */
    fun migrateAutomaticSnapshot(file: File): Boolean {
        if (!file.isFile || file.length() > 4_000_000L) return false
        return runCatching {
            val snapshot = json.decodeFromString<ReliabilityRepository.AutoSnapshot>(KeystoreCrypto.decrypt(file.readText()))
            require(snapshot.version in 1..CURRENT_SNAPSHOT_VERSION)
            if (snapshot.version == CURRENT_SNAPSHOT_VERSION) return@runCatching true
            val migrated = snapshot.copy(version = CURRENT_SNAPSHOT_VERSION)
            val tmp = File(file.parentFile, ".${file.name}.migration.tmp")
            tmp.writeText(KeystoreCrypto.encrypt(json.encodeToString(migrated)))
            require(tmp.renameTo(file))
            true
        }.getOrElse { false }
    }

    private fun migratePayload(version: Int, payload: String): String = when (version) {
        1 -> payload // v2 added optional metadata; old payload remains valid.
        2 -> payload // v3 is backward compatible; snapshot-specific migration is handled above.
        else -> payload
    }

    companion object {
        const val CURRENT_VERSION = 3
        const val CURRENT_SNAPSHOT_VERSION = 3
    }
}
