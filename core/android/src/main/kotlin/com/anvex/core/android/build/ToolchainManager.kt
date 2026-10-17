package com.anvex.core.android.build

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v4.0 (GOAL 3): manages the imported on-device build toolchain.
 *
 * The toolchain is a user-imported ZIP bundle containing an Android-compatible
 * JDK/runtime built for the phone ABI, an Android SDK slice and a Gradle
 * distribution. A normal desktop (glibc) JDK is NOT a valid Android toolchain
 * and is rejected by the manifest check (`platform: "android"` + non-empty ABI).
 *
 * Expected bundle layout (validated by [importBundle]):
 * ```
 *   toolchain.json                  (manifest, required)
 *   jdk/bin/java[.exe]              (required, executable)
 *   sdk/platforms/android-N         (required, at least one android-N platform)
 *   sdk/build-tools/N               (required, at least one version dir)
 *   gradle/bin/gradle[.bat]         (required, executable)
 * ```
 *
 * Storage lives under `filesDir/toolchain`. Nothing here is ever executed at
 * import time; execution happens only in [AndroidBuildEngine] when the user
 * explicitly starts a build.
 */
class ToolchainManager(private val context: Context) {

    @Serializable
    data class Manifest(
        val platform: String = "",
        val abi: String = "",
        val jdkVersion: String = "",
        val gradleVersion: String = "",
        val apiLevel: Int = 0,
    )

    data class ToolchainStatus(
        val valid: Boolean,
        val problems: List<String>,
        val manifest: Manifest?,
    ) {
        /** v4.4: pristine HarnessBridge/Reliability call sites use both spellings. */
        val installed: Boolean get() = valid
        val isInstalled: Boolean get() = valid
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val root: File get() = File(context.filesDir, "toolchain")

    /** Directory layout inside the imported bundle (after extraction). */
    private val jdkDir: File get() = File(root, "jdk")
    private val sdkDir: File get() = File(root, "sdk")
    private val gradleDir: File get() = File(root, "gradle")

    /** Current status of the imported toolchain, with concrete problems when invalid. */
    fun status(): ToolchainStatus {
        if (!root.isDirectory) return ToolchainStatus(false, listOf("No toolchain imported."), null)
        val manifestFile = File(root, "toolchain.json")
        if (!manifestFile.isFile) {
            return ToolchainStatus(false, listOf("toolchain.json is missing from the imported bundle."), null)
        }
        val manifest = runCatching { json.decodeFromString<Manifest>(manifestFile.readText()) }
            .getOrElse {
                return ToolchainStatus(false, listOf("toolchain.json is not valid JSON."), null)
            }
        val problems = buildList {
            if (manifest.platform.trim().lowercase() != "android") {
                add("Manifest platform must be \"android\" (found \"${manifest.platform}\").")
            }
            if (manifest.abi.isBlank()) add("Manifest ABI must not be empty.")
            val javaBin = binFile(jdkDir, "java")
            if (!javaBin.isFile || !javaBin.canExecute()) add("jdk/bin/java is missing or not executable.")
            val platforms = sdkDir.resolve("platforms").listFiles { f -> f.isDirectory }
                ?.filter { it.name.startsWith("android-") }.orEmpty()
            if (platforms.isEmpty()) add("sdk/platforms contains no android-* platform.")
            val buildTools = sdkDir.resolve("build-tools").listFiles { f -> f.isDirectory }.orEmpty()
            if (buildTools.isEmpty()) add("sdk/build-tools is empty.")
            val gradleBin = binFile(gradleDir, "gradle")
            if (!gradleBin.isFile || !gradleBin.canExecute()) add("gradle/bin/gradle is missing or not executable.")
        }
        return ToolchainStatus(problems.isEmpty(), problems, manifest)
    }

    /** True when a valid toolchain bundle is installed. */
    fun isInstalled(): Boolean = status().valid

    /**
     * Import a toolchain bundle from an already-extracted staging directory.
     * The staged tree is validated first; only a fully valid bundle replaces
     * the current one. The staging directory itself is owned by the caller.
     */
    fun importBundle(staged: File): ToolchainStatus {
        val manifestFile = File(staged, "toolchain.json")
        if (!manifestFile.isFile) {
            return ToolchainStatus(false, listOf("The selected bundle has no toolchain.json manifest."), null)
        }
        val manifest = runCatching { json.decodeFromString<Manifest>(manifestFile.readText()) }
            .getOrElse { return ToolchainStatus(false, listOf("toolchain.json is not valid JSON."), null) }

        // Validate the staged bundle against the same rules as status().
        val problems = buildList {
            if (manifest.platform.trim().lowercase() != "android") add("Manifest platform must be \"android\".")
            if (manifest.abi.isBlank()) add("Manifest ABI must not be empty.")
            if (!binFile(File(staged, "jdk"), "java").isFile) add("jdk/bin/java is missing in the bundle.")
            (File(staged, "sdk/platforms").listFiles { f -> f.isDirectory && f.name.startsWith("android-") }?.isEmpty()
                ?: true).let { if (it) add("sdk/platforms contains no android-* platform.") }
            if (File(staged, "sdk/build-tools").listFiles { f -> f.isDirectory }.isNullOrEmpty()) add("sdk/build-tools is empty.")
            if (!binFile(File(staged, "gradle"), "gradle").isFile) add("gradle/bin/gradle is missing in the bundle.")
        }
        if (problems.isNotEmpty()) return ToolchainStatus(false, problems, manifest)

        // Replace the current toolchain: old tree out, staged tree in.
        root.deleteRecursively()
        root.mkdirs()
        listOf("jdk", "sdk", "gradle").forEach { section ->
            File(staged, section).takeIf { it.isDirectory }?.let { src ->
                src.copyRecursively(File(root, section), overwrite = true)
            }
        }
        File(staged, "toolchain.json").copyTo(File(root, "toolchain.json"), overwrite = true)
        return status()
    }

    /** Remove the imported toolchain entirely. */
    fun remove() {
        root.deleteRecursively()
    }

    /** Absolute java binary of the imported JDK, or null when absent. */
    fun javaBinary(): File? = binFile(jdkDir, "java").takeIf { it.isFile && it.canExecute() }

    /** Absolute gradle launcher of the imported distribution, or null when absent. */
    fun gradleBinary(): File? = binFile(gradleDir, "gradle").takeIf { it.isFile && it.canExecute() }

    /** Imported SDK root, or null when absent. */
    fun sdkRoot(): File? = sdkDir.takeIf { it.isDirectory }

    private fun binFile(parent: File, name: String): File =
        File(parent, "bin/$name").takeIf { it.isFile } ?: File(parent, "bin/$name.exe")
}
