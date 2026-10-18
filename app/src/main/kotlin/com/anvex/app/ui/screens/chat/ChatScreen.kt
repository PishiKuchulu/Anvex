package com.anvex.app.ui.screens.chat

import androidx.compose.material3.AlertDialog
import com.anvex.app.ui.components.PythonRunnerDialog
import com.anvex.app.ui.components.ProjectKnowledgeDialog
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import com.anvex.app.ui.components.FileVersion
import com.anvex.app.ui.components.RestoreOutcome
import com.anvex.app.ui.components.VersionSheet
import com.anvex.core.model.AttachmentKind
import com.anvex.core.model.ProviderConfig
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.CameraAlt
import com.anvex.app.share.IncomingShare
import com.anvex.app.ui.components.ReadAloud
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.activity.result.ActivityResultLauncher
import android.speech.RecognizerIntent
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.app.ui.Routes
import com.anvex.app.ui.components.ArtifactCard
import com.anvex.app.ui.components.BidiText
import com.anvex.app.ui.components.MarkdownText
import com.anvex.app.ui.components.ThinkingCard
import androidx.compose.material.icons.filled.Refresh
import com.anvex.app.ui.components.DeliverableCard
import com.anvex.app.ui.components.DiffSheet
import com.anvex.app.ui.components.GradientBadge
import com.anvex.app.ui.components.NebulaChip
import com.anvex.app.ui.theme.LocalBoltExtras
import com.anvex.app.ui.components.ProjectScaffold
import com.anvex.app.ui.components.ProjectTab
import com.anvex.app.ui.components.directionalTextStyle
import com.anvex.app.ui.components.isRtlText
import com.anvex.app.ui.screens.home.NewProjectHandoff
import com.anvex.app.ui.screens.home.formatRelative
import com.anvex.app.ui.theme.AnvexDimens
import com.anvex.app.ui.components.AnvexIconTile
import com.anvex.core.engine.QuickPrompts
import com.anvex.core.model.ActionRecord
import com.anvex.core.model.ActionStatus
import com.anvex.core.model.ChatMessage
import com.anvex.core.model.MessageRole
import com.anvex.core.model.ThinkingLevel
import com.anvex.app.vm.ChatViewModel
import com.anvex.app.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
/**
 * v4.4.4 (smoothness + Command Deck): stable holder for the "ask another model"
 * provider list. A bare List is unstable in Compose and would defeat skipping
 * for every message bubble; the @Immutable contract restores it.
 */
@Immutable
data class ProviderChoices(val items: List<ProviderConfig>)

