package dev.mobilestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobilestudio.editor.AutoEdit
import dev.mobilestudio.editor.Completion
import dev.mobilestudio.editor.Diagnostics
import dev.mobilestudio.editor.Palette
import dev.mobilestudio.editor.SyntaxHighlighter
import dev.mobilestudio.editor.TextPositions

/** Files above this size skip per-keystroke analysis (completion, diagnostics) to keep typing responsive. */
private const val ANALYSIS_LIMIT_CHARS = 200_000
private val LINE_HEIGHT = 20.sp

/**
 * Code editor: line-number gutter, syntax colouring, auto-indent/auto-close, word completion
 * and a status bar with structural problems. Lines never wrap, so the gutter stays aligned.
 *
 * The editor owns the text field state while the user types. It reloads from [text]/[selStart]/[selEnd]
 * only when [key] or [revision] changes (a different file, or a change made from outside such as undo).
 */
@Composable
fun CodeEditor(
    key: String,
    text: String,
    selStart: Int,
    selEnd: Int,
    revision: Int,
    extension: String,
    dark: Boolean,
    onChange: (text: String, cursor: Int) -> Unit,
) {
    var value by remember(key, revision) {
        mutableStateOf(
            TextFieldValue(text, TextRange(selStart.coerceIn(0, text.length), selEnd.coerceIn(0, text.length)))
        )
    }
    val palette = if (dark) Palette.Dark else Palette.Light
    val transformation = remember(extension, dark) {
        VisualTransformation { t ->
            TransformedText(SyntaxHighlighter.highlight(t.text, extension, palette), OffsetMapping.Identity)
        }
    }
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = LINE_HEIGHT,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val scroll = rememberScrollState()
    val lineHeightPx = with(LocalDensity.current) { LINE_HEIGHT.toPx() }

    // Bring the selection into view after the file is opened or changed from outside (find, search hit, undo).
    LaunchedEffect(key, revision) {
        scroll.scrollTo((TextPositions.lineIndexOf(text, selStart) * lineHeightPx).toInt())
    }

    val analyse = value.text.length <= ANALYSIS_LIMIT_CHARS
    val suggestions = remember(value.text, value.selection, extension) {
        if (analyse && value.selection.collapsed) Completion.suggest(value.text, value.selection.start, extension)
        else emptyList()
    }
    val problems = remember(value.text, extension) {
        if (analyse) Diagnostics.check(value.text, extension) else emptyList()
    }
    val lineCount = value.text.count { it == '\n' } + 1
    val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Text(
                text = gutter,
                style = textStyle.copy(color = MaterialTheme.colorScheme.outline),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp)
                    .widthIn(min = 32.dp),
            )
            Box(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = value,
                    onValueChange = { typed ->
                        val before = value
                        var next = typed
                        if (typed.text != before.text && typed.selection.collapsed && before.selection.collapsed) {
                            AutoEdit.apply(before.text, before.selection.start, typed.text, typed.selection.start)
                                ?.let { next = TextFieldValue(it.text, TextRange(it.cursor)) }
                        }
                        value = next
                        if (next.text != before.text) onChange(next.text, next.selection.start)
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    modifier = Modifier.padding(horizontal = 8.dp).widthIn(min = 300.dp),
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                for (s in suggestions) {
                    AssistChip(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        onClick = {
                            val ctx = Completion.contextAt(value.text, value.selection.start) ?: return@AssistChip
                            val r = Completion.accept(value.text, ctx, s)
                            value = TextFieldValue(r.text, TextRange(r.cursor))
                            onChange(r.text, r.cursor)
                        },
                        label = { Text(s.text, fontFamily = FontFamily.Monospace) },
                    )
                }
            }
        }

        Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            val status = when {
                !analyse -> "Large file: analysis disabled"
                problems.isEmpty() -> "No problems found"
                else -> {
                    val first = problems.first()
                    "${problems.size} problem(s) — line ${first.line}: ${first.message}"
                }
            }
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (problems.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
