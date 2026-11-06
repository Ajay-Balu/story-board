package com.ajay.storyboard.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.ajay.storyboard.ai.AiGateway
import com.ajay.storyboard.capture.WebLog
import com.ajay.storyboard.data.AiRun
import com.ajay.storyboard.data.ApiChatMessage
import com.ajay.storyboard.data.AppDatabase
import com.ajay.storyboard.data.BibleEntry
import com.ajay.storyboard.data.Book
import com.ajay.storyboard.data.Chapter
import com.ajay.storyboard.data.ChapterVersion
import com.ajay.storyboard.data.CustomSite
import com.ajay.storyboard.data.Draft
import com.ajay.storyboard.data.GalleryImage
import com.ajay.storyboard.data.ImageRequest
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.data.Roundtable
import com.ajay.storyboard.data.RoundtableMessage
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Everything in a backup. API keys and website logins are never included. */
data class BackupData(
    val format: String = "storyboard-backup",
    val version: Int = 1,
    val dbVersion: Int = AppDatabase.VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val books: List<Book> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val versions: List<ChapterVersion> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val bible: List<BibleEntry> = emptyList(),
    val runs: List<AiRun> = emptyList(),
    val sites: List<CustomSite> = emptyList(),
    val imageRequests: List<ImageRequest> = emptyList(),
    val images: List<GalleryImage> = emptyList(),
    val apiChat: List<ApiChatMessage> = emptyList(),
    val roundtables: List<Roundtable> = emptyList(),
    val roundtableMessages: List<RoundtableMessage> = emptyList(),
)

data class BackupFile(val name: String, val uri: Uri, val time: Long, val bytes: Long)

/**
 * Backups to a folder you pick with Android's folder picker (any folder, including a Google Drive folder
 * through the Files app), about 2 minutes after each change; the last 10 are kept. Pictures are stored once
 * in an "images" folder next to the backups. Manual backup / restore uses a single .zip file.
 */
