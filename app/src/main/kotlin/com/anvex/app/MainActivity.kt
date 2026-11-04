package com.anvex.app

import com.anvex.app.share.IncomingShare
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.LayoutDirection
import com.anvex.app.ui.AnvexApp
import com.anvex.app.ui.theme.AnvexTheme
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.ThemeMode
import com.anvex.core.android.repo.AppLanguage

private const val MAX_SHARE_BYTES = 20L * 1024 * 1024

/** 2026-11-04 (external-audit wave): aggregate cap across one whole share. */
private const val MAX_TOTAL_SHARE_BYTES = 100L * 1024 * 1024

/**
 * Single-activity Compose shell.
 *
 * v4.0 audit fix (locale apply race — round 3): the previous once-per-instance
 * [AtomicBoolean] guard consumed the FIRST effect run, which usually fired with
 * the null initialValue (SYSTEM) before DataStore loaded — so a persisted
 * PERSIAN choice was never applied: the UI flipped to RTL via the Compose
 * layout direction, but [com.anvex.app.i18n.tr] kept reading an English
 * configuration ("RTL frame, English text"). The apply now tracks the last
 * value that was actually applied and re-runs whenever the REAL settings
 * value differs; [applyLanguage] itself remains idempotent (equality checks
 * on both API paths) so recreations stay bounded and the historic infinite
 * recreate loop cannot return.
 */
class MainActivity : ComponentActivity() {

    /** The language actually applied to the platform for this instance (null = not yet). */
    private var lastAppliedLanguage: AppLanguage? = null

    /** 2026-11-04 (audit wave): splash stays up until the first real
     * settings value lands, replacing the main-thread runBlocking read. */
    @Volatile
    private var settingsReady = false

