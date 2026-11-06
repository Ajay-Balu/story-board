package com.ajay.storyboard.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.ajay.storyboard.ai.AiGateway
import com.ajay.storyboard.data.Chapter
import com.ajay.storyboard.data.ChapterBlocks
import com.ajay.storyboard.data.GalleryImage
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.common.dropRepeatedTitle
import com.ajay.storyboard.ui.reader.MarkdownLite
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB and PDF export of a book, pictures included. */
object StoryExport {

    fun fileName(title: String?, ext: String): String =
        (title ?: "story").replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "story" }.take(80) + ".$ext"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** **bold** / *italic* to XHTML. */
    private fun inlineHtml(line: String): String {
        val a = MarkdownLite.inline(line)
        val sb = StringBuilder()
        var i = 0
        val text = a.text
        while (i < text.length) {
            val styles = a.spanStyles.filter { i >= it.start && i < it.end }
            val bold = styles.any { it.item.fontWeight == FontWeight.Bold }
            val italic = styles.any { it.item.fontStyle == FontStyle.Italic }
            var j = i + 1
            while (j < text.length) {
                val s2 = a.spanStyles.filter { j >= it.start && j < it.end }
                if (s2.any { it.item.fontWeight == FontWeight.Bold } != bold || s2.any { it.item.fontStyle == FontStyle.Italic } != italic) break
                j++
            }
            var chunk = esc(text.substring(i, j))
            if (italic) chunk = "<i>$chunk</i>"
            if (bold) chunk = "<b>$chunk</b>"
            sb.append(chunk)
            i = j
        }
        return sb.toString()
    }

    private suspend fun load(repo: Repository, bookId: Long): Triple<String, List<Chapter>, Map<Long, GalleryImage>> {
        val book = repo.books.get(bookId) ?: error("Book not found")
        val chapters = repo.chapters.listForBook(bookId)
        val images = repo.images.byIds(chapters.flatMap { ChapterBlocks.imageIds(it.content) }.distinct()).associateBy { it.id }
        return Triple(book.title, chapters, images)
    }

    private fun chapterHeading(ch: Chapter) =
        ch.title.replace(Regex("^(chapter|ch\\.?)\\s*\\d+\\s*[:.\\-–—]\\s*", RegexOption.IGNORE_CASE), "").ifBlank { ch.title }

    // ------------------------------------------------------------------ EPUB

    suspend fun epub(context: Context, repo: Repository, bookId: Long, out: OutputStream) {
        val (title, chapters, images) = load(repo, bookId)
        val uid = "urn:uuid:" + UUID.nameUUIDFromBytes("storyboard-$bookId-$title".toByteArray())
        ZipOutputStream(out.buffered()).use { zip ->
            // "mimetype" must be first and stored uncompressed
            val mt = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            zip.write(mt); zip.closeEntry()
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }

            put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")

            val css = "body{font-family:serif;line-height:1.5;margin:0 4%} h1{text-align:center;margin:2em 0 1em} p{text-indent:1.2em;margin:0 0 .6em} " +
                ".pic{text-align:center;margin:1em 0} .pic img{max-width:100%} .cap{font-style:italic;font-size:.9em;text-align:center} .num{text-align:center;font-size:.8em;letter-spacing:.2em;margin-top:3em}"
            put("OEBPS/style.css", css)

            chapters.forEachIndexed { i, ch ->
                val body = StringBuilder()
                ChapterBlocks.lines(dropRepeatedTitle(ch.content, ch.title)).forEach { line ->
                    val pic = ChapterBlocks.picture(line)
                    when {
                        pic != null -> images[pic.imageId]?.let { img ->
                            body.append("<div class=\"pic\"><img src=\"images/${esc(img.fileName)}\" alt=\"${esc(pic.caption)}\" style=\"width:${pic.widthPct}%\"/>")
                            if (pic.caption.isNotBlank()) body.append("<p class=\"cap\">${esc(pic.caption)}</p>")
                            body.append("</div>\n")
                        }
                        line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> body.append("<hr/>\n")
                        line.startsWith("#") -> body.append("<h2>${inlineHtml(line.trimStart('#').trim())}</h2>\n")
                        else -> body.append("<p>${inlineHtml(line.removePrefix("> "))}</p>\n")
                    }
                }
                put("OEBPS/ch${i + 1}.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${esc(ch.title)}</title><link rel="stylesheet" href="style.css"/></head>
<body><p class="num">CHAPTER ${i + 1}</p><h1>${esc(chapterHeading(ch))}</h1>
$body</body></html>""")
            }
            images.values.forEach { img ->
                val f = AiGateway.fileOf(context, img)
                if (f.exists()) { zip.putNextEntry(ZipEntry("OEBPS/images/${img.fileName}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }

            val nav = chapters.mapIndexed { i, ch -> "<li><a href=\"ch${i + 1}.xhtml\">${esc(ch.title)}</a></li>" }.joinToString("\n")
            put("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${esc(title)}</title></head>
<body><nav epub:type="toc"><h1>Contents</h1><ol>
$nav
</ol></nav></body></html>""")
            put("OEBPS/toc.ncx", """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="$uid"/></head>
<docTitle><text>${esc(title)}</text></docTitle>
<navMap>
${chapters.mapIndexed { i, ch -> "<navPoint id=\"n${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${esc(ch.title)}</text></navLabel><content src=\"ch${i + 1}.xhtml\"/></navPoint>" }.joinToString("\n")}
</navMap></ncx>""")

            val manifest = buildString {
                appendLine("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                appendLine("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
                appendLine("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
                chapters.indices.forEach { appendLine("<item id=\"c${it + 1}\" href=\"ch${it + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>") }
                images.values.forEachIndexed { i, img -> appendLine("<item id=\"img$i\" href=\"images/${esc(img.fileName)}\" media-type=\"${img.mime}\"/>") }
            }
            val spine = chapters.indices.joinToString("\n") { "<itemref idref=\"c${it + 1}\"/>" }
            val modified = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            put("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">$uid</dc:identifier>
<dc:title>${esc(title)}</dc:title>
<dc:language>und</dc:language>
<dc:creator>Story-Board</dc:creator>
<meta property="dcterms:modified">$modified</meta>
</metadata>
<manifest>
$manifest</manifest>
<spine toc="ncx">
$spine
</spine>
</package>""")
        }
    }

    // ------------------------------------------------------------------ PDF

    private const val PAGE_W = 420      // A5 in points
    private const val PAGE_H = 595
    private const val MARGIN = 42f

    suspend fun pdf(context: Context, repo: Repository, bookId: Long, out: OutputStream) {
        val (title, chapters, images) = load(repo, bookId)
        val doc = PdfDocument()
        val width = (PAGE_W - 2 * MARGIN).toInt()
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; typeface = Typeface.SERIF; color = 0xFF111111.toInt() }
        val heading = TextPaint(body).apply { textSize = 17f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val small = TextPaint(body).apply { textSize = 8f; color = 0xFF666666.toInt(); letterSpacing = 0.2f }
        val caption = TextPaint(body).apply { textSize = 9f; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); color = 0xFF444444.toInt() }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun canvas(): Canvas = page!!.canvas
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN
            if (pageNo > 1) canvas().drawText("$pageNo", PAGE_W / 2f, PAGE_H - 18f, small.apply { textAlign = Paint.Align.CENTER })
            small.textAlign = Paint.Align.LEFT
        }
        fun layout(text: CharSequence, paint: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(align).setLineSpacing(0f, 1.25f).build()

        /** Draws [l] from the current position, continuing on new pages as needed. */
        fun draw(l: StaticLayout, after: Float) {
            var line = 0
            while (line < l.lineCount) {
                val room = PAGE_H - MARGIN - y
                var last = line
                while (last < l.lineCount && l.getLineBottom(last) - l.getLineTop(line) <= room) last++
                if (last == line) { newPage(); continue }
                val top = l.getLineTop(line).toFloat()
                val bottom = l.getLineBottom(last - 1).toFloat()
                canvas().save()
                canvas().translate(MARGIN, y - top)
                canvas().clipRect(0f, top, width.toFloat(), bottom)
                l.draw(canvas())
                canvas().restore()
                y += bottom - top
                line = last
                if (line < l.lineCount) newPage()
            }
            y += after
        }

        fun styled(line: String): CharSequence {
            val a = MarkdownLite.inline(line)
            val sp = SpannableString(a.text)
            a.spanStyles.forEach { r ->
                if (r.item.fontWeight == FontWeight.Bold) sp.setSpan(StyleSpan(Typeface.BOLD), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.item.fontStyle == FontStyle.Italic) sp.setSpan(StyleSpan(Typeface.ITALIC), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sp
        }

        // title page
        newPage()
        y = PAGE_H / 3f
        draw(layout(title, TextPaint(heading).apply { textSize = 24f }, Layout.Alignment.ALIGN_CENTER), 12f)

        chapters.forEachIndexed { i, ch ->
            newPage()
            y = MARGIN + 30f
            draw(layout("CHAPTER ${i + 1}", small, Layout.Alignment.ALIGN_CENTER), 6f)
            draw(layout(chapterHeading(ch), heading, Layout.Alignment.ALIGN_CENTER), 18f)
            ChapterBlocks.lines(dropRepeatedTitle(ch.content, ch.title)).forEach { line ->
                val pic = ChapterBlocks.picture(line)
                when {
                    pic != null -> {
                        val img = images[pic.imageId] ?: return@forEach
                        val bmp = BitmapFactory.decodeFile(AiGateway.fileOf(context, img).path, BitmapFactory.Options().apply { inSampleSize = 2 })
                            ?: return@forEach
                        var w = width * pic.widthPct / 100f
                        var h = w * bmp.height / bmp.width
                        val maxH = PAGE_H - 2 * MARGIN - 30f
                        if (h > maxH) { w *= maxH / h; h = maxH }
                        if (y + h > PAGE_H - MARGIN) newPage()
                        val left = MARGIN + (width - w) / 2
                        canvas().drawBitmap(bmp, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                        bmp.recycle()
                        y += h + 6f
                        if (pic.caption.isNotBlank()) draw(layout(pic.caption, caption, Layout.Alignment.ALIGN_CENTER), 10f) else y += 6f
                    }
                    line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> draw(layout("* * *", body, Layout.Alignment.ALIGN_CENTER), 8f)
                    line.startsWith("#") -> draw(layout(styled(line.trimStart('#').trim()), TextPaint(heading).apply { textSize = 13f }), 6f)
                    else -> draw(layout(styled(line.removePrefix("> ")), body), 7f)
                }
            }
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }
}
