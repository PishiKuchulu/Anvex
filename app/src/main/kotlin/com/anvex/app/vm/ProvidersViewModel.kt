package com.anvex.app.vm

import com.anvex.core.model.Project
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.ai.ProviderClientFactory
import com.anvex.core.ai.ValidationResult
import com.anvex.core.ai.zai.ZaiAccountCoordinator
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.RepoResult
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ProvidersViewModel : ViewModel() {

    private val graph = AppGraph.get()

    /** Projects, used by the terminal picker in Settings. */
    val projects: kotlinx.coroutines.flow.StateFlow<List<Project>> = graph.projects.observeAll()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    val providers: StateFlow<List<ProviderConfig>> = graph.providers.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class EditUi(
        val id: String = "new",
        val name: String = "",
        val kind: ProviderKind = ProviderKind.CUSTOM_OPENAI,
        val baseUrl: String = "",
        val apiKey: String = "",
        val apiKeySet: Boolean = false,
        val model: String = "",
        val extraHeaders: String = "",
        val temperature: String = "",
        val maxTokens: String = "",
        val isDefault: Boolean = false,
    ) {
        val isNew: Boolean get() = id == "new"
    }

    val edit = MutableStateFlow(EditUi())

    val testResult = MutableStateFlow<ValidationResult?>(null)
    val testing = MutableStateFlow(false)
    val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val loadingModels = MutableStateFlow(false)
    val saveMessage = MutableStateFlow<String?>(null)
    val credentialWarning = MutableStateFlow<String?>(null)

    fun loadForEdit(id: String, initialKind: String = "") {
        if (id == "new") {
            // Deep-link kind (e.g. a home-screen card preselecting a provider type).
            val kind = runCatching { ProviderKind.valueOf(initialKind) }.getOrNull()
                ?: ProviderKind.CUSTOM_OPENAI
            edit.value = EditUi(id = "new", kind = kind)
            return
        }
        viewModelScope.launch {
            val config = graph.providers.get(id) ?: return@launch
            credentialWarning.value = if (graph.providers.hasCredentialDecryptionFailure(id)) {
                "Stored API key cannot be decrypted on this installation. Enter it again."
            } else null
            edit.value = EditUi(
                id = config.id,
                name = config.name,
                kind = config.kind,
                baseUrl = config.baseUrl,
                apiKey = "",
                apiKeySet = config.apiKeyEnc != null,
                model = config.model,
                extraHeaders = config.extraHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                temperature = config.temperature?.toString() ?: "",
                maxTokens = config.maxTokens?.toString() ?: "",
                isDefault = config.isDefault,
            )
        }
    }

    fun update(transform: EditUi.() -> EditUi) {
        edit.value = edit.value.transform()
    }

    fun testConnection() {
        val ui = edit.value
        testing.value = true
        testResult.value = null
        viewModelScope.launch {
            val runtime = buildRuntime(ui) ?: run {
                testing.value = false
                testResult.value = ValidationResult(false, 0, credentialWarning.value ?: "Enter base URL, model and API key first.")
                return@launch
            }
            val result = try {
                ProviderClientFactory.create(runtime).validateConnection()
            } catch (e: Exception) {
                ValidationResult(false, 0, "Network error: ${e.message ?: "unreachable"}")
            }
            testing.value = false
            testResult.value = result
        }
    }

    fun loadModels() {
        val ui = edit.value
        loadingModels.value = true
        viewModelScope.launch {
            // v4.4.4: discovery must work before a model is chosen (see buildRuntime).
            val runtime = buildRuntime(ui, requireModel = false) ?: run {
                loadingModels.value = false
                return@launch
            }
            try {
                models.value = ProviderClientFactory.create(runtime).listModels()
            } catch (e: Exception) {
                models.value = emptyList()
            }
            loadingModels.value = false
        }
    }

    fun save(onDone: () -> Unit) {
        val ui = edit.value
        // v4.0 (GOAL 5): local-server kinds can no longer be created; legacy rows
        // remain read-only until the user re-creates them as cloud endpoints.
        // v4.4.4: retired kinds (Antigravity, Cline) are likewise read-only —
        // the product surface no longer offers them.
        if (ui.kind.isLegacyLocalKind || ui.kind.isRetiredKind) {
            saveMessage.value = if (ui.kind.isRetiredKind)
                "This provider kind was removed in Anvex 4.4.4 and can no longer be edited. Create a new provider instead."
            else
                "Unsupported provider type: Anvex 4.0 is cloud-only. Create a custom cloud endpoint instead."
            return
        }
        viewModelScope.launch {
            val headers = parseHeaders(ui.extraHeaders)
            val config = ProviderConfig(
                id = if (ui.isNew) UUID.randomUUID().toString() else ui.id,
                name = ui.name.trim(),
                kind = ui.kind,
                apiKeyEnc = null,
                baseUrl = ui.baseUrl.trim().ifBlank { ui.kind.defaultBaseUrl },
                model = ui.model.trim(),
                extraHeaders = headers,
                temperature = ui.temperature.trim().toDoubleOrNull(),
                maxTokens = ui.maxTokens.trim().toIntOrNull(),
                isDefault = ui.isDefault || providers.value.isEmpty(),
            )
            val result = graph.providers.save(config, ui.apiKey.takeIf { it.isNotBlank() })
            when (result) {
                is RepoResult.Ok -> {
                    saveMessage.value = "Saved"
                    onDone()
                }
                is RepoResult.Err -> saveMessage.value = "${result.title}: ${result.detail}"
            }
        }
    }

    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            graph.providers.delete(id)
            onDone()
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch { graph.providers.setDefault(id) }
    }

    private suspend fun buildRuntime(ui: EditUi, requireModel: Boolean = true): com.anvex.core.ai.ProviderRuntimeConfig? {
        // v4.4.4: model discovery ("Fetch models") never needs a model —
        // the whole point is to pick one from the catalog. Test connection and
        // save keep the gate.
        if (requireModel && ui.model.isBlank()) return null
        if ((ui.kind == ProviderKind.CUSTOM_OPENAI || ui.kind == ProviderKind.CUSTOM_ANTHROPIC) && ui.baseUrl.isBlank()) return null
        val apiKey = resolveApiKey(ui) ?: return null
        val config = ProviderConfig(
            id = ui.id,
            name = ui.name,
            kind = ui.kind,
            baseUrl = ui.baseUrl.trim().ifBlank { ui.kind.defaultBaseUrl },
            model = ui.model.trim(),
            extraHeaders = parseHeaders(ui.extraHeaders),
            temperature = ui.temperature.trim().toDoubleOrNull(),
            maxTokens = ui.maxTokens.trim().toIntOrNull(),
        )
        return com.anvex.core.ai.ProviderRuntimeConfig(config, apiKey)
    }

    /** Resolve the effective API key for the edit form: typed value, else the stored ciphertext. Null on decryption failure. */
    private suspend fun resolveApiKey(ui: EditUi): String? {
        if (ui.apiKey.isNotBlank()) return ui.apiKey.trim()
        if (ui.isNew) return ""
        val encoded = graph.providers.get(ui.id)?.apiKeyEnc ?: return ""
        if (encoded.isBlank()) return ""
        return runCatching {
            com.anvex.core.android.KeystoreCrypto.decrypt(encoded)
        }.getOrElse {
            credentialWarning.value = "Stored API key cannot be decrypted on this installation. Enter it again."
            null
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        return text.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
    }

    // ================================================================ v4.4: Z.ai account login

    /** Live state of the ZCode-compatible account login flow (no secrets ever stored here). */
    sealed class ZaiLoginUi {
        object Idle : ZaiLoginUi()
        data class Running(
            val stage: ZaiAccountCoordinator.Stage,
            val authorizeUrl: String? = null,
            val attempt: Int? = null,
        ) : ZaiLoginUi()
        data class Failed(val message: String) : ZaiLoginUi()
    }

    val zaiLogin = MutableStateFlow<ZaiLoginUi>(ZaiLoginUi.Idle)
    private var zaiCoordinator: ZaiAccountCoordinator? = null

    /** The already-connected Z.ai provider row (if any). */
    val zaiProvider: ProviderConfig?
        get() = providers.value.firstOrNull { it.kind == ProviderKind.ZAI_ACCOUNT }

    /**
     * Start the account login. [onAuthorizeUrl] is invoked (main thread) with the
     * upstream-generated browser URL; the screen opens it in the system browser.
     */
    fun startZaiLogin(onAuthorizeUrl: (String) -> Unit) {
        if (zaiLogin.value is ZaiLoginUi.Running) return // single-flight (double-tap guard)
        val coordinator = ZaiAccountCoordinator()
        zaiCoordinator = coordinator
        zaiLogin.value = ZaiLoginUi.Running(ZaiAccountCoordinator.Stage.INITIATING)
        viewModelScope.launch {
            val outcome = coordinator.login { progress ->
                // Progress fires on the IO dispatcher; only stage/urls, never secrets.
                zaiLogin.value = ZaiLoginUi.Running(progress.stage, progress.authorizeUrl, progress.attempt)
                val url = progress.authorizeUrl
                if (progress.stage == ZaiAccountCoordinator.Stage.WAITING_FOR_BROWSER && url != null) {
                    viewModelScope.launch { onAuthorizeUrl(url) } // hop to Main
                }
            }
            when (outcome) {
                is ZaiAccountCoordinator.Outcome.Connected -> {
                    val identity = outcome.accountName ?: outcome.accountEmail ?: "GLM Coding Plan"
                    val existing = zaiProvider
                    val config = ProviderConfig(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = "Z.ai · $identity",
                        kind = ProviderKind.ZAI_ACCOUNT,
                        baseUrl = ProviderKind.ZAI_ACCOUNT.defaultBaseUrl,
                        model = existing?.model?.takeIf { it.isNotBlank() } ?: "glm-5.3",
                        isDefault = existing?.isDefault ?: providers.value.isEmpty(),
                    )
                    val saved = graph.providers.save(config, outcome.planApiKey)
                    zaiLogin.value = when (saved) {
                        is RepoResult.Ok -> ZaiLoginUi.Idle // the row now shows as Connected
                        is RepoResult.Err -> ZaiLoginUi.Failed(
                            "Login succeeded but saving the credential failed: ${saved.title} — ${saved.detail}"
                        )
                    }
                }
                is ZaiAccountCoordinator.Outcome.Failed -> zaiLogin.value = ZaiLoginUi.Failed(outcome.reason)
                ZaiAccountCoordinator.Outcome.Cancelled -> zaiLogin.value = ZaiLoginUi.Idle
            }
        }
    }

    fun cancelZaiLogin() {
        zaiCoordinator?.cancel()
    }
}

