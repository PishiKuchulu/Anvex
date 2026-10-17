package com.anvex.core.model

import kotlinx.serialization.Serializable

/**
 * Project types supported by the Android runtime.
 * Tiering follows docs/audit/RUNTIME_ANALYSIS.md.
 */
enum class ProjectType(val displayName: String, val previewTier: PreviewTier) {
    STATIC_HTML("Static HTML/CSS/JS", PreviewTier.SUPPORTED_OFFLINE),
    VANILLA_JS("JavaScript (vanilla)", PreviewTier.SUPPORTED_OFFLINE),
    REACT("React (JS/JSX/TSX)", PreviewTier.SUPPORTED_ONLINE),
    TYPESCRIPT("TypeScript (browser-runnable)", PreviewTier.SUPPORTED_ONLINE),
    VITE("Vite / bundler project", PreviewTier.PARTIALLY),
    UNKNOWN("Unknown", PreviewTier.PARTIALLY);

    companion object {
        fun infer(files: Collection<String>): ProjectType {
            val hasPackageJson = files.any { it == "package.json" }
            val hasHtml = files.any { it.equals("index.html", ignoreCase = true) || it.endsWith(".html") }
            val hasJsx = files.any { it.endsWith(".jsx") || it.endsWith(".tsx") }
            return when {
                hasJsx -> REACT
                hasHtml -> STATIC_HTML
                hasPackageJson -> VITE
                files.any { it.endsWith(".ts") } -> TYPESCRIPT
                files.any { it.endsWith(".js") } -> VANILLA_JS
                else -> UNKNOWN
            }
        }
    }
}

enum class PreviewTier(val label: String) {
    SUPPORTED_OFFLINE("Supported (offline preview)"),
    SUPPORTED_ONLINE("Supported (online CDN preview)"),
    PARTIALLY("Partially supported (files only)");
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val type: ProjectType = ProjectType.UNKNOWN,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val description: String = "",
    /** Total size in bytes of the project directory, refreshed on write. */
    val sizeBytes: Long = 0L,
    /** Persisted Android Storage Access Framework tree URI. Null means app-private workspace. */
    val workspaceUri: String? = null,
    /** True only when Anvex created this SAF directory and therefore owns its contents. */
    val workspaceOwnedByApp: Boolean = false,
)

data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val children: List<FileNode> = emptyList(),
)
