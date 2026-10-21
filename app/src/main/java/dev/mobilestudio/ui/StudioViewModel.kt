package dev.mobilestudio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.Environment
import dev.mobilestudio.build.ApkSignerAdapter
import dev.mobilestudio.build.BuildEngine
import dev.mobilestudio.build.BuildResult
import dev.mobilestudio.build.D8Dexer
import dev.mobilestudio.build.EcjJavaRunner
import dev.mobilestudio.build.JvmSourceCompiler
import dev.mobilestudio.build.K2KotlinRunner
import dev.mobilestudio.build.KeystoreAsset
import dev.mobilestudio.build.PipelineBuildEngine
import dev.mobilestudio.build.Toolchain
import dev.mobilestudio.build.ToolchainStore
import dev.mobilestudio.data.FolderBrowser
import dev.mobilestudio.data.Language
import dev.mobilestudio.data.ProjectRepository
import dev.mobilestudio.data.RecentFolders
import dev.mobilestudio.data.ProjectSearch
import dev.mobilestudio.data.SearchHit
import dev.mobilestudio.data.TreeNode
import dev.mobilestudio.editor.FindOptions
import dev.mobilestudio.editor.FindReplace
import dev.mobilestudio.editor.TextPositions
import dev.mobilestudio.editor.UndoStack
import dev.mobilestudio.terminal.BuiltinCommand
import dev.mobilestudio.terminal.CommandHistory
import dev.mobilestudio.terminal.ShellSession
import dev.mobilestudio.terminal.TerminalBuffer
import dev.mobilestudio.terminal.TerminalCommands
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * One editor tab. [revision] is bumped whenever the text or selection is changed from outside the
 * editor (undo, replace, jump to a search hit) so the editor knows to reload instead of keeping its own state.
 */
data class OpenFile(
    val file: File,
    val text: String,
    val savedText: String,
    val undo: UndoStack = UndoStack(text),
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val revision: Int = 0,
) {
    val dirty: Boolean get() = text != savedText
}

data class FindState(
    val options: FindOptions = FindOptions(""),
    val replacement: String = "",
    val matchCount: Int = 0,
    val error: String? = null,
)

/** The "Open folder" dialog: [dir] is the folder being browsed, [entries] its sub-folders. */
data class FolderPickerState(
    val dir: File,
    val entries: List<File> = emptyList(),
    val error: String? = null,
    val looksLikeProject: Boolean = false,
    val atTop: Boolean = false,
)

data class ToolchainInfo(
    val aapt2: Boolean = false,
    val androidJar: Boolean = false,
    val libs: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
)

data class TerminalState(val visible: Boolean = false, val text: String = "", val running: Boolean = false)

enum class ToolKind { AAPT2, ANDROID_JAR, LIB }

data class SearchState(val hits: List<SearchHit>? = null, val error: String? = null, val running: Boolean = false)

