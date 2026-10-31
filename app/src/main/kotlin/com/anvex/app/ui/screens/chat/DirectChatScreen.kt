package com.anvex.app.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.app.i18n.tr
import com.anvex.app.ui.Routes
import com.anvex.app.ui.components.AnvexIconTile
import com.anvex.app.ui.components.BidiText
import com.anvex.app.ui.components.ChatTranscriptExporter
import com.anvex.app.ui.components.ConfirmDialog
import com.anvex.app.ui.components.GooThinkingIcon
import com.anvex.app.ui.components.MarkdownText
import com.anvex.app.ui.components.MessageActionSheet
import com.anvex.app.ui.components.directionalTextStyle
import com.anvex.app.ui.components.isRtlText
import com.anvex.app.ui.components.quotedReplyText
import com.anvex.app.vm.DirectChatViewModel
import com.anvex.core.model.ChatMessage
import com.anvex.core.model.MessageRole
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * In-memory hand-off for the Atelier home composer's "quick chat" mode:
 * the typed text is sent as the first message when this screen opens.
 * Same guarded pattern as the project-chat NewProjectHandoff.
 */
object DirectChatHandoff {
    @Volatile
    private var pending: String = ""

    var pendingPrompt: String
        get() = synchronized(this) { pending }
        set(value) { synchronized(this) { pending = value } }

    /** Atomically reads and clears the pending prompt exactly once. */
    fun takePendingPrompt(): String = synchronized(this) {
        val value = pending
        pending = ""
        value
    }
}

