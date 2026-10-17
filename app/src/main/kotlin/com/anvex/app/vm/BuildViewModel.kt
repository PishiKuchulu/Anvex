package com.anvex.app.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.android.AppGraph
import com.anvex.core.android.build.AndroidBuildEngine
import com.anvex.core.android.build.SafeDirUnzip
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.android.repo.CloudBuildRepository
import com.anvex.core.android.repo.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v4.0 (GOAL 3): state holder for the in-app Android build surface.
 * Streams real Gradle output lines while a build runs and exposes the
 * imported-toolchain status honestly.
 */
class BuildViewModel(
    val projectId: String,
) : ViewModel() {

    private val graph = AppGraph.get()

    val project: StateFlow<com.anvex.core.model.Project?> = graph.projects.observeAll()
        .map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _toolchainStatus = MutableStateFlow(graph.toolchain.status())
    val toolchainStatus: StateFlow<com.anvex.core.android.build.ToolchainManager.ToolchainStatus> = _toolchainStatus

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines

    private val _building = MutableStateFlow(false)
    val building: StateFlow<Boolean> = _building

    private val _buildError = MutableStateFlow<String?>(null)
    val buildError: StateFlow<String?> = _buildError

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage

    private val _artifacts = MutableStateFlow<List<ProjectRepository.BuildArtifact>>(emptyList())
    val artifacts: StateFlow<List<ProjectRepository.BuildArtifact>> = _artifacts

    private val _useWrapper = MutableStateFlow(false)
    val useWrapper: StateFlow<Boolean> = _useWrapper

    private val _wrapperRiskAcknowledged = MutableStateFlow(false)
    val wrapperRiskAcknowledged: StateFlow<Boolean> = _wrapperRiskAcknowledged

    init {
        refresh()
    }

    fun refresh() {
        _toolchainStatus.value = graph.toolchain.status()
        _artifacts.value = graph.projects.listBuildArtifacts(projectId)
    }

    fun setUseWrapper(v: Boolean) { _useWrapper.value = v }
    fun setWrapperRiskAcknowledged(v: Boolean) { _wrapperRiskAcknowledged.value = v }

    /** Import a toolchain bundle ZIP chosen through SAF. */
    fun importToolchain(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _importMessage.value = "Extracting toolchain bundle…"
            val staged = File(graph.context.cacheDir, "toolchain-import").apply { deleteRecursively(); mkdirs() }
            try {
                val count = graph.context.contentResolver.openInputStream(uri)?.use { input ->
                    SafeDirUnzip.extract(input, staged)
                } ?: error("Could not read the selected bundle.")
                val status = graph.toolchain.importBundle(staged)
                _toolchainStatus.value = status
                _importMessage.value = if (status.valid) {
                    "Toolchain imported ($count files). JDK ${status.manifest?.jdkVersion ?: "?"}, Gradle ${status.manifest?.gradleVersion ?: "?"}, ABI ${status.manifest?.abi ?: "?"}."
                } else {
                    "Import failed: ${status.problems.joinToString("; ")}"
                }
            } catch (e: Exception) {
                _importMessage.value = "Import failed: ${e.message ?: e::class.simpleName}"
            } finally {
                staged.deleteRecursively()
            }
        }
    }

    fun removeToolchain() {
        viewModelScope.launch(Dispatchers.IO) {
            graph.toolchain.remove()
            _toolchainStatus.value = graph.toolchain.status()
            _importMessage.value = "Toolchain removed."
        }
    }

    fun startBuild(variant: String) {
        if (_building.value) return
        _building.value = true
        _buildError.value = null
        _logLines.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            var workspace: File? = null
            try {
                val dir = graph.projects.materializeForBuild(projectId)
                workspace = dir
                val request = AndroidBuildEngine.BuildRequest(
                    projectDir = dir,
                    variant = variant,
                    useProjectWrapper = _useWrapper.value,
                    riskScanAcknowledged = _wrapperRiskAcknowledged.value,
                )
                appendLog("\$ ${if (request.useProjectWrapper) "./gradlew" else "app-managed gradle"} --no-daemon assemble${variant.replaceFirstChar { it.uppercase() }}")
                val result = graph.androidBuildEngine.build(request) { line ->
                    appendLog(line)
                }
                val apkFile = result.apk
                if (result.success && apkFile != null) {
                    val artifact = graph.projects.persistBuildArtifact(projectId, variant, apkFile)
                    appendLog("APK ready: ${artifact.file.name} (${artifact.file.length() / (1024 * 1024)} MB)")
                } else {
                    _buildError.value = result.error ?: "Build failed."
                    result.logTail.takeLast(30).forEach(::appendLog)
                }
            } catch (e: Exception) {
                _buildError.value = e.message ?: e::class.simpleName ?: "Build failed."
            } finally {
                workspace?.let { runCatching { graph.projects.cleanupBuildWorkspace(it) } }
                withContext(Dispatchers.Main) { _building.value = false }
                refresh()
            }
        }
    }

    fun cancelBuild() {
        graph.androidBuildEngine.requestCancellation()
        appendLog("^C cancellation requested…")
    }

    fun deleteArtifact(artifact: ProjectRepository.BuildArtifact) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { artifact.file.delete() }
            refresh()
        }
    }

    private fun appendLog(line: String) {
        _logLines.value = (_logLines.value + line).takeLast(AndroidBuildEngine.DEFAULT_MAX_LOG_LINES)
    }

    // ---- cloud build (GitHub Actions) ------------------------------------

    private val _cloudBusy = MutableStateFlow(false)
    val cloudBusy: StateFlow<Boolean> = _cloudBusy

    private val _cloudStatus = MutableStateFlow<String?>(null)
    val cloudStatus: StateFlow<String?> = _cloudStatus

    private val _cloudError = MutableStateFlow<String?>(null)
    val cloudError: StateFlow<String?> = _cloudError

    private val _cloudRun = MutableStateFlow<CloudBuildRepository.RunInfo?>(null)
    val cloudRun: StateFlow<CloudBuildRepository.RunInfo?> = _cloudRun

    private val _cloudLogLines = MutableStateFlow<List<String>>(emptyList())
    val cloudLogLines: StateFlow<List<String>> = _cloudLogLines

    /** Variant dispatched this session; used to label the downloaded artifact. */
    private var cloudVariant: String = "debug"
    private var cloudJob: kotlinx.coroutines.Job? = null

    fun hasGithubToken(): Boolean = graph.github.hasToken()

    /** Saves the shared GitHub token from the Build screen (same Keystore-encrypted store as GitHub Sync). */
    fun saveGithubToken(raw: String): String = try {
        graph.github.saveToken(raw)
        "Token saved securely. Cloud build is ready."
    } catch (e: Exception) {
        e.message ?: "Could not save the token."
    }

    /**
     * One-click cloud build: push the project + workflow file, dispatch the
     * workflow and poll the new run every 5s (max 40 attempts). Every state
     * shown to the user comes verbatim from the GitHub Actions API — nothing
     * is fabricated. The run created by this dispatch is identified by a
     * baseline id captured before the push (the push itself may also trigger
     * unrelated CI workflows on the same branch).
     */
    fun pushAndBuild(owner: String, repo: String, branch: String, variant: String) {
        if (_cloudBusy.value) return
        cloudVariant = variant.trim().lowercase().take(50)
        _cloudBusy.value = true
        _cloudError.value = null
        _cloudStatus.value = null
        _cloudRun.value = null
        _cloudLogLines.value = emptyList()
        cloudJob = viewModelScope.launch(Dispatchers.IO) {
            var workspace: File? = null
            try {
                val baseline = graph.cloudBuild.latestRun(owner, repo, branch, CloudBuildRepository.WORKFLOW_PATH)
                    .getOrNull()?.id ?: 0L
                _cloudStatus.value = "Pushing project to GitHub…"
                // materializeForBuild gives a local copy (SAF projects) with
                // build outputs excluded — exactly what should be pushed.
                val dir = graph.projects.materializeForBuild(projectId)
                workspace = dir
                val pushed = graph.github.push(RealProjectFileSystem(dir), owner, repo, branch, "Anvex cloud build push")
                    .getOrElse { error(it.message ?: "GitHub push failed.") }
                _cloudStatus.value = "Pushed $pushed file(s). Checking workflow file…"
                graph.cloudBuild.ensureWorkflowFile(owner, repo, branch)
                    .getOrElse { error(it.message ?: "Could not publish the workflow file.") }
                _cloudStatus.value = "Dispatching workflow…"
                graph.cloudBuild.dispatchWorkflow(owner, repo, CloudBuildRepository.WORKFLOW_FILE, branch, mapOf("variant" to cloudVariant))
                    .getOrElse { error(it.message ?: "Workflow dispatch failed.") }
                var run: CloudBuildRepository.RunInfo? = null
                for (attempt in 1..CLOUD_MAX_POLLS) {
                    delay(CLOUD_POLL_INTERVAL_MS)
                    val current = graph.cloudBuild.latestRun(owner, repo, branch, CloudBuildRepository.WORKFLOW_PATH).getOrNull()
                    if (current != null && current.id > baseline) {
                        run = current
                        _cloudRun.value = current
                        _cloudStatus.value = describeRun(current) + " — poll $attempt/$CLOUD_MAX_POLLS"
                        if (current.status.equals("completed", ignoreCase = true)) break
                    } else {
                        _cloudStatus.value = "Waiting for the workflow run to appear… (poll $attempt/$CLOUD_MAX_POLLS)"
                    }
                }
                val finished = run
                if (finished == null) {
                    _cloudStatus.value = null
                    _cloudError.value = "No new workflow run appeared within the polling window. Open the repository on GitHub to check."
                } else {
                    applyRunOutcome(finished, exhaustedPolls = !finished.status.equals("completed", ignoreCase = true))
                }
            } catch (e: CancellationException) {
                _cloudStatus.value = "Cloud build watch stopped."
            } catch (e: Exception) {
                _cloudStatus.value = null
                _cloudError.value = e.message ?: e::class.simpleName ?: "Cloud build failed."
            } finally {
                workspace?.let { runCatching { graph.projects.cleanupBuildWorkspace(it) } }
                _cloudBusy.value = false
            }
        }
    }

    /** Stops the in-flight cloud watch (push/dispatch finish at their next suspension point). */
    fun stopCloudWatch() {
        cloudJob?.cancel()
        if (_cloudBusy.value) {
            _cloudBusy.value = false
            _cloudStatus.value = "Stopped."
        }
    }

    /** One-shot status refresh — used after the polling window has ended. */
    fun refreshCloudRun(owner: String, repo: String, branch: String) {
        if (_cloudBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            graph.cloudBuild.latestRun(owner, repo, branch, CloudBuildRepository.WORKFLOW_PATH).fold(
                onSuccess = { run ->
                    if (run == null) {
                        _cloudError.value = "No workflow run found for this branch yet."
                    } else {
                        applyRunOutcome(run, exhaustedPolls = false)
                    }
                },
                onFailure = { e ->
                    _cloudError.value = e.message ?: "Could not refresh the run status."
                },
            )
        }
    }

    /** Fetches the run log tail (last 200 lines); also works while the run is in progress. */
    fun tailCloudLog(owner: String, repo: String) {
        val run = _cloudRun.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _cloudError.value = null
            val text = graph.cloudBuild.fetchLogTail(owner, repo, run.id).getOrElse { e ->
                _cloudError.value = e.message ?: "Could not fetch the run log."
                return@launch
            }
            if (text.isBlank()) {
                _cloudStatus.value = "Run log is empty — the workflow may not have produced output yet."
                _cloudLogLines.value = emptyList()
            } else {
                _cloudLogLines.value = text.lines().takeLast(CLOUD_LOG_MAX_LINES)
            }
        }
    }

    /** Downloads the APK artifact of the tracked run and persists it in the artifacts list. */
    fun downloadCloudApk(owner: String, repo: String) {
        val run = _cloudRun.value ?: return
        if (_cloudBusy.value) return
        _cloudBusy.value = true
        _cloudError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val staging = File(graph.context.cacheDir, "cloud-apk/${run.id}").apply { deleteRecursively(); mkdirs() }
            try {
                _cloudStatus.value = "Downloading APK artifact…"
                val apk = graph.cloudBuild.downloadArtifactApk(owner, repo, run.id, staging)
                    .getOrElse { error(it.message ?: "APK download failed.") }
                val artifact = graph.projects.persistBuildArtifact(projectId, cloudVariant.ifBlank { "debug" }, apk)
                _cloudStatus.value = "APK saved: ${artifact.file.name}"
                refresh()
            } catch (e: Exception) {
                _cloudStatus.value = null
                _cloudError.value = e.message ?: e::class.simpleName ?: "APK download failed."
            } finally {
                runCatching { staging.deleteRecursively() }
                _cloudBusy.value = false
            }
        }
    }

    /** Status line from the API verbatim — completed runs also show their conclusion. */
    private fun describeRun(run: CloudBuildRepository.RunInfo): String =
        if (run.status.equals("completed", ignoreCase = true)) {
            "Run #${run.id}: ${run.conclusion ?: "completed"}"
        } else {
            "Run #${run.id}: ${run.status}"
        }

    private fun applyRunOutcome(run: CloudBuildRepository.RunInfo, exhaustedPolls: Boolean) {
        _cloudRun.value = run
        val completed = run.status.equals("completed", ignoreCase = true)
        val success = completed && run.conclusion.equals("success", ignoreCase = true)
        when {
            success -> _cloudStatus.value = "Cloud build succeeded (run #${run.id}). Download the APK below."
            completed -> {
                _cloudStatus.value = describeRun(run)
                _cloudError.value = "Cloud build finished with conclusion '${run.conclusion ?: "unknown"}'. Tail the log for details."
            }
            else -> _cloudStatus.value = describeRun(run) + if (exhaustedPolls) {
                " — still running after the polling window; open it on GitHub to watch live."
            } else ""
        }
    }

    private companion object {
        const val CLOUD_MAX_POLLS = 40
        const val CLOUD_POLL_INTERVAL_MS = 5000L
        const val CLOUD_LOG_MAX_LINES = 200
    }
}
