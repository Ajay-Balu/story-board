package com.ajay.storyboard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class BookWithStats(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val chapterCount: Int,
    val pendingDrafts: Int,
    /** 1-based number of the chapter you were reading, if any. */
    val readingChapter: Int?,
    val bibleEntries: Int,
    /** A picture of this book to show on its cover (first one used in a chapter, else the first made). */
    val coverImageId: Long? = null,
    val forkOfTitle: String? = null,
)

data class ChapterSummary(
    @androidx.room.Embedded val chapter: Chapter,
    val versionCount: Int,
    val aiRunCount: Int,
)

@Dao
interface BookDao {
    @Query(
        """
        SELECT b.id, b.title, b.updatedAt,
          (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id) AS chapterCount,
          (SELECT COUNT(*) FROM drafts d WHERE d.bookId = b.id AND d.status IN ('PENDING','STREAMING')) AS pendingDrafts,
          (SELECT c2.position + 1 FROM chapters c2 WHERE c2.id = b.lastChapterId) AS readingChapter,
          (SELECT COUNT(*) FROM bible_entries e WHERE e.bookId = b.id) AS bibleEntries,
          CASE b.coverMode
            WHEN 2 THEN NULL
            WHEN 1 THEN (SELECT gc.id FROM gallery_images gc WHERE gc.id = b.coverPictureId)
            ELSE COALESCE(
              (SELECT g.id FROM gallery_images g WHERE g.bookId = b.id ORDER BY g.used DESC, g.createdAt ASC LIMIT 1),
              (SELECT g2.id FROM gallery_images g2 WHERE g2.bookId = b.forkOfBookId ORDER BY g2.used DESC, g2.createdAt ASC LIMIT 1)
            )
          END AS coverImageId,
          b.forkOfTitle AS forkOfTitle
        FROM books b ORDER BY b.updatedAt DESC
        """
    )
    fun observeLibrary(): Flow<List<BookWithStats>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Insert suspend fun insert(book: Book): Long
    @Update suspend fun update(book: Book)
    @Delete suspend fun delete(book: Book)

    @Query("UPDATE books SET lastChapterId = :chapterId, lastParagraph = :paragraph, lastOffset = :offset WHERE id = :bookId")
    suspend fun savePosition(bookId: Long, chapterId: Long?, paragraph: Int, offset: Int)

    @Query("UPDATE books SET deepseekChatUrl = :url, chatService = :service WHERE id = :bookId")
    suspend fun saveChatUrl(bookId: Long, url: String?, service: String)

    @Query("UPDATE books SET updatedAt = :time WHERE id = :bookId")
    suspend fun touch(bookId: Long, time: Long = System.currentTimeMillis())
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    fun observeForBook(bookId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    suspend fun listForBook(bookId: Long): List<Chapter>

    @Query(
        """
        SELECT c.*,
          (SELECT COUNT(*) FROM chapter_versions v WHERE v.chapterId = c.id) AS versionCount,
          (SELECT COUNT(*) FROM ai_runs r WHERE r.chapterId = c.id) AS aiRunCount
        FROM chapters c WHERE c.bookId = :bookId ORDER BY c.position
        """
    )
    fun observeSummaries(bookId: Long): Flow<List<ChapterSummary>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Long): Chapter?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM chapters WHERE bookId = :bookId")
    suspend fun nextPosition(bookId: Long): Int

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND inBible = 0 ORDER BY position")
    suspend fun pendingBible(bookId: Long): List<Chapter>

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND inBible = 0")
    fun observePendingBible(bookId: Long): Flow<Int>

    @Query("UPDATE chapters SET inBible = :value WHERE id = :id")
    suspend fun setInBible(id: Long, value: Boolean)

    @Query("UPDATE chapters SET inBible = 0 WHERE bookId = :bookId")
    suspend fun resetBible(bookId: Long)

