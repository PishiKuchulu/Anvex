package com.anvex.core.android.reliability

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Complete safe plugin boundary: package/skill metadata can execute as declarative plans, arbitrary code is never loaded into the app process. */
class PluginRuntime {
    @Serializable
    data class Manifest(
        val id: String,
        val name: String,
        val description: String = "",
        val version: String,
        val gitUrl: String? = null,
        val dependencies: List<String> = emptyList(),
        val entrypoint: String? = null,
        val capabilities: List<String> = emptyList(),
        val skillInstructions: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun parseManifest(text: String): Manifest {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Plugin manifest is too large." }
        val manifest = json.decodeFromString<Manifest>(text)
        require(manifest.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}"))) { "Invalid plugin id." }
        require(manifest.name.length in 1..120 && manifest.version.length in 1..40 && manifest.description.length <= 2_000) { "Invalid plugin metadata." }
        require(manifest.capabilities.size <= 32 && manifest.capabilities.all { it.length in 1..80 }) { "Invalid plugin capabilities." }
        require(manifest.dependencies.size <= 32 && manifest.dependencies.all { it.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}(?:\\s*(?:>=|<=|>|<|=)\\s*\\d+(?:\\.\\d+){0,2})?")) }) { "Invalid plugin dependencies." }
        require(manifest.skillInstructions == null || manifest.skillInstructions.length <= 32_000) { "Skill instructions are too large." }
        require(manifest.entrypoint == null) { "Executable plugin entrypoints are not supported by the Android sandbox." }
        return manifest
    }
}
