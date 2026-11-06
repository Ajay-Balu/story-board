package com.ajay.storyboard.data

/**
 * A chapter's text as editable paragraphs. Each non-empty line is one paragraph (that is also how the
 * reader shows it). A picture is a paragraph of its own: ![caption](sbimg:<galleryId>?w=<width %>).
 */
object ChapterBlocks {
    private val IMAGE = Regex("^!\\[(.*)]\\(sbimg:(\\d+)(?:\\?w=(\\d+))?\\)$")

    data class Picture(val imageId: Long, val caption: String, val widthPct: Int)

    fun lines(content: String): List<String> = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun join(lines: List<String>): String = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    fun picture(line: String): Picture? = IMAGE.matchEntire(line.trim())?.let {
        Picture(it.groupValues[2].toLong(), it.groupValues[1], it.groupValues[3].toIntOrNull()?.coerceIn(20, 100) ?: 100)
    }

    fun pictureLine(p: Picture): String =
        "![${p.caption.replace("]", ")").replace("\n", " ").trim()}](sbimg:${p.imageId}${if (p.widthPct < 100) "?w=${p.widthPct}" else ""})"

    fun pictureLine(imageId: Long, caption: String = "", widthPct: Int = 100) = pictureLine(Picture(imageId, caption, widthPct))

    /** Gallery ids used in [content]. */
    fun imageIds(content: String): List<Long> = lines(content).mapNotNull { picture(it)?.imageId }

    /** Text without picture lines (for AI prompts and word counts). */
    fun textOnly(content: String): String = lines(content).filter { picture(it) == null }.joinToString("\n\n")

    /** Replaces picture ids (used when importing a shared story). */
    fun remapImages(content: String, map: Map<Long, Long>): String =
        content.lines().joinToString("\n") { line ->
            val p = picture(line) ?: return@joinToString line
            map[p.imageId]?.let { pictureLine(p.copy(imageId = it)) } ?: line
        }

    /**
     * A whole-chapter AI rewrite comes back without the pictures; put each one back at the same relative
     * place (a picture after 40% of the old text goes after 40% of the new text).
     */
    fun keepPictures(old: String, new: String): String {
        val oldLines = lines(old)
        val pics = oldLines.withIndex().filter { picture(it.value) != null }
        if (pics.isEmpty() || imageIds(new).isNotEmpty()) return new
        val out = lines(new).toMutableList()
        val n = oldLines.size.coerceAtLeast(1)
        pics.sortedByDescending { it.index }.forEach { (i, line) ->
            val at = Math.round(i.toDouble() / n * out.size).toInt().coerceIn(0, out.size)
            out.add(at, line)
        }
        return join(out)
    }
}
