package com.anvex.core.engine

object QuickPrompts {
    data class Item(val title: String, val prompt: String)
    val defaults = listOf(
        Item("Responsive", "Make the UI fully responsive for phones, tablets and landscape without breaking existing behavior."),
        Item("Dark mode", "Add a polished dark mode with accessible contrast and persist the user's preference."),
        Item("Write tests", "Inspect the project and add meaningful unit/UI tests for the most important behavior."),
        Item("Find bugs", "Audit the current project for crashes, race conditions, security issues and data-loss risks; fix the highest-impact issues."),
        Item("Refactor", "Refactor safely toward clean, maintainable architecture without changing user-visible behavior."),
        Item("Explain", "Explain the current project architecture and identify the next three highest-value improvements."),
    )
}