    // M-11: the deprecated requestPermissions(int) path is replaced by the
    // Activity Result API. The grant is best-effort (a denied notification
    // permission only hides the agent notification), so no result handling.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 2026-11-04 (audit wave): keep the activity's intent current —
        // without setIntent, a later recreation re-read the FIRST
        // intent (and could re-offer a stale share).
        setIntent(intent)
        handleShare(intent)
    }

    /** Phase 3.2: text and files from the share sheet. Files are copied into the app cache. */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val streams: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            val single: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            listOfNotNull(single)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // 2026-11-04 (external-audit wave): three fixes —
            //  (a) an AGGREGATE cap across the whole share: the old code
            //      capped each file at 20 MB but a SEND_MULTIPLE with
            //      many files could still fill the cache unbounded;
            //  (b) a payload with no text and no copied file is no
            //      longer offered (it surfaced as an empty share chip);
            //  (c) URI creation failures delete the orphan copy.
            val copied = ArrayList<Uri>()
            var totalBytes = 0L
            for (stream in streams) {
                if (totalBytes >= MAX_TOTAL_SHARE_BYTES) break
                val file = copyToCache(stream) ?: continue
                totalBytes += file.length()
                if (totalBytes > MAX_TOTAL_SHARE_BYTES) {
                    file.delete()
                    break
                }
                val uri = runCatching {
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                }.getOrNull()
                if (uri != null) copied += uri else file.delete()
            }
            if (text != null || copied.isNotEmpty()) {
                IncomingShare.offer(IncomingShare.Payload(text, copied))
            }
        }
    }

    private fun copyToCache(source: Uri): java.io.File? = runCatching {
        val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
        val displayName = contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "shared-${System.currentTimeMillis()}"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val target = java.io.File(dir, "${java.util.UUID.randomUUID()}-$safeName")
        // M-11: the old code copied the whole stream and only THEN checked
        // the 20 MB cap — a hostile share could fill the cache/disk first.
        // Bytes are counted DURING the copy; the cap aborts mid-stream and
        // the partial file is deleted.
        val input = contentResolver.openInputStream(source) ?: return@runCatching null
        try {
            input.use { inputStream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SHARE_BYTES) {
                            throw ShareTooLargeException()
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: ShareTooLargeException) {
            target.delete()
            return@runCatching null
        }
        // 2026-11-04: returns the copied File; the caller enforces the
        // aggregate cap and mints the FileProvider URI (so an over-cap or
        // unsharable copy can be deleted rather than orphaned).
        target
    }.getOrNull()

    private class ShareTooLargeException : Exception()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 2026-11-04 (external-audit wave): M-11's first-frame fix read the
        // persisted settings with runBlocking on the main thread inside
        // composition — correct frame, wrong mechanism. The splash screen
        // now covers the wait: it is held until the first real settings
        // value arrives (settingsReady), so composition never blocks and
        // frame one is still the persisted theme/language. A 3 s safety
        // releases the splash even if the settings store fails.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !settingsReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Recreations (rotation, locale) must not re-offer the same share.
        if (savedInstanceState == null) handleShare(intent)
        // v4.4 (architect review item 12): Android 13+ needs an explicit
        // POST_NOTIFICATIONS grant for the agent notification to be visible.
        // 2026-11-04 (audit wave): ask only while the system would still
        // show the dialog for a first ask — once the user has denied,
        // shouldShowRequestPermissionRationale turns true and re-asking
        // on every start was pure nagging (the system silently denies).
        // The recovery path lives in Settings (app notification settings).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val graph = AppGraph.get()
        lifecycleScope.launch {
            runCatching { graph.settings.settings.first() }
            settingsReady = true
        }
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3_000)
            settingsReady = true
        }
        // 2026-11-04 (audit wave): share copies in cache/shared used to
        // live forever (the update controller sweeps only its own
        // anvex-update-* files). Sweep share copies older than 7 days;
        // anything still pending consumption is fresh by definition.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(cacheDir, "shared")
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                dir.listFiles()?.forEach { f ->
                    if (!f.name.startsWith("anvex-update-") && f.lastModified() < cutoff) f.delete()
                }
            }
        }
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            androidx.compose.runtime.LaunchedEffect(settings?.language) {
                // Wait for the REAL settings value — the null initial emission must not
                // consume the apply (that was the round-2 bug).
                val language = settings?.language ?: return@LaunchedEffect
                if (language != lastAppliedLanguage) {
                    lastAppliedLanguage = language
                    applyLanguage(language)
                }
            }
            val rtl = when (settings?.language ?: AppLanguage.SYSTEM) {
                AppLanguage.PERSIAN -> true
                AppLanguage.ENGLISH -> false
                // SYSTEM: derive from the resolved locale the platform actually gave us.
                AppLanguage.SYSTEM -> isResolvedLocaleRtl()
            }
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                com.anvex.app.i18n.LocalAppLanguage provides when (settings?.language ?: AppLanguage.SYSTEM) {
                    AppLanguage.PERSIAN -> "fa"
                    AppLanguage.ENGLISH -> "en"
                    AppLanguage.SYSTEM -> null
                },
            ) {
            AnvexTheme(
                // M-11: the first frame (settings == null, DataStore not
                // loaded yet) must match the stored default DARK, not
                // SYSTEM — the old fallback flashed the system theme on
                // every cold start before the real value arrived.
                dark = when (settings?.themeMode ?: ThemeMode.DARK) {
                    ThemeMode.SYSTEM -> null
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = settings?.dynamicColor ?: false,
                themeStyle = settings?.uiThemeStyle,
                atelierAccent = settings?.atelierAccent
                    ?: com.anvex.core.android.repo.AtelierAccent.ORANGE,
            ) {
                AnvexApp()
            }
            }
        }
    }

    /**
     * API 33+: delegate to the system LocaleManager (no recreate — the platform recreates
     * the activity itself only when the per-app locale actually changes).
     * API 26-32: apply the legacy resource configuration exactly once per instance and
     * only recreate when the requested locale differs from the current configuration.
     */
    private fun applyLanguage(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java) ?: return
            val tags = when (language) {
                AppLanguage.PERSIAN -> "fa"
                AppLanguage.ENGLISH -> "en"
                AppLanguage.SYSTEM -> ""
            }
            val desired = LocaleList.forLanguageTags(tags)
            if (localeManager.applicationLocales != desired) localeManager.applicationLocales = desired
        } else {
            val requested = when (language) {
                AppLanguage.PERSIAN -> java.util.Locale("fa")
                AppLanguage.ENGLISH -> java.util.Locale("en")
                AppLanguage.SYSTEM -> null // follow the system configuration as-is
            }
            val current = resources.configuration.locales[0]
            if (requested == null || current.language == requested.language) return
            @Suppress("DEPRECATION")
            java.util.Locale.setDefault(requested)
            val config = resources.configuration
            config.setLocale(requested)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
            // The guard + equality check makes this a bounded, single recreation.
            recreate()
        }
    }

    private fun isResolvedLocaleRtl(): Boolean {
        val locale = resources.configuration.locales[0]
        return locale.language == "fa" || locale.language == "ar" || locale.language == "he" || locale.language == "ur"
    }
}
