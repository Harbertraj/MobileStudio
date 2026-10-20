package dev.mobilestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobilestudio.editor.Palette
import dev.mobilestudio.editor.SyntaxHighlighter

/**
 * Plain text editor with a line-number gutter and regex syntax colouring.
 * Lines never wrap; long lines scroll horizontally so the gutter stays aligned.
 * [key] identifies the file; switching it resets the cursor state.
 */
@Composable
fun CodeEditor(
    key: String,
    initialText: String,
    extension: String,
    dark: Boolean,
    onChange: (String) -> Unit,
) {
    var value by remember(key) { mutableStateOf(TextFieldValue(initialText)) }
    val palette = if (dark) Palette.Dark else Palette.Light
    val ext = extension

    val transformation = remember(ext, dark) {
        VisualTransformation { text ->
            TransformedText(SyntaxHighlighter.highlight(text.text, ext, palette), OffsetMapping.Identity)
        }
    }

    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val lineCount = value.text.count { it == '\n' } + 1
    val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }

    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                onValueChange = { new ->
                    val textChanged = new.text != value.text
                    value = new
                    if (textChanged) onChange(new.text)
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transformation,
                modifier = Modifier.padding(horizontal = 8.dp).widthIn(min = 300.dp),
            )
        }
    }
}