@Composable
fun ChatScreen(nav: NavController, projectId: String) {
    val vm: ChatViewModel = viewModel(key = "chat-$projectId") { ChatViewModel(projectId) }
    val messages by vm.messages.collectAsState()
    // v4.4.4 (smoothness): the full sessionState ticks up to every 80ms while
    // streaming — the root now collects only the lightweight streamUi snapshot
    // (emits on visibility-relevant changes). Live text is collected locally
    // inside StreamingBubble.
    val streamUi by vm.streamUi.collectAsState()
    val project by vm.project.collectAsState()
    val noProvider by vm.noProviderConfigured.collectAsState()
    val attachmentError by vm.attachmentError.collectAsState()
    val thinkingLevel by vm.thinkingLevel.collectAsState()
    val activeModel by vm.activeModel.collectAsState()
    val webAvail by vm.webSearchAvailable.collectAsState()
    var pythonCode by remember { mutableStateOf<String?>(null) }
    var showKnowledge by remember { mutableStateOf(false) }
    val knowledgeFiles by vm.knowledgeFiles.collectAsState()
    val knowledgePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addKnowledgeFile(uri)
    }
    LaunchedEffect(showKnowledge) { if (showKnowledge) vm.refreshKnowledge() }
    val webOn by vm.webSearchOn.collectAsState()
    val providerChoices by vm.providerChoices.collectAsState()

    var input by remember { mutableStateOf(NewProjectHandoff.takePendingPrompt()) }
    val attachments = remember { mutableStateListOf<Uri>() }

    // Phase 3.2: a share sheet payload assigned to this chat is applied once on open.
    LaunchedEffect(vm.projectId) {
        IncomingShare.takeFor(vm.projectId)?.let { payload ->
            if (!payload.text.isNullOrBlank() && input.isBlank()) input = payload.text
            attachments.addAll(payload.uris)
        }
    }
    var diffPath by remember { mutableStateOf<String?>(null) }
    var diffContent by remember { mutableStateOf<String?>(null) }
    // Architect review item 3 (version history): selected file + version for the
    // read-only version sheet, plus the honest outcome of a restore action.
    var versionPath by remember { mutableStateOf<String?>(null) }
    var versionIndex by remember { mutableStateOf(1) }
    var restoreOutcome by remember { mutableStateOf<RestoreOutcome?>(null) }
    // v4.4 (architect feature 8): the user message being edited for a branch turn.
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    val branchNote by vm.branchNote.collectAsState()
    val listState = rememberLazyListState()

    // Phase 3.3 camera: the system camera writes into the app cache through FileProvider.
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraTarget
        cameraTarget = null
        if (saved && uri != null) attachments.add(uri)
    }

    // Phase 3.4 voice input: the recognizer result is appended to the draft.
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!spoken.isNullOrEmpty()) input = if (input.isBlank()) spoken else "$input $spoken"
    }
    val context = LocalContext.current

    // Phase 3.5 read-aloud: one shared engine per screen, released on leave.
    val readAloud = remember { ReadAloud(context) }
    val speakingId by readAloud.speakingId.collectAsState()
    DisposableEffect(Unit) { onDispose { readAloud.shutdown() } }

    // v4.0 audit fix (pendingChanges recomposition cost): the mutation journal snapshot
    // is derived from the persisted mutations flow instead of polling the session on
    // every recomposition. Declared before the effects that read it.
    val persistedMutations by vm.persistedMutations.collectAsState()
    val pendingChangesCached = remember(persistedMutations) {
        vm.pendingChangesSnapshot(persistedMutations)
    }

    // v4.0 audit fix (URI permission pairing): every persistable grant this screen takes
    // is registered so removing an attachment releases the matching grant (no leaked
    // persisted permissions) and disposal releases any remaining grants.
    val attachmentGrants = remember { mutableStateListOf<Uri>() }

    fun grantAttachment(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
        if (granted) attachmentGrants.add(uri)
        attachments.add(uri)
    }

    fun releaseAttachment(uri: Uri) {
        if (attachmentGrants.remove(uri)) {
            // Best-effort release: the provider may already have dropped the grant.
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Persisted grants outlive the screen; release what we still own.
            attachmentGrants.toList().forEach(::releaseAttachment)
            attachmentGrants.clear()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && attachments.size < 5) grantAttachment(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && attachments.size < 5) grantAttachment(uri)
    }

    LaunchedEffect(diffPath) {
        diffPath?.let { p ->
            diffContent = pendingChangesCached.firstOrNull { it.path == p }?.after ?: vm.readProjectFile(p)
        }
    }
    // v4.4.4 (smoothness): follow the tail only when the user is already pinned
    // near the bottom — instant jump, no animation fighting stream emissions
    // or stealing a deliberate scroll-up. (StreamingBubble follows its own
    // tail while streaming; this covers committed messages.)
    LaunchedEffect(messages.size) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            if (last == null || last >= count - 2) listState.scrollToItem(count - 1)
        }
    }

    ProjectScaffold(
        nav = nav,
        projectId = projectId,
        title = project?.name ?: "Chat",
        currentTab = ProjectTab.CHAT,
        actions = {
            if (streamUi.streaming) {
                IconButton(onClick = vm::cancel) {
                    Icon(Icons.Filled.StopCircle, contentDescription = "Stop generation")
                }
            }
            // v4.4.6: export the conversation as a Markdown transcript.
            IconButton(
                onClick = {
                    val title = project?.name ?: "Chat"
                    val uri = com.anvex.app.ui.components.ChatTranscriptExporter
                        .writeToCache(context, title, messages)
                    if (uri != null) com.anvex.app.ui.components.ChatTranscriptExporter
                        .share(context, uri, title)
                },
                enabled = messages.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = tr("Export chat"))
            }
        },
    ) { modifier ->
        // v4.4.4 (smoothness): O(n) scan cached — was re-run on every recomposition.
        val lastAssistantId = remember(messages) { messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id }
        // v4.4.4 (smoothness): stable provider holder — a bare List is unstable
        // and would defeat MessageBubble skipping on every pass.
        val choices = remember(providerChoices) { ProviderChoices(providerChoices) }
        Column(modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = AnvexDimens.page, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (noProvider) {
                    item { ProviderSetupCard(onConfigure = { nav.navigate(Routes.PROVIDERS) }) }
                }
                items(messages, key = { it.id }) { message ->
                    // v4.4.4 (smoothness): hoisted lambdas — recreated instances
                    // made every MessageBubble non-skippable, so all messages
                    // recomposed on every stream tick. Keyed on the message so
                    // the captured instance is never stale.
                    val onAskOther = remember(message) { { providerId: String -> vm.askAnotherModel(providerId, message.id) } }
                    val onSpeak = remember(message, speakingId) { { if (speakingId == message.id) readAloud.stop() else readAloud.speak(message.id, message.content) } }
                    val onDiff = remember { { path: String -> diffPath = path } }
                    val onRunPython = remember { { code: String -> pythonCode = code } }
                    val onVersions = remember { { path: String -> versionPath = path; versionIndex = vm.versionsFor(path).size; restoreOutcome = null } }
                    val onEditUser = remember(message) { { if (message.role == MessageRole.USER) { editing = message; editText = message.content } } }
                    MessageBubble(
                        message,
                        projectId,
                        onDiff = onDiff,
                        canRegenerate = message.id == lastAssistantId,
                        onRegenerate = vm::regenerateLast,
                        isSpeaking = speakingId == message.id,
                        providers = choices,
                        onAskOther = onAskOther,
                        onRunPython = onRunPython,
                        onSpeak = onSpeak,
                        versionsFor = vm::versionsFor,
                        onVersions = onVersions,
                        // v4.4 (architect feature 8): edit an earlier user message → branch.
                        onEditUser = onEditUser,
                        versionsRev = persistedMutations.size,
                    )
                }
                branchNote?.let { note ->
                    item { NebulaChip("↻ ${note}", icon = Icons.Filled.AutoAwesome) }
                }
                // v4.4.4 (smoothness): the live session values are collected
                // inside StreamingBubble — the list root only needs the
                // lightweight active flag.
                if (streamUi.active) {
                    item {
                        StreamingBubble(
                            session = vm.sessionState,
                            listState = listState,
                            versionsFor = vm::versionsFor,
                            onShowVersion = { path, index ->
                                versionPath = path
                                versionIndex = index
                                restoreOutcome = null
                            },
                        )
                    }
                }
                streamUi.memoryNote?.let { note ->
                    item { NebulaChip("Memory · $note", icon = Icons.Filled.Info) }
                }
                val errTitle = streamUi.errorTitle
                if (errTitle != null) {
                    item { ErrorCard(errTitle, streamUi.errorMessage.orEmpty(), vm::retryLast, vm::clearError) }
                }
                if (messages.isEmpty() && !streamUi.streaming && !noProvider) {
                    item { EmptyChatState() }
                }
            }

            if (messages.isEmpty() && input.isEmpty() && !streamUi.streaming) {
                QuickPromptRow(
                    onPrompt = { input = it },
                    enabled = true,
                )
            }
            if (attachments.isNotEmpty()) {
                AttachmentRow(attachments, onRemove = { uri ->
                    releaseAttachment(uri)
                    attachments.remove(uri)
                }, context = context)
            }
            attachmentError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::clearAttachmentError) { Text(tr("Dismiss")) }
                    }
                }
            }
            Composer(
                value = input,
                enabled = !streamUi.streaming,
                onValueChange = { input = it },
                onAttach = { filePicker.launch(arrayOf("*/*")) },
                onImage = { imagePicker.launch(arrayOf("image/*")) },
                onSend = {
                    val text = input
                    input = ""
                    vm.send(text, attachments.toList())
                    // Grants are transferred to the message attachments (retry re-opens
                    // via the same URIs); do not release them here.
                    attachmentGrants.clear()
                    attachments.clear()
                },
                canSend = input.isNotBlank() || attachments.isNotEmpty(),
                thinkingLevel = thinkingLevel,
                onThinkingLevel = vm::setThinkingLevel,
                onVoice = { startVoiceInput(context, voiceLauncher) },
                onCamera = {
                    val uri = newCameraUri(context)
                    if (uri != null) {
                        cameraTarget = uri
                        cameraLauncher.launch(uri)
                    }
                },
                modelLabel = activeModel,
                webAvailable = webAvail,
                webOn = webOn,
                onProject = { showKnowledge = true },
                onToggleWeb = vm::setWebSearch,
            )
        }
    }

    val mcpRequest by vm.mcpApproval.collectAsState()
    mcpRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { vm.answerMcp(request.id, false) },
            title = { Text(tr("Allow tool call?")) },
            text = {
                Column {
                    Text("Server: ${request.server}", style = MaterialTheme.typography.bodyMedium)
                    Text("Tool: ${request.tool}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        request.arguments,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.answerMcp(request.id, true) }) { Text(tr("Allow once")) } },
            dismissButton = { TextButton(onClick = { vm.answerMcp(request.id, false) }) { Text(tr("Deny")) } },
        )
    }

    if (showKnowledge) {
        ProjectKnowledgeDialog(
            initial = vm.projectInstructions(),
            files = knowledgeFiles,
            onSave = vm::saveProjectInstructions,
            onAddFile = { knowledgePicker.launch(arrayOf("text/*", "application/json")) },
            onRemoveFile = vm::removeKnowledgeFile,
            onDismiss = { showKnowledge = false },
        )
    }
    pythonCode?.let { code ->
        PythonRunnerDialog(code = code, onDismiss = { pythonCode = null })
    }

    // v4.4 (architect feature 8): edit-and-branch dialog. Resending an edited turn
    // NEVER deletes the existing thread — file changes made after that message are
    // rolled back from the mutation journal first, then the edited text is sent as a
    // new turn. The original replies stay in history.
    editing?.let { target ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr("Edit & branch")) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it.take(8_000) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 10,
                    label = { Text(tr("Your message (edited)")) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = editText.trim()
                        val message = target
                        editing = null
                        if (text.isNotBlank()) vm.branchFrom(message, text)
                    },
                    enabled = editText.isNotBlank(),
                ) { Text(tr("Send as new branch")) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }

    diffPath?.let { path ->
        val change = pendingChangesCached.firstOrNull { it.path == path }
        DiffSheet(
            path = path,
            before = change?.before,
            after = diffContent,
            operation = change?.operation,
            renamedTo = change?.renamedTo,
            onRevert = { vm.revertChange(path); diffPath = null },
            onOpenEditor = { diffPath = null; nav.navigate(Routes.editor(projectId, path)) },
            onDismiss = { diffPath = null },
        )
    }

    versionPath?.let { path ->
        val versions = remember(versionPath, persistedMutations) { vm.versionsFor(path) }
        VersionSheet(
            path = path,
            versions = versions,
            initialVersion = versionIndex,
            restoreOutcome = restoreOutcome,
            onRestore = { version ->
                vm.restoreVersion(path, version) { ok, detail ->
                    restoreOutcome = RestoreOutcome(ok, detail)
                }
            },
            onOpenEditor = { versionPath = null; nav.navigate(Routes.editor(projectId, path)) },
            onDismiss = { versionPath = null; restoreOutcome = null },
        )
    }
}

