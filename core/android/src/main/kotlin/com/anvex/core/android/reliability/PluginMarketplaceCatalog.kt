package com.anvex.core.android.reliability

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

/** Optional remote catalog. Catalog is metadata-only and is never allowed to execute plugin code. */
class PluginMarketplaceCatalog {
    @Serializable data class Item(val id: String, val name: String, val description: String, val version: String, val gitUrl: String, val dependencies: List<String> = emptyList())
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun parse(text: String): List<Item> {
        require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Marketplace catalog too large." }
        val items = json.decodeFromString<List<Item>>(text)
        require(items.size <= 500) { "Marketplace catalog contains too many items." }
        items.forEach {
            require(it.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}")))
            val u = URI(it.gitUrl); require(u.scheme == "https" && u.userInfo == null && u.rawQuery == null && u.rawFragment == null && u.host?.lowercase() in setOf("github.com", "gitlab.com"))
            require(it.dependencies.size <= 32 && it.dependencies.all { d -> d.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}(?:\\s*(?:>=|<=|>|<|=)\\s*\\d+(?:\\.\\d+){0,2})?")) })
        }
        return items
    }

    fun fetch(url: String): List<Item> {
        val u = URI(url); require(u.scheme == "https" && u.userInfo == null && u.rawQuery == null && u.rawFragment == null)
        require(u.host?.lowercase() in setOf("raw.githubusercontent.com", "github.com", "gitlab.com")) { "Marketplace catalogs must use a trusted GitHub/GitLab origin." }
        val c = u.toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
        require(c.responseCode in 200..299) { "Catalog HTTP ${c.responseCode}" }
        require(c.contentLengthLong in 0..2L * 1024 * 1024) { "Catalog too large." }
        return c.inputStream.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    }
}
