package com.anvex.core.android

import com.anvex.core.android.build.AndroidBuildEngine
import com.anvex.core.android.reliability.DurableTaskEngine
import com.anvex.core.engine.AgentOrchestrator
import com.anvex.core.engine.AgentPlanner
import com.anvex.core.engine.ActionDispatcher
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Single integration boundary between the agent runtime and Android IDE operations. */
class AgentIdeBridge(
    private val taskEngine: DurableTaskEngine,
    private val buildEngine: AndroidBuildEngine,
) {
    @Serializable data class BuildPayload(val projectId: String, val variant: String = "debug")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun enqueueBuild(projectId: String, variant: String = "debug"): Long =
        taskEngine.enqueue(
            type = "android-build",
            label = "Build $variant",
            payloadJson = json.encodeToString(BuildPayload(projectId, variant)),
            resumable = true,
            maxAttempts = 2,
        )

    fun registerBuildHandler(projects: com.anvex.core.android.repo.ProjectRepository) {
        taskEngine.register("android-build") { task ->
            val payload = task.payload?.let { json.decodeFromString<BuildPayload>(it) }
                ?: return@register DurableTaskEngine.Result.Failure("Missing build payload")
            task.ensureActive()
            val dir = projects.materializeForBuild(payload.projectId)
            try {
                val result = buildEngine.build(dir, payload.variant)
                if (result.success && result.apk != null) {
                    val artifact = projects.persistBuildArtifact(payload.projectId, payload.variant, result.apk)
                    DurableTaskEngine.Result.Success("APK ready: ${artifact.file.name}")
                } else DurableTaskEngine.Result.Failure(result.error ?: "Android build failed")
            } finally {
                runCatching { projects.cleanupBuildWorkspace(dir) }
            }
        }
    }
}
