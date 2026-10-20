package dev.mobilestudio.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

data class Palette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val tag: Color,
    val attribute: Color,
) {
    companion object {
        val Dark = Palette(
            keyword = Color(0xFFCC7832),
            string = Color(0xFF6A8759),
            comment = Color(0xFF808080),
            number = Color(0xFF6897BB),
            tag = Color(0xFFE8BF6A),
            attribute = Color(0xFFBABABA),
        )
        val Light = Palette(
            keyword = Color(0xFF0033B3),
            string = Color(0xFF067D17),
            comment = Color(0xFF8C8C8C),
            number = Color(0xFF1750EB),
            tag = Color(0xFF0033B3),
            attribute = Color(0xFF174AD4),
        )
    }
}

/** Regex-based highlighter. Fast and good enough for an editor view; it is not a parser. */
object SyntaxHighlighter {

    private val kotlinKeywords = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when",
        "for", "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw",
        "in", "is", "as", "null", "true", "false", "this", "super", "override", "private", "public",
        "protected", "internal", "open", "abstract", "sealed", "data", "enum", "companion", "init",
        "suspend", "lateinit", "const", "by", "typealias", "inline", "operator", "vararg", "where",
    )

    private val javaKeywords = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "new", "public",
        "private", "protected", "static", "final", "abstract", "void", "int", "long", "double",
        "float", "boolean", "char", "byte", "short", "if", "else", "switch", "case", "default",
        "for", "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw",
        "throws", "this", "super", "null", "true", "false", "instanceof", "synchronized",
    )

    private val kotlinRegex = Regex(
        """(?<comment>//[^\n]*|/\*[\s\S]*?(?:\*/|$))|(?<string>"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(?<number>\b\d[\d_]*(?:\.\d+)?[fFLl]?\b)|(?<word>\b[A-Za-z_][A-Za-z0-9_]*\b)"""
    )

    private val xmlRegex = Regex(
        """(?<comment><!--[\s\S]*?(?:-->|$))|(?<string>"[^"]*"|'[^']*')|(?<tag></?[A-Za-z_][\w:.-]*|/?>)|(?<attr>\b[A-Za-z_][\w:.-]*(?==))"""
    )

    fun highlight(text: String, extension: String, p: Palette): AnnotatedString =
        when (extension.lowercase()) {
            "kt", "kts", "gradle" -> highlightCode(text, kotlinKeywords, p)
            "java" -> highlightCode(text, javaKeywords, p)
            "xml" -> highlightXml(text, p)
            else -> AnnotatedString(text)
        }

    private fun highlightCode(text: String, keywords: Set<String>, p: Palette): AnnotatedString =
        buildAnnotatedString {
            append(text)
            for (m in kotlinRegex.findAll(text)) {
                val style = when {
                    m.groups["comment"] != null -> SpanStyle(color = p.comment)
                    m.groups["string"] != null -> SpanStyle(color = p.string)
                    m.groups["number"] != null -> SpanStyle(color = p.number)
                    m.groups["word"] != null && m.value in keywords -> SpanStyle(color = p.keyword)
                    else -> null
                } ?: continue
                addStyle(style, m.range.first, m.range.last + 1)
            }
        }

    private fun highlightXml(text: String, p: Palette): AnnotatedString =
        buildAnnotatedString {
            append(text)
            for (m in xmlRegex.findAll(text)) {
                val style = when {
                    m.groups["comment"] != null -> SpanStyle(color = p.comment)
                    m.groups["string"] != null -> SpanStyle(color = p.string)
                    m.groups["tag"] != null -> SpanStyle(color = p.tag)
                    m.groups["attr"] != null -> SpanStyle(color = p.attribute)
                    else -> null
                } ?: continue
                addStyle(style, m.range.first, m.range.last + 1)
            }
        }
}
