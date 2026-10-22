package dev.mobilestudio.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val STORAGE_PERMISSIONS = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
)

/**
 * Returns an action that opens the folder browser, asking for storage access first when it is missing.
 * The app targets API 28 on purpose, so these two permissions give java.io access to shared storage.
 */
@Composable
internal fun rememberOpenFolderAction(vm: StudioViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.openFolderPicker()
    }
    return {
        val granted = STORAGE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) vm.openFolderPicker() else launcher.launch(STORAGE_PERMISSIONS)
    }
}

@Composable
internal fun FolderPickerDialog(picker: FolderPickerState, vm: StudioViewModel) {
    AlertDialog(
        onDismissRequest = vm::closeFolderPicker,
        title = { Text("Open folder") },
        text = {
            Column {
                Text(picker.dir.path, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (picker.looksLikeProject) {
                    Text("Android project found", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                picker.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    if (!picker.atTop) {
                        item(key = "..") {
                            ListItem(
                                modifier = Modifier.clickable { vm.browseUp() },
                                leadingContent = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                headlineContent = { Text("..") },
                            )
                        }
                    }
                    items(picker.entries, key = { it.path }) { dir ->
                        ListItem(
                            modifier = Modifier.clickable { vm.browseTo(dir) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.openFolder(picker.dir) }) { Text("Open this folder") } },
        dismissButton = { TextButton(onClick = vm::closeFolderPicker) { Text("Cancel") } },
    )
}

@Composable
internal fun ToolchainDialog(info: ToolchainInfo, busy: Boolean, log: List<String>, aapt2Url: String, vm: StudioViewModel) {
    val context = LocalContext.current
    fun picker(kind: ToolKind) = { uri: Uri? ->
        if (uri != null) {
            vm.importTool(kind, displayName(context, uri)) {
                context.contentResolver.openInputStream(uri) ?: error("Cannot open the selected file")
            }
        }
    }
    val pickAapt2 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), picker(ToolKind.AAPT2))
    val pickJar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), picker(ToolKind.ANDROID_JAR))
    val pickLib = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), picker(ToolKind.LIB))
    val any = arrayOf("*/*")

    AlertDialog(
        onDismissRequest = vm::closeToolchain,
        title = { Text("Build toolchain") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Building an APK needs aapt2, android.jar and (for Kotlin) kotlin-stdlib. The app downloads them for you, " +
                        "and a build does it automatically when something is missing. They are kept in the app's private storage.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = vm::downloadToolchain, enabled = !busy) {
                    Text(if (busy) "Downloading..." else "Download missing tools")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (log.isNotEmpty()) {
                    Text(
                        log.takeLast(12).joinToString("\n"),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                }
                var url by remember(aapt2Url) { mutableStateOf(aapt2Url) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("aapt2 download address (optional)") },
                    supportingText = { Text("A .zip / .tar.gz containing the Android aarch64 aapt2, or the file itself. Empty = automatic.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.setAapt2Url(url) }) { Text("Save address") }
                Text("Or import files by hand:", style = MaterialTheme.typography.labelLarge)
                ToolRow("aapt2 (Android, aarch64 build)", info.aapt2, "Import aapt2") { pickAapt2.launch(any) }
                ToolRow("android.jar (SDK platform, matches compileSdk)", info.androidJar, "Import android.jar") { pickJar.launch(any) }
                Text("Libraries (for example kotlin-stdlib-*.jar, required for Kotlin projects)", style = MaterialTheme.typography.labelLarge)
                if (info.libs.isEmpty()) Text("None imported", style = MaterialTheme.typography.bodySmall)
                info.libs.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        IconButton(onClick = { vm.removeLib(name) }) { Icon(Icons.Default.Close, "Remove $name") }
                    }
                }
                OutlinedButton(onClick = { pickLib.launch(any) }) { Text("Import jar") }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeToolchain) { Text("Done") } },
    )
}

@Composable
private fun ToolRow(label: String, ok: Boolean, action: String, onImport: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (ok) "Ready" else "Missing",
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(label, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.padding(top = 4.dp)) { Text(action) }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

@Composable
internal fun TerminalPanel(terminal: TerminalState, vm: StudioViewModel) {
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(terminal.text) { scroll.scrollTo(scroll.maxValue) }

    fun send() {
        if (input.isNotBlank()) {
            vm.terminalSend(input)
            input = ""
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (terminal.running) "Terminal" else "Terminal (stopped)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::terminalRestart) { Icon(Icons.Default.Refresh, "Restart shell") }
                IconButton(onClick = vm::toggleTerminal) { Icon(Icons.Default.Close, "Hide terminal") }
            }
            SelectionContainer {
                Text(
                    terminal.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.terminalHistoryPrevious()?.let { input = it } }) {
                    Icon(Icons.Default.KeyboardArrowUp, "Previous command")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("command") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { send() }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Run")
                }
            }
        }
    }
}