@Composable
private fun ProviderSetupCard(onConfigure: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Connect an AI provider"), style = MaterialTheme.typography.titleSmall)
                Text(tr("Add an API key to start building with Anvex."), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onConfigure) { Text(tr("Configure")) }
        }
    }
}

@Composable
private fun EmptyChatState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("What are you building?"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Describe a feature or change. Anvex will edit the project and let you review the result.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.86f),
        )
    }
}

@Composable
private fun QuickPromptRow(onPrompt: (String) -> Unit, enabled: Boolean) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = AnvexDimens.page),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(QuickPrompts.defaults.take(3), key = { it.title }) { item ->
            AssistChip(
                onClick = { onPrompt(item.prompt) },
                enabled = enabled,
                label = { Text(item.title, maxLines = 1) },
            )
        }
    }
}

/** Compact "Thinking · Normal" pill that opens a menu with every level and what it means. */
@Composable
private fun ThinkingMenu(selected: ThinkingLevel, enabled: Boolean, onSelect: (ThinkingLevel) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        NebulaChip(
            label = "Thinking · ${selected.label}",
            icon = Icons.Filled.Psychology,
            selected = selected != ThinkingLevel.NORMAL,
            onClick = { if (enabled) open = true },
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThinkingLevel.entries.forEach { level ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(level.label, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (level == selected) FontWeight.SemiBold else FontWeight.Normal)
                            Text(
                                when (level) {
                                    ThinkingLevel.LOW -> "Fast, light reasoning"
                                    ThinkingLevel.NORMAL -> "Balanced (default)"
                                    ThinkingLevel.HIGH -> "Deeper analysis"
                                    ThinkingLevel.XHIGH -> "Complex tasks, slower"
                                    ThinkingLevel.MAX -> "Maximum effort, slowest"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = if (level == selected) ({ Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { onSelect(level); open = false },
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(attachments: List<Uri>, onRemove: (Uri) -> Unit, context: android.content.Context) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = AnvexDimens.page),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments.size, key = { attachments[it] }) { index ->
            val uri = attachments[index]
            FilterChip(
                selected = false,
                onClick = { onRemove(uri) },
                label = { Text(uri.lastPathSegment ?: "Attachment", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(if (graphMimeIsImage(context, uri)) Icons.Filled.Image else Icons.Filled.AttachFile, null) },
            )
        }
    }
}

/** A fresh FileProvider URI for one camera capture, stored under the app cache. */
private fun newCameraUri(context: android.content.Context): Uri? = runCatching {
    val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
    val file = java.io.File(dir, "capture-${System.currentTimeMillis()}.jpg")
    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

/** Opens the system speech recognizer; shows a message when none is installed. */
private fun startVoiceInput(context: android.content.Context, launcher: ActivityResultLauncher<Intent>) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        launcher.launch(intent)
    } else {
        android.widget.Toast.makeText(context, "No speech recognizer on this device", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onAttach: () -> Unit,
    onImage: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    thinkingLevel: ThinkingLevel,
    onThinkingLevel: (ThinkingLevel) -> Unit,
    onVoice: () -> Unit,
    onCamera: () -> Unit = {},
    modelLabel: String? = null,
    webAvailable: Boolean = false,
    webOn: Boolean = false,
    onToggleWeb: (Boolean) -> Unit = {},
    onProject: () -> Unit = {},
) {
    // v4.4.4 (Command Deck): the composer is the signature control — a command
    // palette, not a chat box. Mono prompt glyph, hairline border, solid surface.
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page, vertical = 8.dp),
        shape = RoundedCornerShape(AnvexDimens.radiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(8.dp)) {
            // v4.4.4 (smoothness): O(n) bidi scan cached per input value.
            val rtl = remember(value) { isRtlText(value) }
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = directionalTextStyle(MaterialTheme.typography.bodyLarge, value),
                    placeholder = { Text(tr("Ask anything, or describe what to build…")) },
                    // Command-deck signature: the mono prompt glyph.
                    leadingIcon = {
                        Text(
                            "❯",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    },
                    minLines = 2,
                    maxLines = 5,
                    enabled = enabled,
                    shape = RoundedCornerShape(AnvexDimens.radiusMedium),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                    ),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // v4.4.5 (UX): one "+" entry point instead of five undifferentiated
                // icon buttons — the sheet groups them with labels, the
                // professional pattern. Mic stays dedicated: first-class
                // mobile input with its own flow. (The old horizontally
                // scrolling strip is gone — it was a band-aid for the clutter.)
                var showAttachSheet by remember { mutableStateOf(false) }
                IconButton(onClick = { showAttachSheet = true }, enabled = enabled) {
                    Icon(Icons.Filled.Add, tr("Add attachment"))
                }
                IconButton(onClick = onVoice, enabled = enabled) { Icon(Icons.Filled.Mic, tr("Voice input")) }
                if (webAvailable) {
                    FilterChip(
                        selected = webOn,
                        onClick = { onToggleWeb(!webOn) },
                        label = { Text(tr("Web")) },
                        enabled = enabled,
                    )
                }
                ThinkingMenu(selected = thinkingLevel, enabled = enabled, onSelect = onThinkingLevel)
                if (modelLabel != null) {
                    Text(
                        modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp).weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (showAttachSheet) {
                    AttachSheet(
                        onDismiss = { showAttachSheet = false },
                        onProject = { showAttachSheet = false; onProject() },
                        onAttach = { showAttachSheet = false; onAttach() },
                        onImage = { showAttachSheet = false; onImage() },
                        onCamera = { showAttachSheet = false; onCamera() },
                    )
                }
                Spacer(Modifier.width(8.dp))
                val sendReady = canSend && enabled
                // v4.4.4 (Command Deck): the send key springs alive when a command
                // is ready — the one orchestrated motion on this surface.
                val sendScale by animateFloatAsState(
                    targetValue = if (sendReady) 1f else 0.9f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "sendReady",
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                        .clip(RoundedCornerShape(AnvexDimens.radiusMedium))
                        .background(
                            if (sendReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable(enabled = sendReady, role = androidx.compose.ui.semantics.Role.Button, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (sendReady) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v4.4.5 (UX): the composer's "+" entry point. Five undifferentiated icon
 * buttons became one labeled menu — the professional pattern (ChatGPT/Claude
 * mobile do the same). All handlers already existed; this only reorganizes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onProject: () -> Unit,
    onAttach: () -> Unit,
    onImage: () -> Unit,
    onCamera: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                tr("Add to message"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AttachOption(
                icon = Icons.Filled.Folder,
                title = tr("Project files"),
                subtitle = tr("Instructions, docs and project context"),
                onClick = onProject,
            )
            AttachOption(
                icon = Icons.Filled.AttachFile,
                title = tr("Attach file"),
                subtitle = tr("Any document from storage"),
                onClick = onAttach,
            )
            AttachOption(
                icon = Icons.Filled.Image,
                title = tr("Photo library"),
                subtitle = tr("Pick an image"),
                onClick = onImage,
            )
            AttachOption(
                icon = Icons.Filled.CameraAlt,
                title = tr("Take photo"),
                subtitle = tr("Use the camera"),
                onClick = onCamera,
            )
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AnvexDimens.radiusMedium))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnvexIconTile(icon, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    projectId: String,
    onDiff: (String) -> Unit,
    canRegenerate: Boolean = false,
    onRegenerate: () -> Unit = {},
    isSpeaking: Boolean = false,
    onSpeak: () -> Unit = {},
    providers: ProviderChoices = ProviderChoices(emptyList()),
    onAskOther: (String) -> Unit = {},
    onRunPython: (String) -> Unit = {},
    versionsFor: (String) -> List<FileVersion> = { emptyList() },
    onVersions: (String) -> Unit = {},
    onEditUser: () -> Unit = {},
    /** v4.4.4 (smoothness): bumps when the mutation journal grows — invalidates cached version counts. */
    versionsRev: Int = 0,
) {
    val context = LocalContext.current
    val isUser = message.role == MessageRole.USER
    var menuOpen by remember { mutableStateOf(false) }
    val copyResponseLabel = tr("Anvex response")
    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!isUser) GradientBadge(Icons.Filled.AutoAwesome, size = 18)
            Text(
                if (isUser) "You" else "Anvex",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(4.dp))
        val extras = LocalBoltExtras.current
        // v4.4.4 (Command Deck): compact radii on the 8pt grid.
        val bubbleShape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 16.dp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.88f else 0.96f)
                .clip(bubbleShape)
                .then(
                    if (isUser) Modifier.background(extras.userBubbleBrush, bubbleShape)
                    else Modifier.background(extras.glass, bubbleShape).border(BorderStroke(1.dp, extras.glassBorder), bubbleShape)
                ),
        ) {
            Column(Modifier.padding(14.dp)) {
                message.reasoning?.let { trace ->
                    ThinkingCard(reasoning = trace, streaming = false, modifier = Modifier.padding(bottom = 8.dp))
                }
                message.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (message.content.isNotBlank()) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (isUser) {
                            BidiText(
                                message.content,
                                // v4.4.5 (UX): onPrimaryContainer pairs with the
                                // bubble fill by design — hardcoded White was
                                // unreadable on light-theme fills.
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onPrimaryContainer),
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            MarkdownText(
                                message.content,
                                modifier = Modifier.weight(1f),
                                textColor = MaterialTheme.colorScheme.onSurface,
                                onRunPython = onRunPython,
                            )
                        }
                        if (isUser) {
                            // v4.4 (architect feature 8): edit this user message → branch turn.
                            IconButton(onClick = onEditUser, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Edit, tr("Edit & branch"))
                            }
                        }
                        if (!isUser) {
                            IconButton(
                                onClick = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip?.setPrimaryClip(ClipData.newPlainText(copyResponseLabel, message.content))
                                },
                                modifier = Modifier.size(48.dp),
                            ) { Icon(Icons.Filled.ContentCopy, "Copy response") }
                            IconButton(onClick = onSpeak, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (isSpeaking) Icons.Filled.StopCircle else Icons.Filled.VolumeUp,
                                    if (isSpeaking) "Stop reading" else "Read aloud",
                                )
                            }
                            if (providers.items.any { it.id != message.providerId }) {
                                Box {
                                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Filled.MoreVert, "Ask another model")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        providers.items.filter { it.id != message.providerId }.forEach { provider ->
                                            DropdownMenuItem(
                                                text = { Text("Ask ${provider.name}") },
                                                onClick = {
                                                    menuOpen = false
                                                    onAskOther(provider.id)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (canRegenerate) {
                                IconButton(onClick = onRegenerate, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.Refresh, "Regenerate response")
                                }
                            }
                        }
                    }
                }
                if (!isUser) {
                    message.attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach { GeneratedImage(it.uri) }
                }
                if (isUser) {
                    message.attachments.forEach { attachment ->
                        // v4.4.5 (UX): explicit color — the bubble is a raw
                        // background brush, so ambient content color is wrong.
                        Text(
                            attachment.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                message.artifact?.let { summary ->
                    // v4.4.4 (smoothness): partition cached per artifact summary.
                    val (files, others) = remember(summary) { summary.actions.partition { it.type == "artifact" } }
                    if (others.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(summary.title.ifBlank { "Files changed" }, style = MaterialTheme.typography.titleSmall)
                        others.forEach { action -> ArtifactActionLine(action, onDiff, versionsFor, onVersions, versionsRev) }
                    }
                    files.forEach { record ->
                        Spacer(Modifier.height(10.dp))
                        DeliverableCard(projectId, record)
                    }
                }
                Text(formatRelative(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ArtifactActionLine(
    action: ActionRecord,
    onDiff: (String) -> Unit,
    versionsFor: (String) -> List<FileVersion> = { emptyList() },
    onVersions: (String) -> Unit = {},
    versionsRev: Int = 0,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val marker = when (action.type) {
            "file" -> if (action.status == ActionStatus.COMPLETE.name && action.note?.contains("CREATE", true) == true) "+" else "~"
            else -> "$"
        }
        Text(marker, fontFamily = FontFamily.Monospace, modifier = Modifier.width(18.dp), color = if (marker == "+") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        val filePath = if (action.type == "file") action.filePath else null
        if (filePath != null) {
            TextButton(onClick = { onDiff(filePath) }, contentPadding = PaddingValues(0.dp)) {
                Text(filePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Architect item 3: version history is one tap away when the journal
            // holds more than one version of this file.
            // v4.4.4 (smoothness): filter+sort cached — was re-run every recomposition.
            // versionsRev invalidates when the journal grows.
            val versionCount = remember(filePath, versionsRev) { versionsFor(filePath).size }
            if (versionCount > 1) {
                IconButton(onClick = { onVersions(filePath) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = tr("Version history"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            val command = action.command
            if (command != null) {
                Text(command.trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * v4.4.4 (smoothness + Command Deck): collects the session StateFlow LOCALLY so
 * the 80ms stream ticks recompose only this bubble — never the chat root.
 * Follows the tail while streaming (instant jump, only when pinned to bottom).
 */
@Composable
private fun StreamingBubble(
    session: StateFlow<com.anvex.core.android.session.ChatSession.UiState>,
    listState: LazyListState,
    versionsFor: (String) -> List<FileVersion> = { emptyList() },
    onShowVersion: (String, Int) -> Unit = { _, _ -> },
) {
    val s by session.collectAsState()
    val text = s.streamText
    val artifact = s.currentArtifact
    val statusLine = s.statusLine
    val reasoning = s.streamReasoning
    // Follow the tail while streaming; never steal a deliberate scroll-up.
    LaunchedEffect(text.length / 160) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            if (last == null || last >= count - 2) listState.scrollToItem(count - 1)
        }
    }
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank()) {
            ThinkingCard(reasoning = reasoning, streaming = true, modifier = Modifier.padding(bottom = 6.dp).fillMaxWidth(0.96f))
        }
        if (statusLine != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(statusLine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (text.isNotBlank()) {
            Surface(
                color = LocalBoltExtras.current.glass,
                // v4.4.5 (UX): explicit — glass is translucent in some themes,
                // leaving contentColorFor() Unspecified (black-text fallback).
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LocalBoltExtras.current.glassBorder),
                modifier = Modifier.fillMaxWidth(0.96f),
            ) {
                BidiText(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
            }
        }
        artifact?.let {
            Spacer(Modifier.height(8.dp))
            ArtifactCard(
                artifact = it,
                versionsFor = versionsFor,
                onShowVersion = onShowVersion,
            )
        }
    }
}

/** Open a URL in the system browser (never a WebView); failures are swallowed silently. */

@Composable
private fun ErrorCard(title: String, detail: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (title.contains("Access restricted", ignoreCase = true)) {
                // 403 from the provider: Anvex does not (and must not) imitate an official client to get around it.
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("This is a restriction set by the provider, not an Anvex bug. Use an API key from the provider's console (for OpenCode: the OpenCode Console provider), or pick another provider such as a Gemini API key or an OpenRouter free model."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row { TextButton(onClick = onRetry) { Text(tr("Retry")) }; TextButton(onClick = onDismiss) { Text(tr("Dismiss")) } }
        }
    }
}

/**
 * v4.0 audit fix (MIME sniffing): image detection resolves the *actual* content MIME
 * type through ContentResolver first and only falls back to a filename-extension
 * heuristic when the provider reports nothing (e.g. some file managers).
 */
private fun graphMimeIsImage(context: android.content.Context, uri: Uri): Boolean {
    val resolved = runCatching { context.contentResolver.getType(uri)?.lowercase() }.getOrNull()
    if (resolved != null) return resolved.startsWith("image/")
    return uri.toString().substringBefore('?').lowercase().let {
        it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".gif") || it.endsWith(".webp")
    }
}

/** Shows an image produced by /image (stored in the app's files folder, read back via FileProvider). */
@Composable
// v4.4 lint: the producer assigns `value` directly below (verified); the Compose
// detector false-positives on the delegated produceState form used here.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
private fun GeneratedImage(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri) {
        // Lint (ProduceStateDoesNotAssignValue): keep the `value =` assignment a
        // plain statement so the producer is statically visible.
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
        value = loaded
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Generated image",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}