    @Insert suspend fun insert(chapter: Chapter): Long
    @Update suspend fun update(chapter: Chapter)
    @Update suspend fun updateAll(chapters: List<Chapter>)
    @Delete suspend fun delete(chapter: Chapter)
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM chapter_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC")
    fun observeForChapter(chapterId: Long): Flow<List<ChapterVersion>>

    @Insert suspend fun insert(version: ChapterVersion): Long

    @Delete suspend fun delete(version: ChapterVersion)

    @Query("SELECT * FROM chapter_versions WHERE chapterId = :chapterId")
    suspend fun listForChapter(chapterId: Long): List<ChapterVersion>

    @Insert suspend fun insertAll(versions: List<ChapterVersion>)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE bookId = :bookId AND status IN ('PENDING','STREAMING') ORDER BY createdAt DESC")
    fun observePending(bookId: Long): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun get(id: Long): Draft?

    @Query("SELECT * FROM drafts WHERE id = :id")
    fun observe(id: Long): Flow<Draft?>

    @Insert suspend fun insert(draft: Draft): Long
    @Update suspend fun update(draft: Draft)

    @Query("UPDATE drafts SET content = :content, updatedAt = :time WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE drafts SET status = :status, updatedAt = :time WHERE id = :id")
    suspend fun setStatus(id: Long, status: DraftStatus, time: Long = System.currentTimeMillis())

    /** Replies already added or discarded (kept so they can be added again). */
    @Query("SELECT * FROM drafts WHERE bookId = :bookId AND status IN ('ADDED','DISCARDED') AND length(content) > 0 ORDER BY createdAt DESC")
    fun observeEarlier(bookId: Long): Flow<List<Draft>>

    @Query("UPDATE drafts SET status = 'DISCARDED' WHERE bookId = :bookId AND status IN ('PENDING','STREAMING')")
    suspend fun discardAll(bookId: Long)

    /** Replies that were still streaming when the app was killed become reviewable drafts. */
    @Query("UPDATE drafts SET status = 'PENDING' WHERE status = 'STREAMING'")
    suspend fun recoverInterrupted()
}

@Dao
interface BibleDao {
    @Query("SELECT * FROM bible_entries WHERE bookId = :bookId ORDER BY category, name")
    fun observeForBook(bookId: Long): Flow<List<BibleEntry>>

    @Query("SELECT * FROM bible_entries WHERE bookId = :bookId ORDER BY category, name")
    suspend fun listForBook(bookId: Long): List<BibleEntry>

