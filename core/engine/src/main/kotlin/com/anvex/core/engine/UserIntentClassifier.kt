package com.anvex.core.engine

/**
 * Small deterministic classifier used only to select the execution contract.
 * It never decides WHAT to change; it only distinguishes a conversational
 * request from an explicit request to mutate project files.
 */
object UserIntentClassifier {
    enum class Intent { CONVERSATION, PROJECT_MUTATION }

    private val mutationPatterns = listOf(
        // Persian
        "اصلاح", "اصلاح کن", "اصلاحش", "ویرایش", "ویرایش کن", "تغییر", "تغییر بده",
        "درست کن", "درستش کن", "رفع کن", "برطرف کن", "فیکس", "آپدیت کن", "به‌روزرسانی",
        "بساز", "ایجاد کن", "بنویس", "ذخیره کن", "حذف کن", "پاک کن", "rename", "ری‌نیم",
        "اضافه کن", "اضافه", "جایگزین کن", "منتقل کن", "کپی کن", "فایل سورس", "سورس رو",
        // English mutation verbs. These are intentionally matched as words, not
        // arbitrary substrings, so "what is a build?" never becomes an edit request.
        "fix", "modify", "edit", "change", "update", "create", "build", "write", "save",
        "delete", "remove", "rename", "move", "copy", "replace", "add", "implement",
        "refactor", "patch", "apply", "make this change", "make the change",
    )

    fun classify(text: String): Intent {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return Intent.CONVERSATION
        // Questions and explanation requests remain conversational even when they
        // contain a mutation verb.
        if (normalized.endsWith("؟") || normalized.endsWith("?")) return Intent.CONVERSATION
        if (normalized.matches(Regex("^(چطور|چگونه|چه طور)\\s+.*$")) ||
            normalized.matches(Regex("^how\\s+(do|can|should|to)\\b.*$")) ||
            normalized.matches(Regex("^(what|why|when|where|which)\\b.*$"))) {
            return Intent.CONVERSATION
        }
        return if (mutationPatterns.any { pattern ->
            if (pattern.all { it.code < 128 } && pattern.matches(Regex("^[a-z0-9 .'-]+$"))) {
                if (pattern.contains(' ')) normalized.contains(pattern)
                else Regex("(?<![a-z0-9])${Regex.escape(pattern)}(?![a-z0-9])").containsMatchIn(normalized)
            } else normalized.contains(pattern)
        }) Intent.PROJECT_MUTATION else Intent.CONVERSATION
    }

    fun requiresExecution(text: String): Boolean = classify(text) == Intent.PROJECT_MUTATION

    private val exportKeywords = listOf(
        "zip", "زیپ", "pdf", "پی دی اف", "پی‌دی‌اف", "پیدیاف", "export", "خروجی", "دانلود", "download",
        "فایل فشرده", "بسته بندی", "بسته‌بندی", "package the", "archive", "give me the source", "send me the source", "as a file", "فایل بده", "فایل بفرست",
    )
    private val informationalStarts = Regex("^(چطور|چگونه|چه طور|چرا|چیه|چی هست|what|why|how)\\b.*$")

    /**
     * True when the user wants a downloadable file (ZIP/PDF/…) rather than a project edit.
     * v4.2 fix: "make the app source as a zip" used to be classified as a plain project mutation,
     * which forced file actions and produced an index.html instead of the archive.
     * Polite question forms ("می‌تونی زیپ کنی؟") still count; "how do I make a zip?" does not.
     */
    fun requestsExport(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return false
        if (informationalStarts.matches(normalized)) return false
        return exportKeywords.any { normalized.contains(it) }
    }
}
