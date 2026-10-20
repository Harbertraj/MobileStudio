package dev.mobilestudio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilestudio.build.BuildEngine
import dev.mobilestudio.build.BuildResult
import dev.mobilestudio.build.ProjectCheckEngine
import dev.mobilestudio.data.ProjectRepository
import dev.mobilestudio.data.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class OpenFile(val file: File, val text: String, val savedText: String) {
    val dirty: Boolean get() = text != savedText
}

data class StudioState(
    val projects: List<File> = emptyList(),
    val project: File? = null,
    val tree: List<TreeNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val tabs: List<OpenFile> = emptyList(),
    val active: Int = -1,
    val buildLog: List<String> = emptyList(),
    val building: Boolean = false,
    val message: String? = null,
)

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProjectRepository(File(app.filesDir, "workspace"))
    private val engine: BuildEngine = ProjectCheckEngine()

    private val _state = MutableStateFlow(StudioState(projects = repo.projects()))
    val state: StateFlow<StudioState> = _state.asStateFlow()

    // ---- projects -------------------------------------------------------------------------

    fun createProject(name: String, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.createProject(name.trim(), packageName.trim())
                .onSuccess { dir ->
                    _state.update { it.copy(projects = repo.projects()) }
                    openProject(dir)
                }
                .onFailure { toast(it.message) }
        }
    }

    fun openProject(dir: File) {
        val expanded = setOf(dir.path)
        viewModelScope.launch(Dispatchers.IO) {
            val tree = repo.tree(dir, expanded)
            _state.update {
                it.copy(
                    project = dir, tree = tree, expanded = expanded,
                    tabs = emptyList(), active = -1, buildLog = emptyList(),
                )
            }
        }
    }

    fun closeProject() {
        _state.update {
            it.copy(project = null, tree = emptyList(), tabs = emptyList(), active = -1, buildLog = emptyList(),
                projects = repo.projects())
        }
    }

    fun deleteProject(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteProject(dir)
                .onSuccess { _state.update { it.copy(projects = repo.projects()) } }
                .onFailure { toast(it.message) }
        }
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

    fun openFile(file: File) {
        val existing = _state.value.tabs.indexOfFirst { it.file == file }
        if (existing >= 0) {
            _state.update { it.copy(active = existing) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) { openFileNow(file) }
    }

    private fun openFileNow(file: File) {
        repo.read(file)
            .onSuccess { text ->
                _state.update {
                    val tabs = it.tabs + OpenFile(file, text, text)
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

    fun edit(text: String) {
        _state.update { s ->
            if (s.active !in s.tabs.indices) return@update s
            val tabs = s.tabs.toMutableList()
            tabs[s.active] = tabs[s.active].copy(text = text)
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

    // ---- build ----------------------------------------------------------------------------

    fun build() {
        val project = _state.value.project ?: return
        if (_state.value.building) return
        _state.update { it.copy(building = true, buildLog = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            saveAllNow()
            val result = engine.build(project) { line -> _state.update { it.copy(buildLog = it.buildLog + line) } }
            val summary = when (result) {
                BuildResult.Success -> "BUILD SUCCESSFUL"
                is BuildResult.Failure -> "BUILD FAILED: ${result.reason}"
            }
            _state.update { it.copy(building = false, buildLog = it.buildLog + summary) }
        }
    }

    fun dismissBuildLog() = _state.update { it.copy(buildLog = emptyList()) }

    // ---- messages -------------------------------------------------------------------------

    private fun toast(text: String?) = _state.update { it.copy(message = text ?: "Something went wrong") }

    fun messageShown() = _state.update { it.copy(message = null) }
}