/**
 * v4.4.6: project-less direct chat — a top-level Chat tab for conversing with
 * the agent without a folder/project. Conversations live in a drawer; the
 * thread supports text, image/file attachments (via the activity pickers),
 * web search toggle, and streaming.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectChatScreen(nav: NavController) {
    val vm: DirectChatViewModel = viewModel()
    val conversations by vm.conversations.collectAsState()
    val archivedConversations by vm.archivedConversations.collectAsState()
    val visibleConversations by vm.visibleConversations.collectAsState()
    val visibleArchived by vm.visibleArchivedConversations.collectAsState()
    val chatQuery by vm.chatQuery.collectAsState()
    val providerChoices by vm.providerChoices.collectAsState()
    val activeProviderId by vm.activeProviderId.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val messages by vm.messages.collectAsState()
    val sessionState by vm.sessionState.collectAsState()
    val atelierStyle = com.anvex.app.ui.theme.LocalThemeStyle.current ==
        com.anvex.core.android.repo.UiThemeStyle.ATELIER
    val streamActive by vm.streamActive.collectAsState()
    val noProvider by vm.noProviderConfigured.collectAsState()
    val activeModel by vm.activeModel.collectAsState()
    val webOn by vm.webSearchOn.collectAsState()
    val webAvail by vm.webSearchAvailable.collectAsState()
    val attachmentError by vm.attachmentError.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var input by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Uri>() }
    // v4.6: long-press message actions (reply / copy / share / select text).
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var attachSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DirectChatViewModel.Conversation?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<DirectChatViewModel.Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()
    val listState = rememberLazyListState()

    val activeTitle = (conversations + archivedConversations).firstOrNull { it.id == activeId }?.title ?: tr("Chat")

    // v4.4.5 (smoothness): follow the tail only when already at the bottom.
    // v4.6: edge-based pinned check (isPinnedToBottom, shared with the project
    // chat) and never while the user is scrolling — the index-based check ran
    // on EVERY stream chunk and cancelled the user's scroll until generation
    // finished, making a long answer impossible to read while it streamed.
    LaunchedEffect(messages.size, sessionState.streamText.length) {
        val total = messages.size + if (sessionState.streamText.isNotEmpty()) 1 else 0
        if (total > 0 && !listState.isScrollInProgress && listState.isPinnedToBottom()) {
            listState.scrollToItem((total - 1).coerceAtLeast(0))
        }
    }

    // Atelier home composer (quick-chat mode) hands its first message here.
    LaunchedEffect(Unit) {
        val pending = DirectChatHandoff.takePendingPrompt()
        if (pending.isNotBlank()) vm.send(pending, emptyList())
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            attachments.add(uri)
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attachments.add(uri)
    }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraTarget
        cameraTarget = null
        if (saved && uri != null) attachments.add(uri)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() && attachments.isEmpty() && replyTo == null) return
        if (sessionState.streaming) return
        val quote = replyTo
        replyTo = null
        val finalText = if (quote != null) quotedReplyText(quote.content, text) else text
        vm.send(finalText, attachments.toList())
        input = ""
        attachments.clear()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Conversations"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.newConversation() }) {
                        Icon(Icons.Filled.Add, contentDescription = tr("New chat"))
                    }
                }
                if (conversations.isNotEmpty() || archivedConversations.isNotEmpty() || chatQuery.isNotBlank()) {
                    OutlinedTextField(
                        value = chatQuery,
                        onValueChange = vm::setChatQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        label = { Text(tr("Search chats")) },
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                visibleConversations.forEach { conv ->
                    ConversationDrawerRow(
                        conv = conv,
                        selected = conv.id == activeId,
                        menuOpen = menuFor == conv.id,
                        onMenuOpenChange = { open -> menuFor = if (open) conv.id else null },
                        onSelect = {
                            vm.selectConversation(conv.id)
                            scope.launch { drawerState.close() }
                        },
                        onPin = { vm.setPinned(conv.id, !conv.pinned) },
                        onRename = { renameText = conv.title; renameTarget = conv },
                        onArchive = { vm.setArchived(conv.id, true) },
                        onDelete = { deleteTarget = conv },
                    )
                }
                if (visibleConversations.isEmpty() && visibleArchived.isEmpty() && chatQuery.isNotBlank()) {
                    Text(
                        "No chats match \"${chatQuery.trim()}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (conversations.isEmpty() && chatQuery.isBlank()) {
                    Text(
                        tr("No conversations yet"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (visibleArchived.isNotEmpty()) {
                    Text(
                        tr("Archived"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    visibleArchived.forEach { conv ->
                        ConversationDrawerRow(
                            conv = conv,
                            selected = conv.id == activeId,
                            menuOpen = menuFor == conv.id,
                            onMenuOpenChange = { open -> menuFor = if (open) conv.id else null },
                            onSelect = {
                                vm.selectConversation(conv.id)
                                scope.launch { drawerState.close() }
                            },
                            onPin = { vm.setPinned(conv.id, !conv.pinned) },
                            onRename = { renameText = conv.title; renameTarget = conv },
                            onArchive = { vm.setArchived(conv.id, false) },
                            onDelete = { deleteTarget = conv },
                        )
                    }
                }
            }
        }
        },
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                TopAppBar(
                    title = {
                        Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = tr("Conversations"))
                        }
                    },
                    actions = {
                        if (webAvail) {
                            FilterChip(
                                selected = webOn,
                                onClick = { vm.setWebSearch(!webOn) },
                                label = { Text(tr("Web")) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        // v4.4.6: export transcript as Markdown (file route with
                        // plain-text fallback, so the button never silently dies).
                        IconButton(
                            onClick = {
                                ChatTranscriptExporter.shareTranscript(context, activeTitle, messages)
                            },
                            enabled = messages.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = tr("Export chat"))
                        }
                        // v4.4.6: create a project from this conversation.
                        IconButton(
                            onClick = {
                                vm.createProjectFromActive(activeTitle) { id ->
                                    nav.navigate(Routes.chat(id))
                                }
                            },
                            enabled = messages.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = tr("Create project from chat"))
                        }
                        IconButton(onClick = { vm.newConversation() }) {
                            Icon(Icons.Filled.Add, contentDescription = tr("New chat"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // v4.6 (keyboard fix): this screen had NO IME handling at
                    // all — the composer stayed under the keyboard. The root
                    // dock's imePadding now grows the scaffold padding too;
                    // this adds whatever inset remains unconsumed (the two
                    // mechanisms are self-limiting, never double).
                    .imePadding(),
            ) {
                if (attachmentError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                attachmentError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { vm.clearAttachmentError() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = tr("Dismiss"), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                if (noProvider) {
                    // No provider configured — honest empty state, not a dead chat.
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnvexIconTile(Icons.AutoMirrored.Filled.Chat, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(tr("Connect your AI provider"), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tr("Set up a provider to start chatting"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            androidx.compose.material3.Button(onClick = { nav.navigate(Routes.PROVIDERS) }) {
                                Text(tr("Set up"))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (messages.isEmpty() && !streamActive) {
                            item {
                                Box(
                                    modifier = Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        AnvexIconTile(Icons.AutoMirrored.Filled.Chat, modifier = Modifier.size(64.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            tr("Ask anything"),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            tr("No project needed — just chat"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (activeModel != null) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                activeModel!!,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(messages, key = { it.id }) { msg ->
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().animateItem()) {
                            DirectMessageBubble(msg, onShowActions = { actionMessage = msg })
                        
                    }
                }
                        if (sessionState.streamText.isNotEmpty()) {
                            item(key = "streaming") {
                                StreamingBubble(
                                    text = sessionState.streamText,
                                    reasoning = sessionState.streamReasoning,
                                )
                            }
                        }
                        if (sessionState.statusLine != null && sessionState.streaming) {
                            item(key = "status") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        sessionState.statusLine!!,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (sessionState.error != null) {
                            item(key = "error") {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        "${sessionState.error!!.userTitle}: ${sessionState.error!!.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Attachments preview strip.
                    if (attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            attachments.forEach { uri ->
                                FilterChip(
                                    selected = false,
                                    onClick = { attachments.remove(uri) },
                                    label = {
                                        Text(
                                            uri.lastPathSegment?.substringAfterLast('/')?.take(18) ?: "file",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(Icons.Filled.Close, contentDescription = tr("Remove"), modifier = Modifier.size(14.dp))
                                    },
                                )
                            }
                        }
                    }

                    // v4.6: reply quote bar (set from the long-press sheet).
                    replyTo?.let { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                target.content.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { replyTo = null }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = tr("Cancel"), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // Composer. Atelier: attached flat bar (surface fill +
                    // top hairline) instead of floating over the canvas.
                    if (atelierStyle) {
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    // Model switcher row: quick chat previously had NO way
                    // to change the model after it was chosen. Tapping the
                    // label opens the provider list; the pick becomes the
                    // default and the session rebuilds on it.
                    if (activeModel != null && providerChoices.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (atelierStyle) {
                                        Modifier.background(MaterialTheme.colorScheme.surface)
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (providerChoices.size > 1) {
                                var modelMenuOpen by remember { mutableStateOf(false) }
                                Box {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .clickable(enabled = !sessionState.streaming) { modelMenuOpen = true }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            activeModel!!,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(
                                            Icons.Filled.ArrowDropDown,
                                            contentDescription = tr("Switch model"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = modelMenuOpen,
                                        onDismissRequest = { modelMenuOpen = false },
                                    ) {
                                        providerChoices.forEach { choice ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(
                                                            choice.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = if (choice.id == activeProviderId) {
                                                                androidx.compose.ui.text.font.FontWeight.SemiBold
                                                            } else {
                                                                androidx.compose.ui.text.font.FontWeight.Normal
                                                            },
                                                        )
                                                        Text(
                                                            choice.model,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                },
                                                trailingIcon = if (choice.id == activeProviderId) {
                                                    { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                                                } else {
                                                    null
                                                },
                                                onClick = {
                                                    modelMenuOpen = false
                                                    if (choice.id != activeProviderId) vm.switchProvider(choice.id)
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    activeModel!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (atelierStyle) {
                                    Modifier.background(MaterialTheme.colorScheme.surface)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        IconButton(onClick = { attachSheet = true }) {
                            Icon(Icons.Filled.AttachFile, contentDescription = tr("Attach"))
                        }
                        // v4.6 (RTL fix): the composer follows the typed text's
                        // direction, like the project chat composer already did.
                        // (Box carries the Row weight — weight is a RowScope
                        // member and cannot be used inside the provider lambda.)
                        val composerRtl = remember(input) { isRtlText(input) }
                        Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(
                                LocalLayoutDirection provides if (composerRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = directionalTextStyle(MaterialTheme.typography.bodyLarge, input),
                                    placeholder = { Text(tr("Message")) },
                                    shape = RoundedCornerShape(24.dp),
                                    maxLines = 5,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (sessionState.streaming && atelierStyle) {
                            // Atelier streaming state: stop square in the
                            // accent circle, matching the agent composer.
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable(
                                        role = androidx.compose.ui.semantics.Role.Button,
                                        onClick = { vm.cancel() },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = tr("Stop"),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else if (sessionState.streaming) {
                            // Thinking state shows the goo loader (tap still cancels).
                            IconButton(onClick = { vm.cancel() }) {
                                GooThinkingIcon(modifier = Modifier.size(32.dp))
                            }
                        } else if (atelierStyle) {
                            // Atelier send: gradient circle with an up arrow.
                            val sendReady = input.isNotBlank() || attachments.isNotEmpty() || replyTo != null
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .then(
                                        if (sendReady) {
                                            Modifier.background(
                                                com.anvex.app.ui.theme.LocalBoltExtras.current.accentBrush,
                                                androidx.compose.foundation.shape.CircleShape,
                                            )
                                        } else {
                                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                        },
                                    )
                                    .clickable(
                                        enabled = sendReady,
                                        role = androidx.compose.ui.semantics.Role.Button,
                                        onClick = { send() },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.ArrowUpward,
                                    contentDescription = tr("Send"),
                                    tint = if (sendReady) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { send() },
                                enabled = input.isNotBlank() || attachments.isNotEmpty() || replyTo != null,
                            ) {
                                Icon(
                                    // L-08: the filled Send arrow is not
                                    // mirrored — in RTL it pointed the wrong
                                    // way. ChatScreen already uses AutoMirrored.
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = tr("Send"),
                                    tint = if (input.isNotBlank() || attachments.isNotEmpty() || replyTo != null)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        ConfirmDialog(
            title = tr("Delete conversation"),
            text = tr("Delete this conversation and its messages? This cannot be undone."),
            confirmLabel = tr("Delete"),
            destructive = true,
            onConfirm = {
                vm.deleteConversation(deleteTarget!!.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(tr("Rename conversation")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(tr("Conversation title")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameConversation(renameTarget!!.id, renameText)
                    renameTarget = null
                }) { Text(tr("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }

    actionMessage?.let { target ->
        MessageActionSheet(
            content = target.content,
            onReply = { replyTo = target; actionMessage = null },
            onDismiss = { actionMessage = null },
        )
    }

    if (attachSheet) {
        ModalBottomSheet(
            onDismissRequest = { attachSheet = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(tr("Attach"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                AttachRow(
                    icon = Icons.Filled.AttachFile,
                    label = tr("Attach file"),
                    onClick = {
                        attachSheet = false
                        filePicker.launch(arrayOf("*/*"))
                    },
                )
                AttachRow(
                    icon = Icons.Filled.Image,
                    label = tr("Photo library"),
                    onClick = {
                        attachSheet = false
                        photoPicker.launch("image/*")
                    },
                )
                AttachRow(
                    icon = Icons.Filled.PhotoCamera,
                    label = tr("Take photo"),
                    onClick = {
                        attachSheet = false
                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File(dir, "direct-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        cameraTarget = uri
                        cameraLauncher.launch(uri)
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DirectMessageBubble(msg: ChatMessage, onShowActions: () -> Unit = {}) {
    val isUser = msg.role == MessageRole.USER
    // Atelier quick chat: the user bubble is an inverted ink fill and the
    // assistant reply is bare text (transparent surface).
    val atelier = com.anvex.app.ui.theme.LocalThemeStyle.current ==
        com.anvex.core.android.repo.UiThemeStyle.ATELIER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(if (atelier) 20.dp else 16.dp),
            color = when {
                atelier && isUser -> MaterialTheme.colorScheme.onSurface
                atelier -> androidx.compose.ui.graphics.Color.Transparent
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = when {
                atelier && isUser -> MaterialTheme.colorScheme.surface
                isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            // v4.6: long-press opens the message action sheet.
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 1f)
                .combinedClickable(onClick = {}, onLongClick = onShowActions),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (msg.attachments.isNotEmpty()) {
                    Text(
                        msg.attachments.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (isUser) {
                    // v4.6 (RTL fix): user text gets the same direction-aware
                    // rendering as the project chat (plain Text kept Persian
                    // paragraphs in the ambient direction/alignment).
                    BidiText(msg.content, style = MaterialTheme.typography.bodyMedium)
                } else {
                    MarkdownText(msg.content)
                }
                if (msg.error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        msg.error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, reasoning: String) {
    val atelier = com.anvex.app.ui.theme.LocalThemeStyle.current ==
        com.anvex.core.android.repo.UiThemeStyle.ATELIER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(if (atelier) 20.dp else 16.dp),
            color = if (atelier) androidx.compose.ui.graphics.Color.Transparent
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (reasoning.isNotBlank()) {
                    Text(
                        reasoning.take(600),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row {
                    BidiText(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun AttachRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnvexIconTile(icon, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One drawer row for a direct-chat conversation. The trailing overflow menu
 * holds pin / rename / archive / delete — delete moved here from the old
 * always-visible trash badge so the row stays readable. Pinned rows show a
 * pin glyph next to the title; archived rows reuse the same row with the
 * archive action flipped to unarchive.
 */
@Composable
private fun ConversationDrawerRow(
    conv: DirectChatViewModel.Conversation,
    selected: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conv.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (conv.pinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = tr("Pinned"),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(conv.lastAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
        badge = {
            Box {
                IconButton(
                    onClick = { onMenuOpenChange(true) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = tr("Conversation options"),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(tr(if (conv.pinned) "Unpin" else "Pin")) },
                        onClick = { onMenuOpenChange(false); onPin() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Rename")) },
                        onClick = { onMenuOpenChange(false); onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr(if (conv.archived) "Unarchive" else "Archive")) },
                        onClick = { onMenuOpenChange(false); onArchive() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Delete")) },
                        onClick = { onMenuOpenChange(false); onDelete() },
                    )
                }
            }
        },
        selected = selected,
        onClick = onSelect,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
