package com.anvex.app.vm

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.android.AppGraph
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.engine.ProjectSearch
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
    val activePath = MutableStateFlow<String?>(null)

    val treeRevision = MutableStateFlow(0)

    val project: StateFlow<Project?> = graph.projects.observeAll()
        .map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var saveJob: Job? = null

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
        if (fs.isDirectory(safe)) return
        if (tabs.value.any { it.path == safe }) {
            activePath.value = safe
            graph.setActiveFile(projectId, safe)
            return
        }
        val content = fs.readFile(safe) ?: ""
        val readOnly = content.length > EDITABLE_LIMIT
        tabs.value = tabs.value + Tab(safe, TextFieldValue(content), content, readOnly = readOnly)
        activePath.value = safe
        graph.setActiveFile(projectId, safe)
    }

    fun closeTab(path: String) {
        val t = tabs.value.firstOrNull { it.path == path } ?: return
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
            // coalesce undo entries: push snapshot when this is a "new" edit burst
            val shouldPush = tab.undoStack.isEmpty() || tab.undoStack.last() != next
            if (next != current && shouldPush) {
                tab.copy(
                    text = field,
                    undoStack = (tab.undoStack + current).takeLast(200),
                    redoStack = emptyList(),
                )
            } else {
                tab.copy(text = field)
            }
        }
        scheduleSave(path)
    }

    fun undo(path: String) = updateTab(path) { tab ->
        val prev = tab.undoStack.lastOrNull() ?: return@updateTab tab
        tab.copy(
            text = tab.text.copy(text = prev),
            undoStack = tab.undoStack.dropLast(1),
            redoStack = (tab.redoStack + tab.text.text).takeLast(200),
        )
    }.also { scheduleSave(path) }

    fun redo(path: String) = updateTab(path) { tab ->
        val next = tab.redoStack.lastOrNull() ?: return@updateTab tab
        tab.copy(
            text = tab.text.copy(text = next),
            redoStack = tab.redoStack.dropLast(1),
            undoStack = (tab.undoStack + tab.text.text).takeLast(200),
        )
    }.also { scheduleSave(path) }

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


    fun projectSearch(query: String, caseSensitive: Boolean = false): List<ProjectSearch.Match> {
        if (query.isBlank()) return emptyList()
        return runCatching { ProjectSearch.search(fs, query.trim(), caseSensitive) }.getOrDefault(emptyList())
    }

    fun projectReplaceAll(query: String, replacement: String, caseSensitive: Boolean = false): Int {
        if (query.isBlank()) return 0
        return runCatching { ProjectSearch.replaceAll(fs, query, replacement, caseSensitive).sumOf { it.replacements } }.getOrDefault(0).also {
            if (it > 0) { treeRevision.value++; refreshTree() }
        }
    }
    private fun scheduleSave(path: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700) // debounced autosave
            saveNow(path)
        }
    }

    fun saveNow(path: String) {
        val tab = tabs.value.firstOrNull { it.path == path } ?: return
        if (tab.readOnly) return
        viewModelScope.launch(Dispatchers.IO) {
            // Journal the user edit (§22/§24) — user changes are traceable and
            // the original content is recoverable, never silently overwritten.
            val before = fs.readFile(path)
            fs.writeFile(path, tab.text.text)
            graph.recordUserEdit(projectId, path, before, tab.text.text)
            withContext(Dispatchers.Main) {
                updateTab(path) { it.copy(saved = tab.text.text) }
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

    fun readProjectFile(path: String): String? =
        PathSanitizer.sanitizeOrNull(path)?.let { fs.readFile(it) }

    companion object {
        /** Files beyond 1 MB open read-only (mobile memory safeguard). */
        const val EDITABLE_LIMIT = 1_048_576

        /** Syntax highlighting cutoff — bigger files render plain (no jank). */
        const val HIGHLIGHT_LIMIT = 262_144
    }

    override fun onCleared() {
        tabs.value.filter { it.dirty }.forEach { saveNow(it.path) }
        super.onCleared()
    }
}