class SettingsViewModel : ViewModel() {

    private val graph = AppGraph.get()

    val settings = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** v4.4: real projects for the terminal picker (fixes the unresolved `projects` reference). */
    val projects: StateFlow<List<com.anvex.core.model.Project>> = graph.projects.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cacheBytes = MutableStateFlow(0L)

    fun refreshCacheSize() {
        cacheBytes.value = graph.projects.cacheSizeBytes()
    }

    fun setTheme(mode: com.anvex.core.android.repo.ThemeMode) =
        viewModelScope.launch { graph.settings.setThemeMode(mode) }

    /** v4.0 (GOAL 4): switch the visual style family (Professional / Glass). */
    fun setThemeStyle(style: com.anvex.core.android.repo.UiThemeStyle) =
        viewModelScope.launch { graph.settings.setUiThemeStyle(style) }

    fun setLanguage(language: com.anvex.core.android.repo.AppLanguage) =
        viewModelScope.launch { graph.settings.setLanguage(language) }

    fun setDynamicColor(v: Boolean) = viewModelScope.launch { graph.settings.setDynamicColor(v) }

    fun setEditorFont(v: Int) = viewModelScope.launch { graph.settings.setEditorFontSize(v) }

    fun setChatFont(v: Int) = viewModelScope.launch { graph.settings.setChatFontSize(v) }

    fun setAutoSave(v: Boolean) = viewModelScope.launch { graph.settings.setEditorAutoSave(v) }

    fun clearCache() {
        graph.projects.clearCache()
        refreshCacheSize()
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            graph.projects.observeAll().first().forEach { project ->
                graph.chat.deleteForProject(project.id)
            }
        }
    }
}