    @Query("SELECT * FROM bible_entries WHERE bookId = :bookId AND category = :category AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun find(bookId: Long, category: String, name: String): BibleEntry?

    @Query("DELETE FROM bible_entries WHERE bookId = :bookId AND locked = 0")
    suspend fun deleteUnlocked(bookId: Long)

    @Query("DELETE FROM bible_entries WHERE bookId = :bookId")
    suspend fun deleteAll(bookId: Long)

    @Insert suspend fun insert(entry: BibleEntry): Long
    @Update suspend fun update(entry: BibleEntry)
    @Delete suspend fun delete(entry: BibleEntry)
}

@Dao
interface AiRunDao {
    @Query("SELECT * FROM ai_runs WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: Long): Flow<List<AiRun>>

    @Query("SELECT * FROM ai_runs WHERE chapterId = :chapterId ORDER BY createdAt DESC")
    fun observeForChapter(chapterId: Long): Flow<List<AiRun>>

    @Query("SELECT * FROM ai_runs WHERE id = :id")
    fun observe(id: Long): Flow<AiRun?>

    @Query("SELECT * FROM ai_runs WHERE id = :id")
    suspend fun get(id: Long): AiRun?

    @Insert suspend fun insert(run: AiRun): Long

    @Query("UPDATE ai_runs SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: RunStatus)

    @Delete suspend fun delete(run: AiRun)

    @Query("DELETE FROM ai_runs WHERE bookId = :bookId AND (:chapterId IS NULL OR chapterId = :chapterId)")
    suspend fun deleteAll(bookId: Long, chapterId: Long?)

    @Query("DELETE FROM ai_runs WHERE bookId = :bookId AND (:chapterId IS NULL OR chapterId = :chapterId) AND (kind = 'CHECK' OR status != 'ACCEPTED')")
    suspend fun deleteUnused(bookId: Long, chapterId: Long?)
}

@Dao
interface AiConfigDao {
    @Query("SELECT * FROM api_profiles ORDER BY name")
    fun observeProfiles(): Flow<List<ApiProfile>>

    @Query("SELECT * FROM api_profiles WHERE id = :id")
    suspend fun profile(id: Long): ApiProfile?

    @Insert suspend fun insertProfile(p: ApiProfile): Long
    @Update suspend fun updateProfile(p: ApiProfile)
    @Delete suspend fun deleteProfile(p: ApiProfile)

    @Query("SELECT * FROM custom_sites ORDER BY name")
    fun observeSites(): Flow<List<CustomSite>>

    @Query("SELECT * FROM custom_sites WHERE id = :id")
    suspend fun site(id: Long): CustomSite?

    @Insert suspend fun insertSite(s: CustomSite): Long
    @Update suspend fun updateSite(s: CustomSite)
    @Delete suspend fun deleteSite(s: CustomSite)
}

@Dao
interface ImageDao {
    @Query("SELECT * FROM gallery_images ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GalleryImage>>

    @Query("SELECT * FROM gallery_images WHERE id = :id")
    fun observe(id: Long): Flow<GalleryImage?>

    @Query("SELECT * FROM gallery_images WHERE bookId IN (:bookIds) ORDER BY used DESC, createdAt DESC")
    fun observeForBooks(bookIds: List<Long>): Flow<List<GalleryImage>>

    @Query("SELECT * FROM gallery_images WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<GalleryImage>

    @Query("SELECT * FROM gallery_images WHERE requestId = :requestId ORDER BY id")
    fun observeForRequest(requestId: Long): Flow<List<GalleryImage>>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM gallery_images")
    fun observeTotalBytes(): Flow<Long>

    @Insert suspend fun insert(img: GalleryImage): Long
    @Update suspend fun update(img: GalleryImage)
    @Delete suspend fun delete(imgs: List<GalleryImage>)

    @Query("SELECT * FROM image_requests ORDER BY createdAt DESC LIMIT 200")
    fun observeRequests(): Flow<List<ImageRequest>>

    @Query("SELECT * FROM image_requests WHERE bookId = :bookId AND (:chapterId IS NULL OR chapterId = :chapterId) ORDER BY createdAt DESC")
    fun observeRequestsFor(bookId: Long, chapterId: Long?): Flow<List<ImageRequest>>

    @Query("SELECT * FROM gallery_images WHERE requestId IN (SELECT id FROM image_requests WHERE bookId = :bookId) ORDER BY id")
    fun observeRequestImagesForBook(bookId: Long): Flow<List<GalleryImage>>

    @Query("SELECT * FROM image_requests WHERE id = :id")
    suspend fun request(id: Long): ImageRequest?

    @Query("UPDATE image_requests SET status = 'FAILED', error = 'Interrupted (the app was closed)' WHERE status = 'PENDING'")
    suspend fun failInterrupted()

    @Insert suspend fun insertRequest(r: ImageRequest): Long
    @Update suspend fun updateRequest(r: ImageRequest)
    @Delete suspend fun deleteRequest(r: ImageRequest)
}

@Dao
interface ApiChatDao {
    @Query("SELECT * FROM api_chat_messages WHERE bookId = :bookId AND session = :session ORDER BY id")
    fun observe(bookId: Long, session: Int): Flow<List<ApiChatMessage>>

    @Query("SELECT * FROM api_chat_messages WHERE bookId = :bookId AND session = :session ORDER BY id")
    suspend fun list(bookId: Long, session: Int): List<ApiChatMessage>

    @Query("SELECT COALESCE(MAX(session), 1) FROM api_chat_messages WHERE bookId = :bookId")
    suspend fun latestSession(bookId: Long): Int

    @Insert suspend fun insert(m: ApiChatMessage): Long
    @Update suspend fun update(m: ApiChatMessage)

    @Query("DELETE FROM api_chat_messages WHERE bookId = :bookId AND session = :session AND id >= :fromId")
    suspend fun deleteFrom(bookId: Long, session: Int, fromId: Long)
}

@Dao
interface RoundtableDao {
    @Query("SELECT * FROM roundtables ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Roundtable>>

    @Query("SELECT * FROM roundtables WHERE id = :id")
    fun observe(id: Long): Flow<Roundtable?>

    @Query("SELECT * FROM roundtables WHERE id = :id")
    suspend fun get(id: Long): Roundtable?

    @Query("SELECT * FROM roundtable_messages WHERE roundtableId = :id ORDER BY turn, round, id")
    fun observeMessages(id: Long): Flow<List<RoundtableMessage>>

    @Query("SELECT * FROM roundtable_messages WHERE roundtableId = :id ORDER BY turn, round, id")
    suspend fun messages(id: Long): List<RoundtableMessage>

    @Query("SELECT * FROM roundtable_messages WHERE id = :id")
    suspend fun message(id: Long): RoundtableMessage?

    @Insert suspend fun insert(r: Roundtable): Long
    @Update suspend fun update(r: Roundtable)
    @Delete suspend fun delete(r: Roundtable)
    @Insert suspend fun insertMessage(m: RoundtableMessage): Long
    @Update suspend fun updateMessage(m: RoundtableMessage)
    @Delete suspend fun deleteMessage(m: RoundtableMessage)

    @Query("UPDATE roundtables SET status = 'FAILED' WHERE status = 'RUNNING'")
    suspend fun failInterrupted()

    @Query("UPDATE roundtable_messages SET status = 'FAILED', error = 'Interrupted (the app was closed)' WHERE status IN ('PENDING','RUNNING')")
    suspend fun failInterruptedMessages()
}

/** Everything at once, for backup / restore. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM books") suspend fun books(): List<Book>
    @Query("SELECT * FROM chapters") suspend fun chapters(): List<Chapter>
    @Query("SELECT * FROM chapter_versions") suspend fun versions(): List<ChapterVersion>
    @Query("SELECT * FROM drafts") suspend fun drafts(): List<Draft>
    @Query("SELECT * FROM bible_entries") suspend fun bible(): List<BibleEntry>
    @Query("SELECT * FROM ai_runs") suspend fun runs(): List<AiRun>
    @Query("SELECT * FROM custom_sites") suspend fun sites(): List<CustomSite>
    @Query("SELECT * FROM image_requests") suspend fun imageRequests(): List<ImageRequest>
    @Query("SELECT * FROM gallery_images") suspend fun images(): List<GalleryImage>
    @Query("SELECT * FROM api_chat_messages") suspend fun apiChat(): List<ApiChatMessage>
    @Query("SELECT * FROM roundtables") suspend fun roundtables(): List<Roundtable>
    @Query("SELECT * FROM roundtable_messages") suspend fun roundtableMessages(): List<RoundtableMessage>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putBooks(v: List<Book>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putChapters(v: List<Chapter>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putVersions(v: List<ChapterVersion>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putDrafts(v: List<Draft>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putBible(v: List<BibleEntry>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putRuns(v: List<AiRun>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putSites(v: List<CustomSite>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putImageRequests(v: List<ImageRequest>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putImages(v: List<GalleryImage>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putApiChat(v: List<ApiChatMessage>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putRoundtables(v: List<Roundtable>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putRoundtableMessages(v: List<RoundtableMessage>)

    @Query("DELETE FROM books") suspend fun clearBooks()
    @Query("DELETE FROM custom_sites") suspend fun clearSites()
    @Query("DELETE FROM image_requests") suspend fun clearImageRequests()
    @Query("DELETE FROM gallery_images") suspend fun clearImages()
    @Query("DELETE FROM roundtables") suspend fun clearRoundtables()
}
