package com.ajay.storyboard.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

sealed interface Block {
    data class Heading(val text: AnnotatedString) : Block
    data class Paragraph(val text: AnnotatedString) : Block
    data object Divider : Block
    data class Picture(val imageId: Long, val caption: String, val widthPct: Int) : Block
}

/** Very small Markdown subset used by chat AIs: headings, **bold**, *italic*, dividers. */
object MarkdownLite {
    /** One line (paragraph) as a block. */
    fun block(line: String): Block = blocks(line).firstOrNull() ?: Block.Paragraph(AnnotatedString(""))

    fun blocks(content: String): List<Block> =
        content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val pic = com.ajay.storyboard.data.ChapterBlocks.picture(line)
                when {
                    pic != null -> Block.Picture(pic.imageId, pic.caption, pic.widthPct)
                    line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> Block.Divider
                    line.startsWith("#") -> Block.Heading(inline(line.trimStart('#').trim()))
                    else -> Block.Paragraph(inline(line.removePrefix("> ")))
                }
            }

    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        var bold = false
        var italic = false
        while (i < text.length) {
            when {
                text.startsWith("**", i) || text.startsWith("__", i) -> { bold = !bold; i += 2 }
                (text[i] == '*' || (text[i] == '_' && isWordBoundary(text, i))) -> { italic = !italic; i += 1 }
                else -> {
                    val start = length
                    append(text[i])
                    if (bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                    if (italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                    i += 1
                }
            }
        }
    }

    private fun isWordBoundary(text: String, i: Int): Boolean {
        val before = if (i > 0) text[i - 1] else ' '
        val after = if (i + 1 < text.length) text[i + 1] else ' '
        return !before.isLetterOrDigit() || !after.isLetterOrDigit()
    }
}
