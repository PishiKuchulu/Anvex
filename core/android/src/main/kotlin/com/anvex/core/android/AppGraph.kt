package com.anvex.core.android

import android.app.Application
import androidx.room.Room
import com.anvex.core.android.db.AnvexDatabase
import com.anvex.core.android.repo.ChatRepository
import com.anvex.core.android.repo.MutationRepository
import com.anvex.core.android.repo.ProjectRepository
import com.anvex.core.android.repo.ProviderRepository
import com.anvex.core.android.repo.SettingsRepository
import com.anvex.core.android.server.StaticPreviewServer
import com.anvex.core.ai.FallbackProviderClient
import com.anvex.core.android.runtime.AndroidRuntimeGateway
import com.anvex.core.android.runtime.ProviderAgentPlanner
import com.anvex.core.android.artifacts.ArtifactManager
import com.anvex.core.android.reliability.ReliabilityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Manual DI graph. Everything initializes here — no externally started
 * processes, no services required by the user (brief §4).
 */
class AppGraph private constructor(private val app: Application) {

    val context: android.content.Context get() = app

    /**
     * P0-3 fix (v1.1): explicit migration chain, NO destructive fallback.
     * User projects, conversations, and provider configs survive upgrades.
     * A missing future migration now fails loudly (IllegalStateException) at
     * open time instead of silently wiping data.
     */
    val database: AnvexDatabase = Room.databaseBuilder(
        app, AnvexDatabase::class.java, "anvex.db"
    )
        .addMigrations(*AnvexDatabase.ALL_MIGRATIONS)
        .build()

    val settings: SettingsRepository = SettingsRepository(app)

    val projects: ProjectRepository = ProjectRepository(
        app,
        database.projectDao(),
        database.messageDao(),
        database.mutationDao(),
    )

    val chat: ChatRepository = ChatRepository(database.messageDao())

    /** Direct-chat list metadata (pin / rename / archive), DataStore-backed. */
    val conversationMeta: ConversationMetaStore = ConversationMetaStore(app)

    /** v1.1 §24: persisted change journal (action results) per project. */
    val mutations: MutationRepository = MutationRepository(database.mutationDao())

    val providers: ProviderRepository = ProviderRepository(database.providerDao())

    val backup: PortableBackup = PortableBackup(providers, settings)
    val github: com.anvex.core.android.repo.GitHubRepository = com.anvex.core.android.repo.GitHubRepository(app)
    /** v4.x cloud build (feature #4 path 1): GitHub Actions APK builder; shares the github token. */
    val cloudBuild: com.anvex.core.android.repo.CloudBuildRepository =
        com.anvex.core.android.repo.CloudBuildRepository(tokenProvider = { github.savedToken() })
    val toolchain: com.anvex.core.android.build.ToolchainManager = com.anvex.core.android.build.ToolchainManager(app)
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val taskEngine: com.anvex.core.android.reliability.DurableTaskEngine =
        com.anvex.core.android.reliability.DurableTaskEngine(database.taskDao(), taskScope)
    val approvals: com.anvex.core.android.reliability.DurableApprovalRepository =
        com.anvex.core.android.reliability.DurableApprovalRepository(database.approvalGrantDao())
    val lanAccess: com.anvex.core.android.reliability.LanAccessManager =
        com.anvex.core.android.reliability.LanAccessManager()
    val webServer: com.anvex.core.android.reliability.InternalWebServer =
        com.anvex.core.android.reliability.InternalWebServer(
            lanAccess,
            statusProvider = { "Agent runtime ready" },
            tasksProvider = { kotlinx.coroutines.runBlocking { database.taskDao().recent(50) } },
            logsProvider = { kotlinx.coroutines.runBlocking { database.diagnosticDao().recent(50) } },
        )
    val deviceCapabilities: com.anvex.core.android.reliability.DeviceCapabilityDetector =
        com.anvex.core.android.reliability.DeviceCapabilityDetector(app)
    val commandApproval: com.anvex.core.android.reliability.CommandApprovalService =
        com.anvex.core.android.reliability.CommandApprovalService(approvals)
    private val pluginPackageRoot = java.io.File(app.filesDir, "plugins").apply { mkdirs() }
    val pluginInstaller: com.anvex.core.android.reliability.PluginPackageInstaller =
        com.anvex.core.android.reliability.PluginPackageInstaller(pluginPackageRoot, database.pluginDao())
    val pluginMarketplace: com.anvex.core.android.reliability.PluginMarketplace =
        com.anvex.core.android.reliability.PluginMarketplace(database.pluginDao(), pluginInstaller)
    val skillRuntime: com.anvex.core.android.reliability.SkillRuntimeService =
        com.anvex.core.android.reliability.SkillRuntimeService(database.pluginDao())
    val reliability: ReliabilityRepository = ReliabilityRepository(app, database, settings, toolchain, taskEngine)
    val androidBuildEngine: com.anvex.core.android.build.AndroidBuildEngine = com.anvex.core.android.build.AndroidBuildEngine(toolchain)
    val agentIdeBridge: AgentIdeBridge = AgentIdeBridge(taskEngine, androidBuildEngine)
    /** Real Android runtime gateway used by durable agent tasks; SAF workspaces fail closed. */
    val androidRuntime: AndroidRuntimeGateway = AndroidRuntimeGateway(projects, app, java.io.File(app.filesDir, "terminal-home"))
    val artifactManager: ArtifactManager = ArtifactManager(app)
    /** v4.2: long-term memory and read-only agent tools shared by every chat session. */
    val memory: com.anvex.core.android.repo.MemoryRepository = com.anvex.core.android.repo.MemoryRepository(app)
    val agentTools: com.anvex.core.android.tools.AgentToolExecutor = com.anvex.core.android.tools.AgentToolExecutor()

