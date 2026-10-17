package com.anvex.core.engine

/**
 * Port of bolt.diy `app/types/actions.ts` + `app/types/artifact.ts`
 * (upstream: MIT, StackBlitz Inc. & bolt.diy contributors).
 */
sealed class BoltAction {
    abstract val content: String

    data class FileAction(
        override val content: String,
        val filePath: String,
    ) : BoltAction()

    data class ShellAction(override val content: String) : BoltAction()

    data class StartAction(override val content: String) : BoltAction()

    /** Generic generated-file request executed by the controlled packaging runtime. */
    data class ArtifactAction(
        override val content: String,
        val format: String,
        val outputName: String,
        val paths: List<String> = emptyList(),
        val title: String = "",
    ) : BoltAction()

    /**
     * v4.2 read-only agent tools (`fetch`, `search`, `read`, `list`). They never mutate the
     * project; their textual result is fed back to the model so the agent can act on it.
     * [name] is the tool, [argument] the URL / query / path.
     */
    data class ToolAction(
        val name: String,
        val argument: String,
        override val content: String = "",
    ) : BoltAction()

    /** Unknown / unsupported action types are preserved with their declared type. */
    data class UnknownAction(val type: String, override val content: String) : BoltAction()
}

data class AnvexArtifactData(
    val id: String,
    val title: String,
    val type: String? = null,
)

data class ActionCallbackData(
    val messageId: String,
    val artifactId: String,
    val actionId: String,
    val action: BoltAction,
)

/** `<bolt-quick-action>` buttons, ported from the upstream parser. */
data class QuickAction(
    val label: String,
    val type: String,
    val message: String,
    val path: String,
    val href: String,
)

/** Callbacks emitted by [BoltStreamParser], mirroring `ParserCallbacks` upstream. */
interface ParserCallbacks {
    fun onArtifactOpen(artifact: AnvexArtifactData) {}
    fun onArtifactClose(artifact: AnvexArtifactData) {}
    fun onActionOpen(data: ActionCallbackData) {}
    fun onActionStream(data: ActionCallbackData) {}
    fun onActionClose(data: ActionCallbackData) {}
    fun onQuickActions(actions: List<QuickAction>) {}
}

/** Packs an MCP target (server and tool) into one argument string. The separator never appears in names. */
object McpToolTarget {
    const val SEP = "\u001F"

    fun pack(server: String, tool: String): String = server.trim() + SEP + tool.trim()

    fun unpack(argument: String): Pair<String, String> {
        val index = argument.indexOf(SEP)
        return if (index < 0) argument to "" else argument.substring(0, index) to argument.substring(index + 1)
    }
}
