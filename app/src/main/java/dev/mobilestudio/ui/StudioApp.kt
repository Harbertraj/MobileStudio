package dev.mobilestudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobilestudio.build.ApkInstaller
import dev.mobilestudio.data.Language
import dev.mobilestudio.data.SearchHit
import dev.mobilestudio.data.TreeNode
import dev.mobilestudio.editor.FindOptions
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun StudioApp(vm: StudioViewModel, dark: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.project == null) {
            HomeScreen(state, vm, snackbar)
        } else {
            ProjectScreen(state, vm, dark, snackbar)
        }
    }

    state.folderPicker?.let { FolderPickerDialog(it, vm) }
    if (state.toolchainDialog) ToolchainDialog(state.toolchain, state.toolchainBusy, state.toolchainLog, state.aapt2Url, vm)
}

// ---- home -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: StudioState, vm: StudioViewModel, snackbar: SnackbarHostState) {
    var showNew by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<File?>(null) }

    val openFolder = rememberOpenFolderAction(vm)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MobileStudio") },
                actions = {
                    IconButton(onClick = openFolder) { Icon(Icons.Default.FolderOpen, "Open folder") }
                    IconButton(onClick = vm::openToolchain) { Icon(Icons.Default.Build, "Build toolchain") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }) { Icon(Icons.Default.Add, "New project") }
        },
    ) { padding ->
        if (state.projects.isEmpty() && state.recent.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tap the folder icon to open a project folder, or + to create one.")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                if (state.recent.isNotEmpty()) {
                    item(key = "recent-header") {
                        Text("Recent folders", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    }
                    items(state.recent, key = { "recent:" + it.path }) { dir ->
                        ListItem(
                            modifier = Modifier.clickable { vm.openFolder(dir) },
                            leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                            headlineContent = { Text(dir.name) },
                            supportingContent = { Text(dir.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                if (state.projects.isNotEmpty()) {
                    item(key = "projects-header") {
                        Text("Projects", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    }
                }
                items(state.projects, key = { it.path }) { project ->
                    ListItem(
                        modifier = Modifier.clickable { vm.openProject(project) },
                        leadingContent = { Icon(Icons.Default.Folder, null) },
                        headlineContent = { Text(project.name) },
                        trailingContent = {
                            IconButton(onClick = { toDelete = project }) { Icon(Icons.Default.Delete, "Delete") }
                        },
                    )
                }
            }
        }
    }

    if (showNew) {
        NewProjectDialog(
            onDismiss = { showNew = false },
            onCreate = { name, pkg, language -> showNew = false; vm.createProject(name, pkg, language) },
        )
    }
    toDelete?.let { project ->
        ConfirmDialog(
            text = "Delete project \"${project.name}\" and all its files?",
            onDismiss = { toDelete = null },
            onConfirm = { toDelete = null; vm.deleteProject(project) },
        )
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String, Language) -> Unit) {
    var name by remember { mutableStateOf("MyApp") }
    var pkg by remember { mutableStateOf("com.example.myapp") }
    var language by remember { mutableStateOf(Language.KOTLIN) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(pkg, { pkg = it }, label = { Text("Package name") }, singleLine = true)
                Row {
                    FilterChip(selected = language == Language.KOTLIN, onClick = { language = Language.KOTLIN },
                        label = { Text("Kotlin") }, modifier = Modifier.padding(end = 8.dp))
                    FilterChip(selected = language == Language.JAVA, onClick = { language = Language.JAVA }, label = { Text("Java") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, pkg, language) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- project --------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(state: StudioState, vm: StudioViewModel, dark: Boolean, snackbar: SnackbarHostState) {
    val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val project = state.project ?: return

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                FileTreePanel(
                    project = project,
                    nodes = state.tree,
                    expanded = state.expanded,
                    onToggle = vm::toggleDir,
                    onOpen = { vm.openFile(it); scope.launch { drawer.close() } },
                    onCreateFile = vm::createFile,
                    onCreateDir = vm::createDirectory,
                    onRename = vm::rename,
                    onDelete = vm::delete,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Files") }
                    },
                    actions = {
                        val active = state.tabs.getOrNull(state.active)
                        IconButton(onClick = vm::undo, enabled = active?.undo?.canUndo == true) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                        }
                        IconButton(onClick = vm::redo, enabled = active?.undo?.canRedo == true) {
                            Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                        }
                        IconButton(onClick = vm::build, enabled = !state.building) {
                            Icon(Icons.Default.PlayArrow, "Build")
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save") },
                                    enabled = active?.dirty == true,
                                    leadingIcon = { Icon(Icons.Default.Save, null) },
                                    onClick = { menu = false; vm.save() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Find in file") },
                                    enabled = active != null,
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    onClick = { menu = false; vm.openFind() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Search in project") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    onClick = { menu = false; vm.openSearch() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Build debug APK") },
                                    enabled = !state.building,
                                    onClick = { menu = false; vm.build(dev.mobilestudio.build.BuildVariant.DEBUG) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Build release APK") },
                                    enabled = !state.building,
                                    onClick = { menu = false; vm.build(dev.mobilestudio.build.BuildVariant.RELEASE) },
                                )
                                if (state.building) {
                                    DropdownMenuItem(
                                        text = { Text("Cancel build") },
                                        onClick = { menu = false; vm.cancelBuild() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (state.terminal.visible) "Hide terminal" else "Terminal") },
                                    leadingIcon = { Icon(Icons.Default.Terminal, null) },
                                    onClick = { menu = false; vm.toggleTerminal() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Build toolchain") },
                                    leadingIcon = { Icon(Icons.Default.Build, null) },
                                    onClick = { menu = false; vm.openToolchain() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Close project") },
                                    leadingIcon = { Icon(Icons.Default.Close, null) },
                                    onClick = { menu = false; vm.closeProject() },
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.building) LinearProgressIndicator(Modifier.fillMaxWidth())

                state.find?.let { find -> FindBar(find, vm) }

                if (state.tabs.isNotEmpty()) {
                    ScrollableTabRow(selectedTabIndex = state.active.coerceAtLeast(0), edgePadding = 0.dp) {
                        state.tabs.forEachIndexed { i, tab ->
                            Tab(
                                selected = i == state.active,
                                onClick = { vm.selectTab(i) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tab.file.name + if (tab.dirty) " •" else "", maxLines = 1)
                                        IconButton(onClick = { vm.closeTab(i) }, modifier = Modifier.width(28.dp)) {
                                            Icon(Icons.Default.Close, "Close tab")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val open = state.tabs.getOrNull(state.active)
                    if (open == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Open a file from the menu")
                        }
                    } else {
                        CodeEditor(
                            key = open.file.path,
                            text = open.text,
                            selStart = open.selStart,
                            selEnd = open.selEnd,
                            revision = open.revision,
                            extension = open.file.extension,
                            dark = dark,
                            onChange = vm::edit,
                        )
                    }
                }

                if (state.terminal.visible) {
                    HorizontalDivider()
                    TerminalPanel(state.terminal, vm)
                }

                if (state.buildLog.isNotEmpty()) {
                    HorizontalDivider()
                    BuildLogPanel(state.buildLog, state.builtApk, vm::dismissBuildLog)
                }
            }
        }
    }

    state.search?.let { SearchDialog(it, vm) }
}

@Composable
private fun FindBar(find: FindState, vm: StudioViewModel) {
    var query by remember { mutableStateOf(find.options.query) }
    var replacement by remember { mutableStateOf(find.replacement) }
    val options = find.options

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; vm.setFindOptions(options.copy(query = it)) },
                    label = { Text("Find") },
                    singleLine = true,
                    isError = find.error != null,
                    supportingText = {
                        Text(find.error ?: if (query.isEmpty()) "" else "${find.matchCount} match(es)")
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::findPrevious) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = vm::findNext) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
                IconButton(onClick = vm::closeFind) { Icon(Icons.Default.Close, "Close find") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = options.caseSensitive,
                    onClick = { vm.setFindOptions(options.copy(caseSensitive = !options.caseSensitive)) },
                    label = { Text("Aa") },
                    modifier = Modifier.padding(end = 4.dp),
                )
                FilterChip(
                    selected = options.wholeWord,
                    onClick = { vm.setFindOptions(options.copy(wholeWord = !options.wholeWord)) },
                    label = { Text("Word") },
                    modifier = Modifier.padding(end = 4.dp),
                )
                FilterChip(
                    selected = options.regex,
                    onClick = { vm.setFindOptions(options.copy(regex = !options.regex)) },
                    label = { Text(".*") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it; vm.setReplacement(it) },
                    label = { Text("Replace with") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::replaceAll, enabled = query.isNotEmpty() && find.matchCount > 0) {
                    Text("Replace all")
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(search: SearchState, vm: StudioViewModel) {
    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    fun doSearch() {
        if (query.isNotEmpty()) vm.searchProject(FindOptions(query, caseSensitive = caseSensitive, regex = regex))
    }

    AlertDialog(
        onDismissRequest = vm::closeSearch,
        title = { Text("Search in project") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(vertical = 4.dp)) {
                    FilterChip(selected = caseSensitive, onClick = { caseSensitive = !caseSensitive }, label = { Text("Aa") },
                        modifier = Modifier.padding(end = 4.dp))
                    FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text(".*") })
                }
                when {
                    search.running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    search.error != null -> Text(search.error, color = MaterialTheme.colorScheme.error)
                    search.hits != null && search.hits.isEmpty() -> Text("No results")
                    search.hits != null -> {
                        Text("${search.hits.size} result(s)", style = MaterialTheme.typography.labelMedium)
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(search.hits) { hit -> SearchHitRow(hit) { vm.openHit(hit) } }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { doSearch() }, enabled = query.isNotEmpty() && !search.running) { Text("Search") } },
        dismissButton = { TextButton(onClick = vm::closeSearch) { Text("Close") } },
    )
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text("${hit.file.name}:${hit.line}", style = MaterialTheme.typography.labelMedium)
        Text(hit.preview, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BuildLogPanel(lines: List<String>, apk: java.io.File?, onClose: () -> Unit) {
    val context = LocalContext.current
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Build", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (apk != null) TextButton(onClick = { ApkInstaller.install(context, apk) }) { Text("Install") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Dismiss log") }
            }
            LazyColumn(Modifier.padding(horizontal = 12.dp)) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }
}

// ---- file tree ------------------------------------------------------------------------------

@Composable
private fun FileTreePanel(
    project: File,
    nodes: List<TreeNode>,
    expanded: Set<String>,
    onToggle: (File) -> Unit,
    onOpen: (File) -> Unit,
    onCreateFile: (File, String) -> Unit,
    onCreateDir: (File, String) -> Unit,
    onRename: (File, String) -> Unit,
    onDelete: (File) -> Unit,
) {
    var nameDialog by remember { mutableStateOf<NameRequest?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { nameDialog = NameRequest("New file", "") { onCreateFile(project, it) } }) {
                Icon(Icons.Default.NoteAdd, "New file in project root")
            }
            IconButton(onClick = { nameDialog = NameRequest("New folder", "") { onCreateDir(project, it) } }) {
                Icon(Icons.Default.CreateNewFolder, "New folder in project root")
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            items(nodes, key = { it.file.path }) { node ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (node.isDir) onToggle(node.file) else onOpen(node.file) }
                        .padding(start = (12 + node.depth * 16).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = when {
                        !node.isDir -> Icons.Default.Description
                        node.file.path in expanded -> Icons.Default.FolderOpen
                        else -> Icons.Default.Folder
                    }
                    Icon(icon, null, Modifier.padding(end = 8.dp))
                    Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (node.isDir) {
                                DropdownMenuItem(
                                    text = { Text("New file") },
                                    leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                                    onClick = {
                                        menu = false
                                        nameDialog = NameRequest("New file", "") { onCreateFile(node.file, it) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                    onClick = {
                                        menu = false
                                        nameDialog = NameRequest("New folder", "") { onCreateDir(node.file, it) }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    menu = false
                                    nameDialog = NameRequest("Rename", node.name) { onRename(node.file, it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menu = false; deleteTarget = node.file },
                            )
                        }
                    }
                }
            }
        }
    }

    nameDialog?.let { req ->
        NameDialog(req.title, req.initial, onDismiss = { nameDialog = null }) { name ->
            nameDialog = null
            req.onConfirm(name)
        }
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            text = "Delete \"${target.name}\"?",
            onDismiss = { deleteTarget = null },
            onConfirm = { deleteTarget = null; onDelete(target) },
        )
    }
}

private class NameRequest(val title: String, val initial: String, val onConfirm: (String) -> Unit)

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
