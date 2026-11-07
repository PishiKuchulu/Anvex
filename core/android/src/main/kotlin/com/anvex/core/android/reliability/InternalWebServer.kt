package com.anvex.core.android.reliability

/** Internal authenticated LAN web surface. Mutation endpoints are intentionally absent. */
class InternalWebServer(
    private val lan: LanAccessManager,
    private val statusProvider: () -> String,
    private val tasksProvider: () -> List<com.anvex.core.android.db.TaskRunEntity> = { emptyList() },
    private val logsProvider: () -> List<com.anvex.core.android.db.DiagnosticEventEntity> = { emptyList() },
) {
    fun start(port: Int, exposeLan: Boolean = false, htmlProvider: (() -> String)? = null): LanAccessManager.Status {
        require(port in 1024..65535)
        return lan.start(
            statusProvider = statusProvider,
            requestedPort = port,
            // LOG-003 (forensic audit, 2026-11-07): the persisted
            // auto-start used to hard-code loopback-only while the
            // Developer Center start honored the user's LAN toggle —
            // the persisted preference now drives both paths.
            exposeOnLan = exposeLan,
            htmlProvider = htmlProvider,
            tasksProvider = tasksProvider,
            logsProvider = logsProvider,
        )
    }
    fun stop() = lan.stop()
    fun isRunning() = lan.status().running
}
