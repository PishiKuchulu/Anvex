package com.anvex.core.android.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.anvex.core.model.ThinkingLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "anvex_settings")

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * v4.0 (GOAL 4): visual style family for the Compose shell.
 * NEBULA — v4.2 default: deep-space canvas, violet→cyan signature gradient, glass cards.
 * PROFESSIONAL — Linear/Vercel-inspired dark-first design system.
 * GLASS — Glassmorphism / expressive Material 3 variant with translucent surfaces.
 * AURORA — v4.4 port of the untitled.zip web-prototype design language: cyan/slate
 *   dark ladder (#070b14→#050811), cyan primary, semantic status accents, mono badges.
 * MIDNIGHT — deep-navy dark-first, ice-blue accent; calm and focused.
 * FOREST — deep-green dark-first, mint accent; easy on the eyes.
 * EMBER — warm-charcoal dark-first, amber accent; cozy contrast.
 */
@Serializable
enum class UiThemeStyle { PROFESSIONAL, GLASS, NEBULA, AURORA, MIDNIGHT, FOREST, EMBER }

@Serializable
enum class AppLanguage { SYSTEM, ENGLISH, PERSIAN }

/**
 * v4.4: how Node.js workloads execute on-device.
 * IN_APP — the bundled Node-compatibility layer (WebView JS engine + Node-style APIs).
 * TERMUX — forward `node`/`npm` commands to the Termux:API RUN_COMMAND bridge
 *   (requires Termux + Termux:API installed and the bridge enabled in Settings).
 */
@Serializable
enum class NodeBackend { IN_APP, TERMUX }

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val dynamicColor: Boolean = false,
    // v4.4.4 (Command Deck): fresh installs start with PROFESSIONAL — the restrained
    // Obsidian IDE direction. Stored choices are kept.
    val uiThemeStyle: UiThemeStyle = UiThemeStyle.PROFESSIONAL,
    val editorFontSize: Int = 13,
    val editorAutoSave: Boolean = true,
    val chatFontSize: Int = 15,
    val previewConsoleVisible: Boolean = false,
    val lastProjectId: String = "",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.NORMAL,
    val onboardingSeen: Boolean = false,
    val lanEnabled: Boolean = false,
    val webPort: Int = 8765,
    /** v4.2: long-term memory (learn preferences across chats). */
    val memoryEnabled: Boolean = true,
    /** v4.2: free-form "how should the assistant behave" instructions. */
    val customInstructions: String = "",
    /** v4.2: allow the agent to use web fetch/search tools. */
    val webToolsEnabled: Boolean = true,
    /** v4.4: Node.js execution backend (in-app compatibility layer vs Termux bridge). */
    val nodeBackend: NodeBackend = NodeBackend.IN_APP,
    /** v4.4: allow forwarding commands to Termux through RUN_COMMAND (opt-in). */
    val termuxBridgeEnabled: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val UI_THEME_STYLE = stringPreferencesKey("ui_theme_style")
        val EDITOR_FONT = intPreferencesKey("editor_font_size")
        val AUTOSAVE = booleanPreferencesKey("editor_autosave")
        val CHAT_FONT = intPreferencesKey("chat_font_size")
        val CONSOLE_VISIBLE = booleanPreferencesKey("preview_console_visible")
        val LAST_PROJECT = stringPreferencesKey("last_project_id")
        val THINKING = stringPreferencesKey("thinking_level")
        val ONBOARDING = booleanPreferencesKey("onboarding_seen")
        val LAN_ENABLED = booleanPreferencesKey("lan_enabled")
        val WEB_PORT = intPreferencesKey("web_port")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val CUSTOM_INSTRUCTIONS = stringPreferencesKey("custom_instructions")
        val WEB_TOOLS = booleanPreferencesKey("web_tools_enabled")
        val NODE_BACKEND = stringPreferencesKey("node_backend")
        val TERMUX_BRIDGE = booleanPreferencesKey("termux_bridge_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            language = p[Keys.LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: false,
            uiThemeStyle = p[Keys.UI_THEME_STYLE]?.let { runCatching { UiThemeStyle.valueOf(it) }.getOrNull() } ?: UiThemeStyle.PROFESSIONAL,
            editorFontSize = p[Keys.EDITOR_FONT] ?: 13,
            editorAutoSave = p[Keys.AUTOSAVE] ?: true,
            chatFontSize = p[Keys.CHAT_FONT] ?: 15,
            previewConsoleVisible = p[Keys.CONSOLE_VISIBLE] ?: false,
            lastProjectId = p[Keys.LAST_PROJECT] ?: "",
            thinkingLevel = p[Keys.THINKING]?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() } ?: ThinkingLevel.NORMAL,
            onboardingSeen = p[Keys.ONBOARDING] ?: false,
            lanEnabled = p[Keys.LAN_ENABLED] ?: false,
            webPort = (p[Keys.WEB_PORT] ?: 8765).coerceIn(1024, 65535),
            memoryEnabled = p[Keys.MEMORY_ENABLED] ?: true,
            customInstructions = p[Keys.CUSTOM_INSTRUCTIONS] ?: "",
            webToolsEnabled = p[Keys.WEB_TOOLS] ?: true,
            nodeBackend = p[Keys.NODE_BACKEND]?.let { runCatching { NodeBackend.valueOf(it) }.getOrNull() } ?: NodeBackend.IN_APP,
            termuxBridgeEnabled = p[Keys.TERMUX_BRIDGE] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setUiThemeStyle(style: UiThemeStyle) = context.dataStore.edit { it[Keys.UI_THEME_STYLE] = style.name }
    suspend fun setLanguage(language: AppLanguage) = context.dataStore.edit { it[Keys.LANGUAGE] = language.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = v }
    suspend fun setEditorFontSize(v: Int) = context.dataStore.edit { it[Keys.EDITOR_FONT] = v.coerceIn(10, 24) }
    suspend fun setEditorAutoSave(v: Boolean) = context.dataStore.edit { it[Keys.AUTOSAVE] = v }
    suspend fun setChatFontSize(v: Int) = context.dataStore.edit { it[Keys.CHAT_FONT] = v.coerceIn(12, 22) }
    suspend fun setLastProject(id: String) = context.dataStore.edit { it[Keys.LAST_PROJECT] = id }
    suspend fun setThinkingLevel(level: ThinkingLevel) = context.dataStore.edit { it[Keys.THINKING] = level.name }
    suspend fun setOnboardingSeen(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING] = v }
    suspend fun setLanEnabled(v: Boolean) = context.dataStore.edit { it[Keys.LAN_ENABLED] = v }
    suspend fun setWebPort(v: Int) = context.dataStore.edit { it[Keys.WEB_PORT] = v.coerceIn(1024, 65535) }
    suspend fun setMemoryEnabled(v: Boolean) = context.dataStore.edit { it[Keys.MEMORY_ENABLED] = v }
    suspend fun setCustomInstructions(v: String) = context.dataStore.edit { it[Keys.CUSTOM_INSTRUCTIONS] = v.take(4_000) }
    suspend fun setWebToolsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.WEB_TOOLS] = v }
    suspend fun setNodeBackend(v: NodeBackend) = context.dataStore.edit { it[Keys.NODE_BACKEND] = v.name }
    suspend fun setTermuxBridgeEnabled(v: Boolean) = context.dataStore.edit { it[Keys.TERMUX_BRIDGE] = v }

    suspend fun restoreFromBackup(encoded: String) {
        val s = Json { ignoreUnknownKeys = true }.decodeFromString<AppSettings>(encoded)
        context.dataStore.edit { p ->
            p[Keys.THEME] = s.themeMode.name; p[Keys.LANGUAGE] = s.language.name; p[Keys.DYNAMIC] = s.dynamicColor; p[Keys.UI_THEME_STYLE] = s.uiThemeStyle.name; p[Keys.EDITOR_FONT] = s.editorFontSize
            p[Keys.AUTOSAVE] = s.editorAutoSave; p[Keys.CHAT_FONT] = s.chatFontSize; p[Keys.CONSOLE_VISIBLE] = s.previewConsoleVisible
            p[Keys.LAST_PROJECT] = s.lastProjectId; p[Keys.THINKING] = s.thinkingLevel.name; p[Keys.ONBOARDING] = s.onboardingSeen
            p[Keys.LAN_ENABLED] = s.lanEnabled; p[Keys.WEB_PORT] = s.webPort.coerceIn(1024, 65535)
            p[Keys.MEMORY_ENABLED] = s.memoryEnabled; p[Keys.CUSTOM_INSTRUCTIONS] = s.customInstructions.take(4_000); p[Keys.WEB_TOOLS] = s.webToolsEnabled
            p[Keys.NODE_BACKEND] = s.nodeBackend.name; p[Keys.TERMUX_BRIDGE] = s.termuxBridgeEnabled
        }
    }
}
