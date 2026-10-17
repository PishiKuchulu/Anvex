package com.anvex.app.runtime

import android.content.Context

/** Non-sensitive durable runtime intent: whether the user asked the Agent to stay active. */
object AgentRuntimeState {
    private const val PREFS = "agent_runtime"
    private const val ENABLED = "enabled"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, value).apply()
}
