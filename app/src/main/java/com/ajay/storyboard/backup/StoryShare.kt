package com.ajay.storyboard.backup

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.ajay.storyboard.ai.AiGateway
import com.ajay.storyboard.data.BibleEntry
import com.ajay.storyboard.data.Book
import com.ajay.storyboard.data.Chapter
import com.ajay.storyboard.data.ChapterBlocks
import com.ajay.storyboard.data.ChapterSource
import com.ajay.storyboard.data.GalleryImage
import com.ajay.storyboard.data.Repository
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The shared story format (.storyboard = zip of story.json + images/). Only the story: no AI history or settings. */
data class SharedStory(
    val format: String = "storyboard-story",
    val version: Int = 1,
    val title: String = "",
    val exportedAt: Long = System.currentTimeMillis(),
    val chapters: List<SharedChapter> = emptyList(),
    val bible: List<SharedBible> = emptyList(),
    val images: List<SharedImage> = emptyList(),
)

data class SharedChapter(val title: String = "", val content: String = "", val source: String = "MANUAL")
data class SharedBible(val category: String = "", val name: String = "", val details: String = "", val locked: Boolean = false)
data class SharedImage(val id: Long = 0, val fileName: String = "", val mime: String = "image/png", val prompt: String = "")

object StoryShare {
    private val gson = Gson()

    /** Writes a story (chapters, story bible, the pictures used in it) to [out]. */
    suspend fun write(context: Context, repo: Repository, bookId: Long, out: OutputStream) {
        val book = repo.books.get(bookId) ?: error("Book not found")
        val chapters = repo.chapters.listForBook(bookId)
        val ids = chapters.flatMap { ChapterBlocks.imageIds(it.content) }.distinct()
        val images = repo.images.byIds(ids)
        val story = SharedStory(
            title = book.title,
            chapters = chapters.map { SharedChapter(it.title, it.content, it.source.name) },
            bible = repo.bible.listForBook(bookId).map { SharedBible(it.category, it.name, it.details, it.locked) },
            images = images.map { SharedImage(it.id, it.fileName, it.mime, it.prompt) },
        )
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("story.json")); zip.write(gson.toJson(story).toByteArray()); zip.closeEntry()
            images.forEach { img ->
                val f = AiGateway.fileOf(context, img)
                if (!f.exists()) return@forEach
                zip.putNextEntry(ZipEntry("images/${img.fileName}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    /** A temporary .storyboard file to hand to the Android share menu. */
    suspend fun shareFile(context: Context, repo: Repository, bookId: Long): File {
        val title = repo.books.get(bookId)?.title ?: "story"
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, StoryExport.fileName(title, "storyboard"))
        file.outputStream().use { write(context, repo, bookId, it) }
        return file
    }

    fun send(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share story").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Imports a shared story as a new book; returns its id. */
    suspend fun import(context: Context, repo: Repository, input: InputStream): Long {
        var story: SharedStory? = null
        val files = mutableMapOf<String, File>()     // name in zip -> copied file in the gallery
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "story.json" -> story = gson.fromJson(zip.readBytes().decodeToString(), SharedStory::class.java)
                    e.name.startsWith("images/") && !e.isDirectory -> {
                        val name = e.name.substringAfterLast('/')
                        val ext = name.substringAfterLast('.', "png")
                        val dst = File(AiGateway.galleryDir(context), "img_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
                        dst.outputStream().use { zip.copyTo(it) }
                        files[name] = dst
                    }
                }
            }
        }
        val s = story ?: error("This is not a Story-Board story file")
        check(s.format == "storyboard-story") { "This is not a Story-Board story file" }
        return repo.db.withTransaction {
            val bookId = repo.books.insert(Book(title = s.title.ifBlank { "Imported story" }))
            val idMap = mutableMapOf<Long, Long>()
            s.images.forEach { img ->
                val f = files[img.fileName] ?: return@forEach
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                idMap[img.id] = repo.images.insert(
                    GalleryImage(requestId = null, bookId = bookId, chapterId = null, fileName = f.name, mime = img.mime,
                        width = o.outWidth.coerceAtLeast(0), height = o.outHeight.coerceAtLeast(0), bytes = f.length(),
                        prompt = img.prompt, source = "import", sourceLabel = "Imported story", used = true)
                )
            }
            s.chapters.forEachIndexed { i, c ->
                repo.chapters.insert(Chapter(bookId = bookId, position = i, title = c.title, content = ChapterBlocks.remapImages(c.content, idMap),
                    source = runCatching { ChapterSource.valueOf(c.source) }.getOrDefault(ChapterSource.MANUAL), inBible = true))
            }
            s.bible.forEach { repo.bible.insert(BibleEntry(bookId = bookId, category = it.category, name = it.name, details = it.details, locked = it.locked)) }
            bookId
        }
    }
}
