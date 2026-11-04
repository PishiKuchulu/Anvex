package com.anvex.app.vm

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.android.AppGraph
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.engine.ProjectSearch
import com.anvex.core.engine.codeintel.SymbolIndexCore
import com.anvex.core.engine.codeintel.SymbolOccurrence
import com.anvex.core.model.FileNode
import com.anvex.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor state machine: multi-file tabs, undo/redo, debounced autosave,
 * file tree operations (brief §7/20).
 *
 * v1.1 hardening (§21/§29):
 *  - large files beyond [EDITABLE_LIMIT] open READ-ONLY (no accidental OOM
 *    or jank from a TextFieldValue full of megabytes);
 *  - undo/redo stacks stay bounded (200 snapshots);
 *  - saves are journaled as USER_EDIT mutations so user code is never silently
 *    destroyed and the full change history survives restarts (§22/§24);
 *  - the active file feeds the AI context (§25) through AppGraph.
 */
class EditorViewModel(
    val projectId: String,
) : ViewModel() {

    private val graph = AppGraph.get()
    // UI actions are only reachable after init resolves this to the persisted workspace.
    private var fs: ProjectFileSystem = graph.projects.fileSystemFor(projectId)

    data class Tab(
        val path: String,
        val text: TextFieldValue,
        val saved: String,
        val undoStack: List<String> = emptyList(),
        val redoStack: List<String> = emptyList(),
        /** v1.1 §21: huge files open read-only — safeguard against jank/OOM. */
        val readOnly: Boolean = false,
    ) {
        val dirty: Boolean get() = text.text != saved
    }

    val tabs = MutableStateFlow<List<Tab>>(emptyList())

    // ---- F2a (P4): Tree-sitter highlighting + folds -------------------
    private val codeIntelligence by lazy { graph.androidRuntime.codeIntelligence() }
    private val tsJobs = mutableMapOf<String, Job>()
    private val tsParsed = mutableMapOf<String, com.anvex.core.engine.codeintel.ParsedDocument>()
    private val tsParsedSource = mutableMapOf<String, String>()

    /** A Tree-sitter document and the exact text it was parsed from. */
    data class TsUiState(
        val text: String,
        val document: com.anvex.core.engine.codeintel.ParsedDocument,
    )

    val tsStates = MutableStateFlow<Map<String, TsUiState>>(emptyMap())

    /** Folded ranges keyed by path + content hash (a text change expires them). */
    val foldedRanges =
        MutableStateFlow<Map<String, Set<com.anvex.core.engine.codeintel.FoldRange>>>(emptyMap())

    fun tsStateFor(path: String): TsUiState? = tsStates.value[path]

    fun foldedFor(path: String, text: String): Set<com.anvex.core.engine.codeintel.FoldRange> =
        foldedRanges.value[foldKey(path, text)] ?: emptySet()

    private fun foldKey(path: String, text: String): String = "$path#${text.hashCode()}"

    fun toggleFold(
        path: String,
        text: String,
        range: com.anvex.core.engine.codeintel.FoldRange,
    ) {
        val key = foldKey(path, text)
        val current = foldedRanges.value[key] ?: emptySet()
        foldedRanges.value = foldedRanges.value +
            (key to if (range in current) current - range else current + range)
    }

    private fun grammarFor(path: String): String? =
        when (com.anvex.app.ui.editor.CodeLanguage.fromPath(path)) {
            com.anvex.app.ui.editor.CodeLanguage.KOTLIN -> "kotlin"
            com.anvex.app.ui.editor.CodeLanguage.JAVA -> "java"
            com.anvex.app.ui.editor.CodeLanguage.TS -> "typescript"
            com.anvex.app.ui.editor.CodeLanguage.TSX -> "tsx"
            com.anvex.app.ui.editor.CodeLanguage.PYTHON -> "python"
            else -> null
        }

    private fun scheduleTsParse(path: String, text: String) {
        val language = grammarFor(path) ?: return
        if (!codeIntelligence.available || !codeIntelligence.supports(language)) return
        tsJobs.remove(path)?.cancel()
        tsJobs[path] = viewModelScope.launch {
            delay(120)
            val doc = runCatching {
                codeIntelligence.parse(tsParsed[path], tsParsedSource[path], language, text)
            }.getOrNull() ?: return@launch
            // Publish only when the tab still holds this exact text.
            if (tabs.value.firstOrNull { it.path == path }?.text?.text != text) return@launch
            tsParsed[path] = doc
            tsParsedSource[path] = text
            tsStates.value = tsStates.value + (path to TsUiState(text, doc))
        }
        scheduleLspSync(path, text)
    }

    // ------------------------------------------------- P9 (F2c): LSP

    private val lspManager by lazy { graph.androidRuntime.lspManager() }
    private val lspJobs = mutableMapOf<String, Job>()
    private val lspOpenDocs = HashSet<String>()
    private var lspStartAttempted = false

    /** Diagnostics published for the active file (empty when no LSP). */
    val lspDiagnostics = MutableStateFlow<List<com.anvex.core.android.runtime.lsp.LspDiagnostic>>(emptyList())

    private fun lspLanguageFor(path: String): String? =
        com.anvex.core.android.runtime.symbols.SymbolIndexManager.grammarFor(path)
            ?: when (path.substringAfterLast('.', "").lowercase()) {
                "json" -> "json"
                "js", "mjs", "cjs" -> "javascript"
                else -> null
            }

    /**
     * Starts the project's language server on first use and keeps the
     * server's copy of [path] in sync (didOpen once, didChange
     * debounced). Entirely best-effort: no server installed, no host,
     * or a failed start simply leaves the editor on Tree-sitter alone.
     */
    private fun scheduleLspSync(path: String, text: String) {
        val language = lspLanguageFor(path) ?: return
        val manager = lspManager ?: return
        lspJobs.remove(path)?.cancel()
        lspJobs[path] = viewModelScope.launch {
            delay(500)
            if (tabs.value.firstOrNull { it.path == path }?.text?.text != text) return@launch
            if (!manager.isReady()) {
                if (lspStartAttempted) return@launch
                lspStartAttempted = true
                val serverKey = when (language) {
                    "typescript", "tsx", "javascript", "json", "python" -> language
                    else -> return@launch
                }
                runCatching { manager.startFor(projectId, serverKey) }
                if (!manager.isReady()) return@launch
                collectLspDiagnostics()
            }
            if (path !in lspOpenDocs) {
                lspOpenDocs += path
                runCatching { manager.openDocument(path, language, text) }
            } else {
                runCatching { manager.changeDocument(path, text) }
            }
        }
    }

    private fun collectLspDiagnostics() {
        val manager = lspManager ?: return
        viewModelScope.launch {
            manager.diagnostics.collect { refreshLspDiagnostics() }
        }
        viewModelScope.launch {
            activePath.collect { refreshLspDiagnostics() }
        }
    }

    private fun refreshLspDiagnostics() {
        val manager = lspManager ?: return
        val path = activePath.value
        lspDiagnostics.value = if (path == null) {
            emptyList()
        } else {
            manager.diagnostics.value.entries
                .firstOrNull { it.key.endsWith("/$path") }?.value ?: emptyList()
        }
    }

    /** Hover text at the active tab's cursor, via the language server. */
    suspend fun lspHoverAtCursor(): String? {
        val manager = lspManager?.takeIf { it.isReady() } ?: return null
        val tab = tabs.value.firstOrNull { it.path == activePath.value } ?: return null
        val text = tab.text.text
        val cursor = tab.text.selection.min.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0
        var i = 0
        while (i < cursor) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
            i++
        }
        return runCatching {
            manager.hover(tab.path, line, cursor - lineStart)
        }.getOrNull()
    }
    val activePath = MutableStateFlow<String?>(null)

    val treeRevision = MutableStateFlow(0)

    val project: StateFlow<Project?> = graph.projects.observeAll()
        .map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Debounced autosave jobs, one per path (a single shared job let editing file B cancel file A's pending save). */
    private val saveJobs = HashMap<String, Job>()

    /** Last undo-snapshot time per tab, for edit-burst coalescing. */
    private val undoBurstAt = HashMap<String, Long>()

    private val _fileTree = MutableStateFlow<FileNode?>(null)
    val fileTreeState: StateFlow<FileNode?> = _fileTree

    init {
        // Do not expose the editor tree until the persisted workspace URI has been
        // resolved. This removes the SAF/app-private filesystem race on first open.
        viewModelScope.launch(Dispatchers.IO) {
            fs = graph.projects.fileSystemForAsync(projectId)
            val tree = runCatching { graph.projects.treeAsync(projectId) }.getOrNull()
            withContext(Dispatchers.Main.immediate) { _fileTree.value = tree }
        }
    }

    fun refreshTree() {
        viewModelScope.launch(Dispatchers.IO) {
            val tree = runCatching { graph.projects.treeAsync(projectId) }.getOrNull()
            withContext(Dispatchers.Main.immediate) { _fileTree.value = tree }
        }
    }

    fun openFile(path: String) {
        val safe = PathSanitizer.sanitizeOrNull(path) ?: return
        if (tabs.value.any { it.path == safe }) {
            activePath.value = safe
            graph.setActiveFile(projectId, safe)
            return
        }
        // Review B3: this used to read the whole file synchronously on
        // the calling (main) thread and only then compare against the
        // editable cap. The read now runs on IO and the size is known
        // before the content is loaded.
        viewModelScope.launch(Dispatchers.IO) {
            if (fs.isDirectory(safe)) return@launch
            val knownSize = fs.fileSize(safe)
            // Audit fix (2026-11-03): the size was known but the whole
            // file was still read into memory just to mark the tab
            // read-only — the exact OOM/jank the cap exists to prevent.
            // Oversized files now open as a placeholder without a read.
            val tooLarge = knownSize != null && knownSize > EDITABLE_LIMIT
            val content = if (tooLarge) {
                "This file is too large to edit here (${(knownSize ?: 0L) / 1024} KB). " +
                    "It was opened read-only; its contents were not loaded."
            } else {
                fs.readFile(safe) ?: ""
            }
            val readOnly = tooLarge || content.length > EDITABLE_LIMIT
            withContext(Dispatchers.Main.immediate) {
                if (tabs.value.any { it.path == safe }) {
                    activePath.value = safe
                    return@withContext
                }
                tabs.value = tabs.value + Tab(safe, TextFieldValue(content), content, readOnly = readOnly)
                activePath.value = safe
                graph.setActiveFile(projectId, safe)
                if (!readOnly) scheduleTsParse(safe, content)
            }
        }
    }

    fun closeTab(path: String) {
        val t = tabs.value.firstOrNull { it.path == path } ?: return
        tsJobs.remove(path)?.cancel()
        tsParsed.remove(path)?.let { codeIntelligence.release(it) }
        tsParsedSource.remove(path)
        tsStates.value = tsStates.value - path
        if (t.dirty) saveNow(t.path)
        tabs.value = tabs.value.filterNot { it.path == path }
        if (activePath.value == path) {
            activePath.value = tabs.value.lastOrNull()?.path
        }
    }

    fun setActive(path: String) {
        if (tabs.value.any { it.path == path }) {
            activePath.value = path
            graph.setActiveFile(projectId, path)
        }
    }

    fun updateField(path: String, field: TextFieldValue) {
        if (tabs.value.firstOrNull { it.path == path }?.readOnly == true) return
        updateTab(path) { tab ->
            val current = tab.text.text
            val next = field.text
            // Undo coalescing (audit 2026-11-03): the old rule compared
            // the stack top against the NEW text, which is true on
            // virtually every keystroke — a 200-entry stack of
            // one-character steps, so Undo crawled character by
            // character. Snapshot once per edit burst instead: at most
            // one push per UNDO_BURST_MS, plus a push at line breaks.
            val now = System.currentTimeMillis()
            val shouldPush = next != current &&
                (tab.undoStack.isEmpty() ||
                    now - (undoBurstAt[path] ?: 0L) >= UNDO_BURST_MS ||
                    next.contains('\n'))
            if (shouldPush) {
                undoBurstAt[path] = now
                tab.copy(
                    text = field,
                    undoStack = (tab.undoStack + current).takeLast(200),
                    redoStack = emptyList(),
                )
            } else {
                tab.copy(text = field)
            }
        }
        scheduleTsParse(path, field.text)
        scheduleSave(path)
    }

    fun undo(path: String) {
        undoBurstAt[path] = 0L
        updateTab(path) { tab ->
            val prev = tab.undoStack.lastOrNull() ?: return@updateTab tab
            tab.copy(
                text = tab.text.copy(text = prev),
                undoStack = tab.undoStack.dropLast(1),
                redoStack = (tab.redoStack + tab.text.text).takeLast(200),
            )
        }
        scheduleSave(path)
    }

    fun redo(path: String) {
        undoBurstAt[path] = 0L
        updateTab(path) { tab ->
            val next = tab.redoStack.lastOrNull() ?: return@updateTab tab
            tab.copy(
                text = tab.text.copy(text = next),
                redoStack = tab.redoStack.dropLast(1),
                undoStack = (tab.undoStack + tab.text.text).takeLast(200),
            )
        }
        scheduleSave(path)
    }

    fun replaceAll(path: String, search: String, replacement: String): Int {
        if (search.isEmpty()) return 0
        var count = 0
        updateTab(path) { tab ->
            val text = tab.text.text
            count = Regex(Regex.escape(search)).findAll(text).count()
            if (count == 0) return@updateTab tab
            val replaced = text.replace(search, replacement)
            tab.copy(
                text = tab.text.copy(text = replaced),
                undoStack = (tab.undoStack + text).takeLast(200),
                redoStack = emptyList(),
            )
        }
        scheduleSave(path)
        return count
    }

    fun findMatches(path: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val text = tabs.value.firstOrNull { it.path == path }?.text?.text ?: return emptyList()
        val out = ArrayList<Int>()
        var idx = 0
        while (true) {
            val found = text.indexOf(query, idx, ignoreCase = false)
            if (found == -1) break
            out.add(found)
            idx = found + query.length
            if (out.size > 5000) break
        }
        return out
    }


    // Suspend + IO (audit 2026-11-03): both entry points used to run a
    // whole-project scan/write synchronously on the caller's thread —
    // from the editor screen, the main thread.
    suspend fun projectSearch(query: String, caseSensitive: Boolean = false): List<ProjectSearch.Match> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) emptyList()
            else runCatching { ProjectSearch.search(fs, query.trim(), caseSensitive) }.getOrDefault(emptyList())
        }

    suspend fun projectReplaceAll(query: String, replacement: String, caseSensitive: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext 0
            // Journaling + tab sync (audit 2026-11-03): replace-all used
            // to write straight to disk with NO journal entry — invisible
            // to Review/undo — and open tabs kept stale buffers and saved
            // snapshots, so a later autosave silently reverted the whole
            // replacement. Capture befores first, journal each changed
            // file as USER_EDIT, then resync open tabs.
            val matchedPaths = runCatching { ProjectSearch.search(fs, query.trim(), caseSensitive) }
                .getOrDefault(emptyList()).map { it.path }.distinct()
            val befores = matchedPaths.associateWith { runCatching { fs.readFile(it) }.getOrNull() }
            val results = runCatching { ProjectSearch.replaceAll(fs, query, replacement, caseSensitive) }
                .getOrDefault(emptyList())
            var total = 0
            val afters = HashMap<String, String?>()
            for (result in results) {
                total += result.replacements
                val after = runCatching { fs.readFile(result.path) }.getOrNull()
                afters[result.path] = after
                graph.recordUserTreeMutation(projectId, com.anvex.core.model.FileMutation(
                    path = result.path, operation = com.anvex.core.model.MutationOp.MODIFY,
                    before = befores[result.path], after = after,
                    source = com.anvex.core.model.MutationSource.USER_EDIT,
                ))
            }
            if (total > 0) {
                withContext(Dispatchers.Main.immediate) { syncTabsAfterBulkChange(afters) }
                treeRevision.value++; refreshTree()
            }
            total
        }

    // ------------------------------------------------- P8 (F2b): symbols

    private val symbolManager by lazy { graph.androidRuntime.symbolIndexManager() }

    /**
     * The identifier at the active tab's cursor (or the current
     * selection when it is exactly an identifier), for Find References
     * and Rename Symbol.
     */
    fun symbolAtCursor(): String? {
        val tab = tabs.value.firstOrNull { it.path == activePath.value } ?: return null
        val value = tab.text
        val text = value.text
        if (text.isEmpty()) return null
        val selection = value.selection
        if (!selection.collapsed) {
            val selected = text.substring(selection.min, selection.max)
            return selected.takeIf { SymbolIndexCore.isValidIdentifier(it) }
        }
        val cursor = selection.min
        var start = cursor
        while (start > 0 && SymbolIndexCore.isIdentifierPart(text[start - 1])) start--
        var end = cursor
        while (end < text.length && SymbolIndexCore.isIdentifierPart(text[end])) end++
        if (start == end) return null
        return text.substring(start, end).takeIf { it.isNotEmpty() }
    }

    /** Fresh-index references for [name] across the project. */
    suspend fun findSymbolReferences(name: String): List<SymbolOccurrence> =
        withContext(Dispatchers.IO) {
            val manager = symbolManager ?: return@withContext emptyList()
            runCatching {
                manager.indexProject(projectId)
                manager.findReferences(projectId, name)
            }.getOrDefault(emptyList())
        }

    /** Dry-run rename plan (occurrence/file counts for the dialog). */
    suspend fun symbolRenamePreview(
        name: String,
        newName: String,
    ): com.anvex.core.android.runtime.symbols.RenamePlan? =
        withContext(Dispatchers.IO) {
            val manager = symbolManager ?: return@withContext null
            runCatching {
                manager.indexProject(projectId)
                manager.renamePlan(projectId, name, newName)
            }.getOrNull()
        }

    /**
     * Applies a symbol rename through the same journaled bulk-edit
     * pattern as project replace-all: befores captured, each changed
     * file recorded as a USER_EDIT mutation (Review-visible, undoable),
     * then open tabs resynced. Returns the number of occurrences
     * replaced, 0 when the plan is empty/invalid.
     */
    suspend fun applySymbolRename(name: String, newName: String): Int =
        withContext(Dispatchers.IO) {
            val manager = symbolManager ?: return@withContext 0
            val plan = runCatching { manager.renamePlan(projectId, name, newName) }
                .getOrNull() ?: return@withContext 0
            var total = 0
            val afters = HashMap<String, String?>()
            for ((path, newText) in plan.edits) {
                val before = runCatching { fs.readFile(path) }.getOrNull()
                val occurrences = before?.let {
                    SymbolIndexCore.findOccurrences(it, name).size
                } ?: 0
                if (occurrences == 0) continue
                runCatching { fs.writeFile(path, newText) }.getOrNull() ?: continue
                total += occurrences
                afters[path] = newText
                graph.recordUserTreeMutation(projectId, com.anvex.core.model.FileMutation(
                    path = path, operation = com.anvex.core.model.MutationOp.MODIFY,
                    before = before, after = newText,
                    source = com.anvex.core.model.MutationSource.USER_EDIT,
                ))
            }
            if (total > 0) {
                runCatching { manager.indexProject(projectId) }
                withContext(Dispatchers.Main.immediate) { syncTabsAfterBulkChange(afters) }
                treeRevision.value++; refreshTree()
            }
            total
        }

    /** Opens [path] and places the cursor at [offset] once loaded. */
    fun openFileAt(path: String, offset: Int) {
        openFile(path)
        viewModelScope.launch {
            repeat(60) {
                val tab = tabs.value.firstOrNull { it.path == path }
                if (tab != null) {
                    val clamped = offset.coerceIn(0, tab.text.text.length)
                    updateTab(path) {
                        it.copy(text = it.text.copy(selection = androidx.compose.ui.text.TextRange(clamped)))
                    }
                    setActive(path)
                    return@launch
                }
                delay(50)
            }
        }
    }

    /**
     * Resync open tabs after an on-disk bulk change (project replace-all):
     * clean tabs reload from disk and drop their (now meaningless) undo
     * stacks; dirty tabs keep their text but their saved snapshot moves to
     * the new disk content, so the dirty flag and the next save's
     * journaled before stay truthful instead of reverting the change.
     */
    private fun syncTabsAfterBulkChange(afters: Map<String, String?>) {
        if (afters.isEmpty()) return
        tabs.value = tabs.value.map { tab ->
            if (tab.path !in afters) return@map tab
            val disk = afters[tab.path] ?: return@map tab
            if (tab.dirty) {
                tab.copy(saved = disk)
            } else {
                tab.copy(
                    text = TextFieldValue(disk), saved = disk,
                    undoStack = emptyList(), redoStack = emptyList(),
                )
            }
        }
    }

    private fun scheduleSave(path: String) {
        saveJobs[path]?.cancel()
        saveJobs[path] = viewModelScope.launch {
            delay(700) // debounced autosave
            saveNow(path)
        }
    }

    fun saveNow(path: String) {
        val tab = tabs.value.firstOrNull { it.path == path } ?: return
        if (tab.readOnly) return
        val written = tab.text.text
        viewModelScope.launch(Dispatchers.IO) {
            // Journal the user edit (§22/§24) — user changes are traceable and
            // the original content is recoverable, never silently overwritten.
            val before = fs.readFile(path)
            fs.writeFile(path, written)
            graph.recordUserEdit(projectId, path, before, written)
            withContext(Dispatchers.Main) {
                val current = tabs.value.firstOrNull { it.path == path }
                when {
                    current == null -> Unit
                    current.text.text == written -> updateTab(path) { it.copy(saved = written) }
                    else -> {
                        // The user typed during the write. The old code
                        // stamped the NEW text as saved anyway (snapshot
                        // inversion): the newer edits never reached disk
                        // yet the tab looked clean. Reschedule instead.
                        scheduleSave(path)
                    }
                }
                treeRevision.value++; refreshTree()
            }
        }
    }

    // ---- file tree operations ---------------------------------------------

    fun createFile(dirPath: String, name: String): String? = modifyTree {
        val safe = joinPath(dirPath, name) ?: return@modifyTree "Invalid name"
        if (fs.exists(safe)) return@modifyTree "A file named '$name' already exists"
        fs.writeFile(safe, "")
        graph.recordUserTreeMutation(projectId, com.anvex.core.model.FileMutation(
            path = safe, operation = com.anvex.core.model.MutationOp.CREATE,
            before = null, after = "", source = com.anvex.core.model.MutationSource.USER_EDIT
        ))
        openFile(safe)
        null
    }

    fun createFolder(dirPath: String, name: String): String? = modifyTree {
        val safe = joinPath(dirPath, name) ?: return@modifyTree "Invalid name"
        fs.mkdirs(safe)
        null
    }

    fun renameEntry(from: String, newName: String): String? = modifyTree {
        val safeFrom = PathSanitizer.sanitizeOrNull(from) ?: return@modifyTree "Invalid path"
        val parent = PathSanitizer.parent(safeFrom) ?: ""
        val safeTo = joinPath(parent, newName) ?: return@modifyTree "Invalid name"
        if (fs.exists(safeTo)) return@modifyTree "Destination exists"
        val before = if (fs.isDirectory(safeFrom)) null else fs.readFile(safeFrom)
        if (!fs.move(safeFrom, safeTo)) return@modifyTree "Rename failed"
        if (before != null) graph.recordUserTreeMutation(projectId, com.anvex.core.model.FileMutation(
            path = safeFrom, operation = com.anvex.core.model.MutationOp.RENAME,
            before = before, after = before, source = com.anvex.core.model.MutationSource.USER_EDIT, renamedTo = safeTo
        ))
        tabs.value = tabs.value.map { tab ->
            if (tab.path == safeFrom) tab.copy(path = safeTo) else tab
        }
        if (activePath.value == safeFrom) activePath.value = safeTo
        null
    }

    fun deleteEntry(path: String): String? = modifyTree {
        val safe = PathSanitizer.sanitizeOrNull(path) ?: return@modifyTree "Invalid path"
        val before = if (fs.isDirectory(safe)) null else fs.readFile(safe)
        if (!fs.delete(safe, recursive = true)) return@modifyTree "Delete failed"
        if (before != null) graph.recordUserTreeMutation(projectId, com.anvex.core.model.FileMutation(
            path = safe, operation = com.anvex.core.model.MutationOp.DELETE,
            before = before, after = null, source = com.anvex.core.model.MutationSource.USER_EDIT
        ))
        closeTabSilently(safe)
        null
    }

    fun duplicateEntry(path: String): String? = modifyTree {
        val safe = PathSanitizer.sanitizeOrNull(path) ?: return@modifyTree "Invalid path"
        if (fs.isDirectory(safe)) return@modifyTree "Folders cannot be duplicated yet"
        val dot = safe.lastIndexOf('.')
        val copy = if (dot > safe.lastIndexOf('/')) {
            safe.substring(0, dot) + "-copy" + safe.substring(dot)
        } else "$safe-copy"
        if (fs.exists(copy)) return@modifyTree "Copy already exists"
        val content = fs.readFile(safe) ?: return@modifyTree "Could not read source file"
        if (!fs.copy(safe, copy)) return@modifyTree "Copy failed"
        graph.recordUserTreeMutation(projectId, com.anvex.core.model.FileMutation(
            path = copy, operation = com.anvex.core.model.MutationOp.CREATE,
            before = null, after = content, source = com.anvex.core.model.MutationSource.USER_EDIT
        ))
        null
    }

    private fun closeTabSilently(path: String) {
        tabs.value = tabs.value.filterNot { it.path == path }
        if (activePath.value == path) activePath.value = tabs.value.lastOrNull()?.path
    }

    private fun joinPath(dir: String, name: String): String? {
        if (name.isBlank()) return null
        val clean = name.trim().replace('\\', '/')
        if (clean.contains('/') || clean.contains("..")) return null
        return if (dir.isBlank()) clean else "$dir/$clean"
    }

    private fun modifyTree(block: () -> String?): String? {
        val error = try {
            block()
        } catch (e: Exception) {
            e.message
        }
        if (error == null) treeRevision.value++; refreshTree()
        return error
    }

    private inline fun updateTab(path: String, transform: (Tab) -> Tab) {
        tabs.value = tabs.value.map { if (it.path == path) transform(it) else it }
    }

    /** Suspend + IO: this read used to run on the caller's thread. */
    suspend fun readProjectFile(path: String): String? = withContext(Dispatchers.IO) {
        PathSanitizer.sanitizeOrNull(path)?.let { fs.readFile(it) }
    }

    companion object {
        /** Undo snapshot burst window: at most one snapshot per tab per window (plus line breaks). */
        private const val UNDO_BURST_MS = 700L

        /** Files beyond 1 MB open read-only (mobile memory safeguard). */
        const val EDITABLE_LIMIT = 1_048_576

        /** Syntax highlighting cutoff — bigger files render plain (no jank). */
        const val HIGHLIGHT_LIMIT = 262_144
    }

    override fun onCleared() {
        // Review B2: viewModelScope is already cancelled by the time
        // onCleared() runs, so the old saveNow() calls here never
        // executed — edits inside the 700 ms autosave debounce were
        // silently lost when the editor was popped. Flush dirty tabs on
        // an independent scope (same journaled write as saveNow, minus
        // the UI updates that no longer matter).
        val dirty = tabs.value.filter { it.dirty && !it.readOnly }
        if (dirty.isNotEmpty()) {
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
            ).launch {
                dirty.forEach { tab ->
                    val before = runCatching { fs.readFile(tab.path) }.getOrNull()
                    runCatching { fs.writeFile(tab.path, tab.text.text) }
                    runCatching { graph.recordUserEdit(projectId, tab.path, before, tab.text.text) }
                }
            }
        }
        super.onCleared()
    }
}
