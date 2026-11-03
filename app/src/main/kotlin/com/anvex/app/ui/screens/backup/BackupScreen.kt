package com.anvex.app.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Arrays
import com.anvex.app.i18n.tr

/**
 * v4.0 audit fix (password hygiene): the backup password is kept as a [CharArray] in
 * composition state instead of a [String] so every consumer can zero it
 * (`Arrays.fill('0')`) after export/inspection/restore. The text field itself works on
 * the minimum transient String the Compose text pipeline requires, but no long-lived
 * String copy of the secret is retained by this screen.
 */
private class PasswordState(initial: String = "") {
    var chars: CharArray = initial.toCharArray()
        private set

    fun update(text: String) {
        // Zero the previous buffer before replacing it.
        Arrays.fill(chars, '0')
        chars = text.toCharArray()
    }

    fun snapshot(): CharArray = chars.copyOf()

    fun clear() {
        Arrays.fill(chars, '0')
        chars = CharArray(0)
    }
}

/**
 * UI #5: backup status is TYPED — the old code colored the status line by
 * matching English prefixes ("Export failed…") on a raw String, which
 * silently broke in the Persian UI. Color now derives from the type.
 */
private sealed interface BackupStatus {
    val text: String
    data class Success(override val text: String) : BackupStatus
    data class Info(override val text: String) : BackupStatus
    data class Partial(override val text: String) : BackupStatus
    data class Failure(override val text: String) : BackupStatus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(nav: NavController) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()
    val password = remember { PasswordState() }
    var passwordField by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<BackupStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<com.anvex.core.android.PortableBackup.Payload?>(null) }
    // Status strings resolved in composition (the IO callbacks below are
    // not composable and cannot call tr() themselves).
    val exportedText = tr("Encrypted backup exported successfully.")
    val exportFailedText = tr("Export failed")
    val restoreFailedText = tr("Restore failed")
    val unknownErrorText = tr("unknown error")
    val invalidBackupText = tr("invalid backup or password")
    val destErrorText = tr("Could not open the selected destination.")
    val readErrorText = tr("Could not read the selected backup.")
    val tooLargeText = tr("Backup file is too large.")
    val inspectionPassedText = tr("Pre-restore inspection passed")
    val providersWord = tr("provider(s)")
    val inspectionSuffixText = tr("Review the inspection summary, then press Restore again.")
    val restoreOkText = tr("Restore committed successfully after pre-restore inspection with rollback protection.")
    val restorePartialStart = tr("Restore committed, but")
    val restorePartialEnd = tr("provider(s) were skipped and need re-adding")

    // Zero the secret buffer when the screen leaves composition.
    DisposableEffect(Unit) {
        onDispose { password.clear() }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val secret = password.snapshot()
        scope.launch(Dispatchers.IO) {
            busy = true
            status = runCatching {
                val text = graph.backup.export(secret)
                graph.context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error(destErrorText)
                BackupStatus.Success(exportedText)
            }.getOrElse { BackupStatus.Failure("$exportFailedText: ${it.message ?: unknownErrorText}") }
            Arrays.fill(secret, '0') // zero the export copy
            busy = false
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val secret = password.snapshot()
        scope.launch(Dispatchers.IO) {
            busy = true
            status = runCatching {
                val text = graph.context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    reader.readText()
                } ?: error(readErrorText)
                require(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) { tooLargeText }
                val payload = graph.backup.decrypt(text, secret)
                inspected = payload
                BackupStatus.Info("$inspectionPassedText: ${payload.providers.size} $providersWord. $inspectionSuffixText")
            }.getOrElse { BackupStatus.Failure("$restoreFailedText: ${it.message ?: invalidBackupText}") }
            Arrays.fill(secret, '0') // zero the inspection copy
            busy = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(tr("Encrypted Backup")) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Export settings and provider credentials into a password-protected backup. API keys are decrypted only in memory during export. The password buffer is zeroed after every operation."), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = passwordField,
                onValueChange = {
                    passwordField = it
                    password.update(it)
                },
                label = { Text(tr("Backup password")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            // v4.4.5 (UX): equal weights — "Inspect backup" is long (longer in
            // Persian) and could clip at the screen edge on narrow phones.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // Creation floor mirrors PortableBackup.MIN_PASSWORD_LENGTH
                // (12): new backups are only ever sealed with a 12+ char
                // password. Inspect/Restore below keep the legacy 8-char
                // floor so older backups stay restorable.
                Button(enabled = !busy && password.chars.size >= 12, onClick = { export.launch("anvex-backup.json") }, modifier = Modifier.weight(1f)) { Text(tr("Export"), maxLines = 1) }
                OutlinedButton(enabled = !busy && password.chars.size >= 8, onClick = { open.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.weight(1f)) { Text(tr("Inspect backup"), maxLines = 1) }
                Button(enabled = !busy && password.chars.size >= 8 && inspected != null, modifier = Modifier.weight(1f), onClick = {
                    val payload = inspected ?: return@Button
                    scope.launch(Dispatchers.IO) {
                        busy = true
                        status = runCatching {
                            val skipped = graph.backup.restore(payload)
                            inspected = null
                            // M-08: a partial restore is not a silent success —
                            // skipped providers are named so the user re-adds them.
                            if (skipped.isEmpty()) {
                                BackupStatus.Success(restoreOkText)
                            } else {
                                BackupStatus.Partial("$restorePartialStart ${skipped.size} $restorePartialEnd: ${skipped.joinToString("; ")}")
                            }
                        }.getOrElse { BackupStatus.Failure("$restoreFailedText: ${it.message ?: unknownErrorText}") }
                        busy = false
                    }
                }) { Text(tr("Restore")) }
            }
            inspected?.let { payload ->
                Card(Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text(tr("Pre-restore inspection")) }, supportingContent = { Text("${tr("Valid encrypted payload")} • ${payload.providers.size} ${tr("provider(s)")} • ${tr("settings payload present. No changes have been committed yet.")}") })
                }
            }
            status?.let { s ->
                Text(
                    s.text,
                    color = when (s) {
                        is BackupStatus.Failure -> MaterialTheme.colorScheme.error
                        is BackupStatus.Partial -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}