    /** Wires settings + memory + tools into a [com.anvex.core.android.session.SessionExtras]. */
    fun sessionExtras(): com.anvex.core.android.session.SessionExtras =
        com.anvex.core.android.session.SessionExtras(
            memoryEnabled = { settings.settings.first().memoryEnabled },
            memoryBlock = { memory.promptBlock() },
            applyMemory = { memory.apply(it) },
            customInstructions = { settings.settings.first().customInstructions },
            webTools = { settings.settings.first().webToolsEnabled },
            tools = agentTools,
        )
    /** Provider-backed planner connects the durable Agent task path to the configured AI layer. */
    val agentTaskBridge: com.anvex.core.android.reliability.AgentTaskBridge =
        com.anvex.core.android.reliability.AgentTaskBridge(
            taskEngine = taskEngine,
            fsProvider = { projectId -> projects.fileSystemForAsync(projectId) },
            planner = ProviderAgentPlanner { fallbackAiClient() },
            commandApprovalService = commandApproval,
            mutationRepository = mutations,
            runtime = androidRuntime,
            artifactManager = artifactManager,
        )

    /** v4.9: Pi coding agent as experimental Engine #2 (see runtime/pi). */
    val piEngine: com.anvex.core.android.runtime.pi.PiEngine =
        com.anvex.core.android.runtime.pi.PiEngine(app, commandApproval)
    /** v4.9: durable `pi-agent` task type (checkpoints carry the Pi session file). */
    val piTaskBridge: com.anvex.core.android.runtime.pi.PiTaskBridge =
        com.anvex.core.android.runtime.pi.PiTaskBridge(taskEngine, piEngine, providers, projects, settings)
    /** v4.10: chat-surface Pi turns with per-chat session continuity. */
    val piChatBridge: com.anvex.core.android.runtime.pi.PiChatBridge =
        com.anvex.core.android.runtime.pi.PiChatBridge(app, piEngine)

    private val reliabilityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        reliabilityScope.launch {
            reliability.markInterruptedTasksResumable()
            // Register every durable handler before the first drain. Otherwise a persisted
            // task can be observed as QUEUED after process death and be failed permanently
            // as "No handler registered" before its feature is wired.
            agentIdeBridge.registerBuildHandler(projects)
            try {
                providers.migrateLegacyHeaders()
            } catch (_: Exception) {
                reliability.log("security", "WARN", "Legacy provider header migration could not complete.")
            }
            taskEngine.drain()
            approvals.purgeExpired()
            val persistedSettings = settings.settings.first()
            if (persistedSettings.lanEnabled) runCatching { webServer.start(persistedSettings.webPort) }
            
            reliability.registerBuiltInPlugins()
            val latest = reliability.latestAutomaticBackup()
            if (latest == null || System.currentTimeMillis() - latest.createdAt >= 6L * 60 * 60 * 1_000) {
                reliability.createAutomaticSnapshot("automatic-startup")
            }
            // Process-independent scheduling is owned by the app AlarmManager receiver.
        }
    }

    suspend fun fallbackAiClient(): com.anvex.core.ai.ProviderClient? {
        val configs = providers.observeAll().first()
        val ordered = configs.sortedWith(compareByDescending<com.anvex.core.model.ProviderConfig> { it.isDefault }.thenBy { it.createdAt })
        val clients = ordered.mapNotNull { cfg -> providers.runtimeConfig(cfg.id)?.let { com.anvex.core.ai.ProviderClientFactory.create(it) } }
        return clients.takeIf { it.isNotEmpty() }?.let(::FallbackProviderClient)
    }

    /**
     * v1.1 §11: bundled offline runtime (Babel + React + ReactDOM, MIT) served
     * by the preview server from app assets. Wrapped so failures degrade to
     * "asset not found" (404) instead of crashing the server thread.
     */
    val runtimeAssetLoader: (String) -> ByteArray? = { name ->
        try {
            app.assets.open("runtime/$name").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /** Currently running preview server (started/stopped by the preview screen). */
    @Volatile
    var previewServer: StaticPreviewServer? = null

    // ---- cross-feature shared state --------------------------------------------

    /** Active editor file per project — focuses the AI context (v1.1 §25). */
    private val activeFiles = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun setActiveFile(projectId: String, path: String?) {
        if (path == null) activeFiles.remove(projectId) else activeFiles[projectId] = path
    }

    fun activeFile(projectId: String): String? = activeFiles[projectId]

    /**
     * Journal a user edit (§22/§24). Suspend — called from the editor's IO
     * save coroutine; never blocks the main thread.
     */
    /** Journal synchronous editor tree mutations without blocking on database I/O. */
    fun recordUserTreeMutation(projectId: String, mutation: com.anvex.core.model.FileMutation) {
        reliabilityScope.launch {
            runCatching { mutations.recordAll(projectId, listOf(mutation)) }
        }
    }

    suspend fun recordUserEdit(projectId: String, path: String, before: String?, after: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            mutations.recordAll(
                projectId,
                listOf(
                    com.anvex.core.model.FileMutation(
                        path = path,
                        operation = if (before == null) {
                            com.anvex.core.model.MutationOp.CREATE
                        } else {
                            com.anvex.core.model.MutationOp.MODIFY
                        },
                        before = before,
                        after = after,
                        source = com.anvex.core.model.MutationSource.USER_EDIT,
                    )
                )
            )
        }
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun init(app: Application): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(app).also { instance = it }
            }

        fun get(): AppGraph = requireNotNull(instance) { "AppGraph not initialized" }
    }
}