data class StudioState(
    val projects: List<File> = emptyList(),
    val project: File? = null,
    val tree: List<TreeNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val tabs: List<OpenFile> = emptyList(),
    val active: Int = -1,
    val buildLog: List<String> = emptyList(),
    val building: Boolean = false,
    val builtApk: File? = null,
    val find: FindState? = null,
    val search: SearchState? = null,
    val recent: List<File> = emptyList(),
    val folderPicker: FolderPickerState? = null,
    val toolchain: ToolchainInfo = ToolchainInfo(),
    val toolchainDialog: Boolean = false,
    val terminal: TerminalState = TerminalState(),
    val message: String? = null,
)

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    private companion object { const val PREF_RECENT = "recent_folders" }


    private val app: Application = app
    private val workspace = ProjectRepository(File(app.filesDir, "workspace"))

    /** Repository used for file operations of the open project: the workspace, or the opened folder. */
    private var repo: ProjectRepository = workspace

    private val prefs = app.getSharedPreferences("studio", Context.MODE_PRIVATE)
    private val toolchainStore = ToolchainStore(File(app.filesDir, "toolchain"))

    private fun currentToolchain(): Toolchain {
        // A bundled aapt2 (jniLibs/<abi>/libaapt2.so) wins; otherwise the imported one. Executing from app
        // storage works because the app targets API 28 (see README).
        val bundled = File(app.applicationInfo.nativeLibraryDir, "libaapt2.so")
        return Toolchain(
            aapt2 = if (bundled.isFile) bundled else toolchainStore.aapt2,
            androidJar = toolchainStore.androidJar,
            libs = toolchainStore.libs(),
        )
    }

    private val engine: BuildEngine = PipelineBuildEngine(
        toolchain = ::currentToolchain,
        workRoot = File(app.cacheDir, "build"),
        outputDir = File(app.filesDir, "outputs"),
        compiler = JvmSourceCompiler(K2KotlinRunner(), EcjJavaRunner()),
        dexer = D8Dexer(),
        signer = ApkSignerAdapter(keystore = { KeystoreAsset.decode(app.assets.open("debug.p12.b64").use { it.readBytes() }).inputStream() }),
    )

    private val _state = MutableStateFlow(
        StudioState(
            projects = workspace.projects(),
            recent = RecentFolders.prune(RecentFolders.decode(prefs.getString(PREF_RECENT, null))).map(::File),
            toolchain = readToolchainInfo(),
        )
    )
    val state: StateFlow<StudioState> = _state.asStateFlow()

    // ---- projects -------------------------------------------------------------------------

    fun createProject(name: String, packageName: String, language: Language = Language.KOTLIN) {
        viewModelScope.launch(Dispatchers.IO) {
            workspace.createProject(name.trim(), packageName.trim(), language)
                .onSuccess { dir ->
                    _state.update { it.copy(projects = workspace.projects()) }
                    openProject(dir)
                }
                .onFailure { toast(it.message) }
        }
    }

    /** Opens a project that lives in the app's own workspace. */
    fun openProject(dir: File) {
        repo = workspace
        beginProject(dir)
    }

    /** Opens any folder on shared storage as a project; file access is limited to that folder. */
    fun openFolder(dir: File) {
        if (!dir.isDirectory || !dir.canRead()) {
            toast("Cannot read ${dir.path}")
            return
        }
        if (FolderBrowser.isTooBroad(dir, storageRoot)) {
            toast("Pick a project folder, not the whole storage")
            return
        }
        repo = ProjectRepository(dir)
        val recent = RecentFolders.add(RecentFolders.decode(prefs.getString(PREF_RECENT, null)), dir.path)
        prefs.edit().putString(PREF_RECENT, RecentFolders.encode(recent)).apply()
        _state.update { it.copy(recent = recent.map(::File), folderPicker = null) }
        beginProject(dir)
    }

    private fun beginProject(dir: File) {
        val expanded = setOf(dir.path)
        stopShell()
        viewModelScope.launch(Dispatchers.IO) {
            val tree = repo.tree(dir, expanded)
            _state.update {
                it.copy(
                    project = dir, tree = tree, expanded = expanded,
                    tabs = emptyList(), active = -1, buildLog = emptyList(),
                    builtApk = null, find = null, search = null,
                    terminal = TerminalState(visible = it.terminal.visible),
                )
            }
            if (_state.value.terminal.visible) startShell()
        }
    }

    fun closeProject() {
        stopShell()
        repo = workspace
        _state.update {
            it.copy(project = null, tree = emptyList(), tabs = emptyList(), active = -1, buildLog = emptyList(),
                builtApk = null, find = null, search = null, projects = workspace.projects(),
                terminal = TerminalState())
        }
    }

    fun deleteProject(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            workspace.deleteProject(dir)
                .onSuccess { _state.update { it.copy(projects = workspace.projects()) } }
                .onFailure { toast(it.message) }
        }
    }

    // ---- open-folder dialog ---------------------------------------------------------------

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    fun openFolderPicker(start: File? = null) = browseTo(start?.takeIf { it.isDirectory } ?: storageRoot)

    fun closeFolderPicker() = _state.update { it.copy(folderPicker = null) }

    fun browseTo(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val listing = FolderBrowser.list(dir)
            val atTop = FolderBrowser.parentOf(dir, storageRoot) == null
            _state.update {
                it.copy(
                    folderPicker = FolderPickerState(
                        dir = dir,
                        entries = listing.getOrDefault(emptyList()),
                        error = listing.exceptionOrNull()?.message,
                        looksLikeProject = FolderBrowser.looksLikeAndroidProject(dir),
                        atTop = atTop,
                    )
                )
            }
        }
    }

    fun browseUp() {
        val dir = _state.value.folderPicker?.dir ?: return
        FolderBrowser.parentOf(dir, storageRoot)?.let(::browseTo)
    }

    // ---- toolchain ------------------------------------------------------------------------

    private fun readToolchainInfo(): ToolchainInfo {
        val tc = currentToolchain()
        return ToolchainInfo(
            aapt2 = tc.aapt2.isFile && tc.aapt2.canExecute(),
            androidJar = tc.androidJar.isFile,
            libs = tc.libs.map { it.name },
            problems = tc.problems(),
        )
    }

    fun openToolchain() = _state.update { it.copy(toolchainDialog = true, toolchain = readToolchainInfo()) }

    fun closeToolchain() = _state.update { it.copy(toolchainDialog = false) }

    /** Copies a user-picked file into the toolchain folder. [open] is called once on a background thread. */
    fun importTool(kind: ToolKind, fileName: String, open: () -> InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { open() }.mapCatching { input ->
                when (kind) {
                    ToolKind.AAPT2 -> toolchainStore.importAapt2(input).getOrThrow()
                    ToolKind.ANDROID_JAR -> toolchainStore.importAndroidJar(input).getOrThrow()
                    ToolKind.LIB -> toolchainStore.importLib(input, fileName).getOrThrow()
                }
            }
            result.onSuccess { toast("Imported ${it.name}") }.onFailure { toast(it.message) }
            _state.update { it.copy(toolchain = readToolchainInfo()) }
        }
    }

    fun removeLib(name: String) {
        toolchainStore.removeLib(name)
        _state.update { it.copy(toolchain = readToolchainInfo()) }
    }

    // ---- file tree ------------------------------------------------------------------------

    fun toggleDir(dir: File) {
        val s = _state.value
        val expanded = if (dir.path in s.expanded) s.expanded - dir.path else s.expanded + dir.path
        val project = s.project ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val tree = repo.tree(project, expanded)
            _state.update { it.copy(expanded = expanded, tree = tree) }
        }
    }

    private suspend fun refreshTree() {
        val s = _state.value
        val project = s.project ?: return
        val tree = repo.tree(project, s.expanded)
        _state.update { it.copy(tree = tree) }
    }

    fun createFile(parent: File, name: String) = mutate { repo.createFile(parent, name.trim()).map { openFileNow(it) } }

    fun createDirectory(parent: File, name: String) = mutate {
        repo.createDirectory(parent, name.trim()).onSuccess {
            _state.update { s -> s.copy(expanded = s.expanded + parent.path) }
        }
    }

    fun rename(file: File, newName: String) = mutate {
        repo.rename(file, newName.trim()).onSuccess { dropTabsUnder(file) }
    }

    fun delete(file: File) = mutate {
        repo.delete(file).onSuccess { dropTabsUnder(file) }
    }

    private fun mutate(block: suspend () -> Result<*>) {
        viewModelScope.launch(Dispatchers.IO) {
            block().onFailure { toast(it.message) }
            refreshTree()
        }
    }

    private fun dropTabsUnder(path: File) {
        _state.update { s ->
            val keep = s.tabs.filterNot { it.file.path == path.path || it.file.path.startsWith(path.path + File.separator) }
            s.copy(tabs = keep, active = keep.lastIndex.coerceAtMost(s.active).coerceAtLeast(if (keep.isEmpty()) -1 else 0))
        }
    }

    // ---- editor ---------------------------------------------------------------------------

    fun openFile(file: File) = openFileAt(file, line = 0, column = 0)

    /** Opens [file] (or switches to it) and puts the cursor at 1-based [line]/[column]; line 0 means "keep as is". */
    fun openFileAt(file: File, line: Int, column: Int) {
        val existing = _state.value.tabs.indexOfFirst { it.file == file }
        if (existing >= 0) {
            _state.update { s ->
                val tabs = s.tabs.toMutableList()
                if (line > 0) tabs[existing] = moveSelection(tabs[existing], line, column)
                s.copy(tabs = tabs, active = existing)
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) { openFileNow(file, line, column) }
    }

    private fun moveSelection(tab: OpenFile, line: Int, column: Int): OpenFile {
        val offset = TextPositions.offsetOf(tab.text, line, column)
        return tab.copy(selStart = offset, selEnd = offset, revision = tab.revision + 1)
    }

    private fun openFileNow(file: File, line: Int = 0, column: Int = 0) {
        repo.read(file)
            .onSuccess { text ->
                _state.update {
                    var tab = OpenFile(file, text, text)
                    if (line > 0) tab = moveSelection(tab, line, column)
                    val tabs = it.tabs + tab
                    it.copy(tabs = tabs, active = tabs.lastIndex)
                }
            }
            .onFailure { toast(it.message) }
    }

    fun selectTab(index: Int) = _state.update { it.copy(active = index) }

    fun closeTab(index: Int) {
        _state.update { s ->
            val tabs = s.tabs.toMutableList().also { it.removeAt(index) }
            val active = when {
                tabs.isEmpty() -> -1
                s.active > index -> s.active - 1
                else -> s.active.coerceAtMost(tabs.lastIndex)
            }
            s.copy(tabs = tabs, active = active)
        }
    }

    private fun activeTab(): OpenFile? = _state.value.let { it.tabs.getOrNull(it.active) }

    /** The user typed in the editor; [cursor] is the caret offset afterwards. */
    fun edit(text: String, cursor: Int) {
        val tab = activeTab() ?: return
        if (text == tab.text) return
        // The undo stack is mutated here, outside updateActive: a state update may be re-run when another
        // thread updates the state at the same moment, and a re-run must not repeat a side effect.
        tab.undo.record(text)
        updateActive { it.copy(text = text, selStart = cursor, selEnd = cursor) }
    }

    fun undo() = stepHistory { it.undo.undo() }

    fun redo() = stepHistory { it.undo.redo() }

    private fun stepHistory(step: (OpenFile) -> String?) {
        val tab = activeTab() ?: return
        val text = step(tab) ?: return // moves the history exactly once
        updateActive { cur ->
            val at = cur.selStart.coerceAtMost(text.length)
            cur.copy(text = text, selStart = at, selEnd = at, revision = cur.revision + 1)
        }
    }

    private inline fun updateActive(crossinline change: (OpenFile) -> OpenFile) {
        _state.update { s ->
            val tab = s.tabs.getOrNull(s.active) ?: return@update s
            val tabs = s.tabs.toMutableList()
            tabs[s.active] = change(tab)
            s.copy(tabs = tabs)
        }
    }

    fun save() {
        viewModelScope.launch(Dispatchers.IO) { saveAllNow(onlyActive = true) }
    }

    private fun saveAllNow(onlyActive: Boolean = false) {
        val s = _state.value
        s.tabs.forEachIndexed { i, tab ->
            if (!tab.dirty || (onlyActive && i != s.active)) return@forEachIndexed
            repo.write(tab.file, tab.text)
                .onSuccess {
                    _state.update { cur ->
                        val j = cur.tabs.indexOfFirst { it.file == tab.file }
                        if (j < 0) cur else cur.copy(
                            tabs = cur.tabs.toMutableList().also { list ->
                                list[j] = list[j].copy(savedText = tab.text)
                            }
                        )
                    }
                }
                .onFailure { toast("Save failed: ${it.message}") }
        }
    }

    // ---- find / replace (current file) ----------------------------------------------------

    fun openFind() = _state.update { it.copy(find = it.find ?: FindState()) }

    fun closeFind() = _state.update { it.copy(find = null) }

    fun setFindOptions(options: FindOptions) {
        _state.update { it.copy(find = (it.find ?: FindState()).copy(options = options)) }
        refreshFindCount()
        if (options.query.isNotEmpty()) jumpToMatch(forward = true, fromSelectionStart = true)
    }

    fun setReplacement(text: String) = _state.update { it.copy(find = (it.find ?: FindState()).copy(replacement = text)) }

    fun findNext() = jumpToMatch(forward = true, fromSelectionStart = false)

    fun findPrevious() = jumpToMatch(forward = false, fromSelectionStart = false)

    private fun refreshFindCount() {
        val s = _state.value
        val find = s.find ?: return
        val tab = s.tabs.getOrNull(s.active)
        if (tab == null || find.options.query.isEmpty()) {
            _state.update { it.copy(find = find.copy(matchCount = 0, error = null)) }
            return
        }
        val result = FindReplace.findAll(tab.text, find.options)
        _state.update {
            it.copy(find = find.copy(matchCount = result.getOrNull()?.size ?: 0, error = result.exceptionOrNull()?.message))
        }
    }

    private fun jumpToMatch(forward: Boolean, fromSelectionStart: Boolean) {
        val s = _state.value
        val find = s.find ?: return
        val tab = s.tabs.getOrNull(s.active) ?: return
        val matches = FindReplace.findAll(tab.text, find.options).getOrNull().orEmpty()
        val match = when {
            matches.isEmpty() -> null
            forward -> FindReplace.next(matches, if (fromSelectionStart) tab.selStart else tab.selEnd)
            else -> FindReplace.previous(matches, tab.selStart)
        } ?: return
        updateActive { it.copy(selStart = match.start, selEnd = match.end, revision = it.revision + 1) }
    }

    fun replaceAll() {
        val s = _state.value
        val find = s.find ?: return
        val tab = s.tabs.getOrNull(s.active) ?: return
        FindReplace.replaceAll(tab.text, find.options, find.replacement)
            .onSuccess { (text, count) ->
                if (count == 0) { toast("No matches"); return }
                tab.undo.record(text)
                updateActive { it.copy(text = text, selStart = 0, selEnd = 0, revision = it.revision + 1) }
                refreshFindCount()
                toast("Replaced $count occurrence(s)")
            }
            .onFailure { toast(it.message) }
    }

    // ---- search in project ----------------------------------------------------------------

    fun openSearch() = _state.update { it.copy(search = it.search ?: SearchState()) }

    fun closeSearch() = _state.update { it.copy(search = null) }

    fun searchProject(options: FindOptions) {
        val project = _state.value.project ?: return
        _state.update { it.copy(search = SearchState(running = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            // Search what is on disk, so write pending edits first.
            saveAllNow()
            val result = ProjectSearch.search(project, options)
            _state.update {
                if (it.search == null) it // dialog was closed meanwhile
                else it.copy(search = SearchState(hits = result.getOrNull(), error = result.exceptionOrNull()?.message))
            }
        }
    }

    fun openHit(hit: SearchHit) {
        closeSearch()
        openFileAt(hit.file, hit.line, hit.column)
    }

    // ---- build ----------------------------------------------------------------------------

    fun build() {
        val project = _state.value.project ?: return
        if (_state.value.building) return
        _state.update { it.copy(building = true, buildLog = emptyList(), builtApk = null) }
        viewModelScope.launch(Dispatchers.IO) {
            saveAllNow()
            val result = try {
                engine.build(project) { line -> _state.update { it.copy(buildLog = it.buildLog + line) } }
            } catch (e: Throwable) {
                BuildResult.Failure("${e.javaClass.simpleName}: ${e.message}")
            }
            val (summary, apk) = when (result) {
                is BuildResult.Success -> "BUILD SUCCESSFUL: ${result.apk.name}" to result.apk
                is BuildResult.Failure -> "BUILD FAILED: ${result.reason}" to null
            }
            _state.update { it.copy(building = false, buildLog = it.buildLog + summary, builtApk = apk) }
        }
    }

    fun cancelBuild() = engine.cancel()

    fun dismissBuildLog() = _state.update { it.copy(buildLog = emptyList(), builtApk = null) }

    // ---- terminal -------------------------------------------------------------------------

    private val termBuffer = TerminalBuffer()
    private val termHistory = CommandHistory()
    private var shell: ShellSession? = null

    fun toggleTerminal() {
        val visible = !_state.value.terminal.visible
        _state.update { it.copy(terminal = it.terminal.copy(visible = visible)) }
        if (visible && shell?.isRunning != true) viewModelScope.launch(Dispatchers.IO) { startShell() }
    }

    private fun publishTerminal(running: Boolean = shell?.isRunning == true) {
        _state.update { it.copy(terminal = it.terminal.copy(text = termBuffer.text, running = running)) }
    }

    @Synchronized
    private fun startShell() {
        val dir = _state.value.project ?: return
        if (shell?.isRunning == true) return
        val home = File(app.filesDir, "home").also { it.mkdirs() }
        val session = ShellSession(
            command = listOf("/system/bin/sh"),
            workDir = dir,
            environment = mapOf(
                "HOME" to home.path,
                "TMPDIR" to app.cacheDir.path,
                "PATH" to "/system/bin:/system/xbin",
                "TERM" to "dumb",
                "PROJECT_DIR" to dir.path,
                "ANDROID_JAR" to toolchainStore.androidJar.path,
            ),
            onOutput = { chunk -> termBuffer.append(chunk); publishTerminal() },
            onExit = { code ->
                termBuffer.append("\n[shell exited with code $code]\n")
                publishTerminal(running = false)
            },
        )
        session.start()
            .onSuccess {
                shell = session
                termBuffer.append("MobileStudio terminal in ${dir.path}\nType 'build' to build the project, 'clear' to clear.\n")
                publishTerminal(running = true)
            }
            .onFailure { termBuffer.append("Could not start shell: ${it.message}\n"); publishTerminal(running = false) }
    }

    private fun stopShell() {
        shell?.stop()
        shell = null
        termBuffer.clear()
    }

    fun terminalRestart() {
        stopShell()
        viewModelScope.launch(Dispatchers.IO) { startShell() }
    }

    fun terminalSend(line: String) {
        termHistory.add(line)
        termBuffer.append("$ $line\n")
        when (TerminalCommands.parse(line)) {
            BuiltinCommand.Clear -> { termBuffer.clear(); publishTerminal(); return }
            BuiltinCommand.Build -> { publishTerminal(); build(); return }
            null -> Unit
        }
        val session = shell
        if (session == null || !session.send(line)) {
            termBuffer.append("[shell is not running - press restart]\n")
        }
        publishTerminal()
    }

    fun terminalHistoryPrevious(): String? = termHistory.previous()

    fun terminalHistoryNext(): String? = termHistory.next()

    override fun onCleared() {
        shell?.stop()
        super.onCleared()
    }

    // ---- messages -------------------------------------------------------------------------

    private fun toast(text: String?) = _state.update { it.copy(message = text ?: "Something went wrong") }

    fun messageShown() = _state.update { it.copy(message = null) }
}
