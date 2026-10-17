package com.anvex.core.engine

/** Static warning layer for imported Gradle projects. It does not execute build scripts. */
object BuildScriptRiskScanner {
    data class Finding(val path: String, val reason: String)
    private val suspicious = listOf(
        Regex("(?i)\\b(?:project\\.)?exec\\s*\\{"),
        Regex("(?i)Runtime\\.getRuntime\\(\\)\\.exec"),
        Regex("(?i)ProcessBuilder\\("),
        Regex("(?i)commandLine\\("),
        Regex("(?i)JavaExec|Exec\\("),
        Regex("(?i)curl\\s+|wget\\s+"),
        Regex("(?i)java\\.net\\.(?:URL|HttpURLConnection|Socket)"),
        Regex("(?i)File\\([^)]*\\)\\.delete(?:Recursively)?\\("),
        Regex("(?i)Class\\.forName\\("),
    )
    fun scan(path: String, content: String): List<Finding> {
        if (!path.endsWith(".gradle") && !path.endsWith(".gradle.kts") && !path.endsWith("settings.gradle") && !path.endsWith("settings.gradle.kts")) return emptyList()
        return suspicious.filter { it.containsMatchIn(content) }.map { Finding(path, "Build script contains a command/network execution pattern: ${it.pattern}") }
    }
}
