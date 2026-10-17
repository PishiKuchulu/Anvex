package com.anvex.core.android.build

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.0 (GOAL 3): the in-app Android build engine — a real Gradle/AGP process
 * runner, not a simulator. It executes the imported toolchain's Gradle
 * distribution against a materialized project workspace and streams every
 * output line to the caller.
 *
 * Two launch modes:
 *  - app-managed (default): `toolchain/gradle/bin/gradle --no-daemon` with
 *    JAVA_HOME/ANDROID_HOME pointed at the imported bundle.
 *  - project wrapper (`useProjectWrapper`): the checked-in `gradlew` is
 *    executable project code, so it additionally requires an explicit
 *    [BuildRequest.riskScanAcknowledged] confirmation (see docs/BUILD.md
 *    "Imported-project trust boundary").
 *
 * Honest reporting: a build is successful only when the process exits 0 AND a
 * non-empty APK file is found under `build/outputs/apk/<variant>/`.
 */
class AndroidBuildEngine(private val toolchain: ToolchainManager) {

    data class BuildRequest(
        val projectDir: File,
        val variant: String,
        val useProjectWrapper: Boolean = false,
        val riskScanAcknowledged: Boolean = false,
    ) {
        init {
            require(variant in VARIANTS) { "Unsupported variant: $variant" }
        }
    }

    data class BuildResult(
        val success: Boolean,
        val apk: File?,
        val error: String?,
        val logTail: List<String>,
    )

    private val cancelled = AtomicBoolean(false)
    @Volatile private var running: Process? = null

    fun requestCancellation() {
        cancelled.set(true)
        running?.destroy()
    }

    /** Durable-task entry point used by AgentIdeBridge (no log streaming). */
    fun build(dir: File, variant: String): BuildResult =
        build(BuildRequest(projectDir = dir, variant = variant)) { }

    /** Full entry point with live log streaming. Runs synchronously — call off the main thread. */
    fun build(request: BuildRequest, onLine: (String) -> Unit): BuildResult {
        val log = ArrayDeque<String>()
        fun line(text: String) {
            if (text.isBlank()) return
            log.addLast(text.take(2_000))
            if (log.size > DEFAULT_MAX_LOG_LINES) log.removeFirst()
            runCatching { onLine(text) }
        }
        cancelled.set(false)
        val task = "assemble" + request.variant.replaceFirstChar { it.uppercase() }

        // ---- resolve launcher + environment ---------------------------------------
        val command: List<String>
        val extraEnv: Map<String, String>
        if (request.useProjectWrapper) {
            if (!request.riskScanAcknowledged) {
                return BuildResult(
                    false, null,
                    "Wrapper builds execute project-controlled code. Acknowledge the build-script risk scan before using ./gradlew.",
                    emptyList(),
                )
            }
            val wrapper = File(request.projectDir, "gradlew")
            if (!wrapper.isFile) {
                return BuildResult(false, null, "This project has no gradlew wrapper.", emptyList())
            }
            wrapper.setExecutable(true)
            command = listOf(wrapper.absolutePath, "--no-daemon", "-p", request.projectDir.absolutePath, task)
            extraEnv = emptyMap()
            line("\$ ./gradlew --no-daemon $task")
        } else {
            val status = toolchain.status()
            if (!status.valid) {
                return BuildResult(
                    false, null,
                    "No valid toolchain imported${if (status.problems.isNotEmpty()) ": " + status.problems.first() else "."}",
                    emptyList(),
                )
            }
            val gradle = toolchain.gradleBinary()
                ?: return BuildResult(false, null, "Imported toolchain has no usable gradle launcher.", emptyList())
            val java = toolchain.javaBinary()
                ?: return BuildResult(false, null, "Imported toolchain has no usable java binary.", emptyList())
            val sdk = toolchain.sdkRoot()
                ?: return BuildResult(false, null, "Imported toolchain has no usable Android SDK.", emptyList())
            val javaHome = java.parentFile?.parentFile
                ?: return BuildResult(false, null, "Imported JDK layout is unexpected.", emptyList())
            command = listOf(gradle.absolutePath, "--no-daemon", "-p", request.projectDir.absolutePath, task)
            extraEnv = buildMap {
                put("JAVA_HOME", javaHome.absolutePath)
                put("ANDROID_HOME", sdk.absolutePath)
                put("ANDROID_SDK_ROOT", sdk.absolutePath)
                put("GRADLE_USER_HOME", File(gradle.parentFile?.parentFile ?: sdk, "caches").absolutePath)
                put("org.gradle.daemon", "false")
            }
            line("\$ app-managed gradle --no-daemon $task (JDK ${status.manifest?.jdkVersion ?: "?"}, ABI ${status.manifest?.abi ?: "?"})")
        }

        // ---- run the process -------------------------------------------------------
        return try {
            val pb = ProcessBuilder(command)
                .directory(request.projectDir)
                .redirectErrorStream(true)
            pb.environment().putAll(System.getenv())
            pb.environment().putAll(extraEnv)
            val proc = pb.start()
            running = proc
            proc.inputStream.bufferedReader().forEachLine { line(it) }
            val exit = proc.waitFor()
            running = null

            if (cancelled.get()) return BuildResult(false, null, "Build cancelled.", log.toList())
            val apk = newestApk(request.projectDir, request.variant)
            when {
                exit == 0 && apk != null && apk.length() > 0 -> {
                    line("APK ready: ${apk.name} (${apk.length() / (1024 * 1024)} MB)")
                    BuildResult(true, apk, null, log.toList())
                }
                exit != 0 -> BuildResult(false, null, "Gradle exited with code $exit.", log.toList())
                apk == null -> BuildResult(
                    false, null,
                    "Build finished but no APK was produced under build/outputs/apk/${request.variant}/.",
                    log.toList(),
                )
                else -> BuildResult(false, null, "Build finished but the APK is empty.", log.toList())
            }
        } catch (e: Exception) {
            running = null
            BuildResult(false, null, e.message ?: e::class.simpleName ?: "Build failed.", log.toList())
        }
    }

    private fun newestApk(projectDir: File, variant: String): File? {
        val direct = File(projectDir, "build/outputs/apk/$variant")
        val roots: List<File> = if (direct.isDirectory) listOf(direct) else {
            // AGP nests outputs under each module (e.g. app/build/outputs/apk/...).
            projectDir.walkTopDown()
                .filter { it.isDirectory && it.path.endsWith("build/outputs/apk/$variant") }
                .toList()
        }
        return roots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension.equals("apk", true) && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
    }

    companion object {
        const val DEFAULT_MAX_LOG_LINES = 2_000
        val VARIANTS = setOf("debug", "release")
    }
}