class BackupManager(
    private val context: Context,
    private val repo: Repository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val gson = GsonBuilder().create()
    private val mutex = Mutex()
    private var pending: Job? = null

    var folder by mutableStateOf(prefs.getString("tree", null))
        private set
    var auto by mutableStateOf(prefs.getBoolean("auto", true))
        private set
    var lastAt by mutableStateOf(prefs.getLong("last", 0L))
        private set
    var lastError by mutableStateOf(prefs.getString("error", null))
        private set
    /** What is happening right now (null = idle). */
    var working by mutableStateOf<String?>(null)
        private set

    fun start() {
        repo.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                if (restoring) return
                prefs.edit().putBoolean("dirty", true).apply()
                schedule(DELAY_MS)
            }
        })
        // changes from last time that were never backed up (app closed within 2 minutes)
        if (prefs.getBoolean("dirty", false)) schedule(20_000)
    }

    @Volatile private var restoring = false

    private fun schedule(delayMs: Long) {
        if (!auto || folder == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            backupToFolder()
        }
    }

    fun setFolder(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        folder = uri.toString()
        prefs.edit().putString("tree", folder).apply()
        scope.launch { backupToFolder() }
    }

    fun clearFolder() {
        folder?.let { f -> runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        folder = null
        prefs.edit().remove("tree").apply()
    }

    fun updateAuto(value: Boolean) {
        auto = value
        prefs.edit().putBoolean("auto", value).apply()
        if (value) schedule(5_000)
    }

    fun folderName(): String? = folder?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }

    // ------------------------------------------------------------------ collect / apply

    private suspend fun collect(): BackupData {
        val b = repo.backup
        return BackupData(
            books = b.books(), chapters = b.chapters(), versions = b.versions(), drafts = b.drafts(), bible = b.bible(),
            runs = b.runs(), sites = b.sites(), imageRequests = b.imageRequests(), images = b.images(), apiChat = b.apiChat(),
            roundtables = b.roundtables(), roundtableMessages = b.roundtableMessages(),
        )
    }

    private suspend fun apply(d: BackupData) {
        val b = repo.backup
        restoring = true
        try {
            repo.db.withTransaction {
                b.clearBooks(); b.clearSites(); b.clearImages(); b.clearImageRequests(); b.clearRoundtables()
                b.putBooks(d.books); b.putChapters(d.chapters); b.putVersions(d.versions); b.putDrafts(d.drafts)
                b.putBible(d.bible); b.putRuns(d.runs); b.putSites(d.sites); b.putImageRequests(d.imageRequests)
                b.putImages(d.images); b.putApiChat(d.apiChat); b.putRoundtables(d.roundtables); b.putRoundtableMessages(d.roundtableMessages)
            }
        } finally {
            restoring = false
        }
    }

    /** Before a restore, the current data is saved inside the app (files/db-safety). */
    private suspend fun safetyCopy() {
        val dir = File(context.filesDir, "db-safety").apply { mkdirs() }
        File(dir, "before-restore-${stamp()}.json").writeText(gson.toJson(collect()))
        dir.listFiles { f -> f.name.startsWith("before-restore-") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------------ folder backups

    private fun tree(): DocumentFile? = folder?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }

    private fun subdir(root: DocumentFile, name: String): DocumentFile =
        root.findFile(name)?.takeIf { it.isDirectory } ?: root.createDirectory(name) ?: error("Can't create the $name folder")

    /** Writes a new backup into the chosen folder now. */
    suspend fun backupToFolder(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = tree() ?: return@withContext false
            working = "Backing up…"
            try {
                val data = collect()
                val backups = subdir(root, "backups")
                val imagesDir = subdir(root, "images")
                // pictures: copied once, kept by file name
                val have = imagesDir.listFiles().mapNotNull { it.name }.toHashSet()
                data.images.forEach { img ->
                    if (img.fileName in have) return@forEach
                    val src = AiGateway.fileOf(context, img)
                    if (!src.exists()) return@forEach
                    val f = imagesDir.createFile(img.mime.ifBlank { "image/png" }, img.fileName) ?: return@forEach
                    context.contentResolver.openOutputStream(f.uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    // some providers append an extension; keep the exact name for matching
                    if (f.name != img.fileName) runCatching { f.renameTo(img.fileName) }
                }
                val file = backups.createFile("application/json", "storyboard-backup-${stamp()}.json") ?: error("Can't create the backup file")
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(gson.toJson(data).toByteArray()) } ?: error("Can't write the backup file")
                // keep the newest 10
                backups.listFiles().filter { it.name?.startsWith("storyboard-backup-") == true }
                    .sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
                lastAt = System.currentTimeMillis(); lastError = null
                prefs.edit().putLong("last", lastAt).remove("error").putBoolean("dirty", false).apply()
                true
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                prefs.edit().putString("error", lastError).apply()
                WebLog.add("backup", "failed: $lastError")
                false
            } finally {
                working = null
            }
        }
    }

    suspend fun listBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        val root = tree() ?: return@withContext emptyList()
        val dir = root.findFile("backups") ?: return@withContext emptyList()
        dir.listFiles().filter { it.name?.startsWith("storyboard-backup-") == true }
            .map { BackupFile(it.name!!, it.uri, it.lastModified(), it.length()) }
            .sortedByDescending { it.name }
    }

    suspend fun restoreFromFolder(file: BackupFile) = mutex.withLock {
        withContext(Dispatchers.IO) {
            working = "Restoring…"
            try {
                val data = context.contentResolver.openInputStream(file.uri)!!.use { gson.fromJson(it.reader(), BackupData::class.java) }
                check(data.format == "storyboard-backup") { "Not a Story-Board backup" }
                val imagesDir = tree()?.findFile("images")
                val byName = imagesDir?.listFiles()?.associateBy { it.name } ?: emptyMap()
                data.images.forEach { img ->
                    val dst = AiGateway.fileOf(context, img)
                    if (dst.exists()) return@forEach
                    val src = byName[img.fileName] ?: return@forEach
                    context.contentResolver.openInputStream(src.uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
                }
                safetyCopy()
                apply(data)
            } finally {
                working = null
            }
        }
    }

    // ------------------------------------------------------------------ manual .zip

    /** One file with everything (data + pictures), for keeping anywhere you like. */
    suspend fun backupToZip(out: OutputStream) = mutex.withLock {
        withContext(Dispatchers.IO) {
            working = "Creating the backup file…"
            try {
                val data = collect()
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("backup.json")); zip.write(gson.toJson(data).toByteArray()); zip.closeEntry()
                    data.images.forEach { img ->
                        val f = AiGateway.fileOf(context, img)
                        if (!f.exists()) return@forEach
                        zip.putNextEntry(ZipEntry("images/${img.fileName}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
            } finally {
                working = null
            }
        }
    }

    /** Restores a .zip made by [backupToZip] (or a single backup .json). Replaces everything except API keys. */
    suspend fun restoreFromFile(input: InputStream) = mutex.withLock {
        withContext(Dispatchers.IO) {
            working = "Restoring…"
            try {
                val bytes = input.readBytes()
                var data: BackupData? = null
                if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    ZipInputStream(bytes.inputStream()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            when {
                                e.name == "backup.json" -> data = gson.fromJson(zip.readBytes().decodeToString(), BackupData::class.java)
                                e.name.startsWith("images/") && !e.isDirectory -> {
                                    val dst = File(AiGateway.galleryDir(context), e.name.substringAfter("images/").substringAfterLast('/'))
                                    if (!dst.exists()) dst.outputStream().use { zip.copyTo(it) }
                                }
                            }
                        }
                    }
                } else data = gson.fromJson(bytes.decodeToString(), BackupData::class.java)
                val d = data ?: error("No backup inside this file")
                check(d.format == "storyboard-backup") { "Not a Story-Board backup" }
                safetyCopy()
                apply(d)
            } finally {
                working = null
            }
        }
    }

    companion object {
        const val DELAY_MS = 2 * 60_000L
        const val KEEP = 10
        val TABLES = arrayOf(
            "books", "chapters", "chapter_versions", "drafts", "bible_entries", "ai_runs", "custom_sites",
            "image_requests", "gallery_images", "api_chat_messages", "roundtables", "roundtable_messages",
        )
    }
}
