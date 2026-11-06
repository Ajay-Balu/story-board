package com.ajay.storyboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Last DeepSeek chat URL used for this book (e.g. https://chat.deepseek.com/a/chat/s/<id>). */
    val deepseekChatUrl: String? = null,
    /** Which service [deepseekChatUrl] belongs to (the column name is historical). */
    @ColumnInfo(defaultValue = "DEEPSEEK") val chatService: String = "DEEPSEEK",
    /** Reading position: chapter + paragraph + pixel offset inside that paragraph. */
    val lastChapterId: Long? = null,
    val lastParagraph: Int = 0,
    val lastOffset: Int = 0,
    /** Set on a fork: the book it was copied from, at which chapter, and that book's title then. */
    val forkOfBookId: Long? = null,
    val forkChapter: Int? = null,
    val forkOfTitle: String? = null,
    /** Cover picture: 0 = automatic (the book's first picture), 1 = [coverPictureId], 2 = no picture (a cloth cover). */
    @ColumnInfo(defaultValue = "0") val coverMode: Int = 0,
    val coverPictureId: Long? = null,
)

object CoverMode { const val AUTO = 0; const val CHOSEN = 1; const val NONE = 2 }

enum class ChapterSource { MANUAL, AI, AI_RECOVERED }

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId")],
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val position: Int,
    val title: String,
    val content: String,
    val source: ChapterSource = ChapterSource.MANUAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** True once the story bible has been updated from this version of the chapter. */
    @ColumnInfo(defaultValue = "0") val inBible: Boolean = false,
)

/** Older copies of a chapter, saved every time its text is replaced (edit, refactor, restore). */
@Entity(
    tableName = "chapter_versions",
    foreignKeys = [ForeignKey(
        entity = Chapter::class, parentColumns = ["id"], childColumns = ["chapterId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("chapterId")],
)
data class ChapterVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val title: String,
    val content: String,
    val note: String,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class DraftStatus { STREAMING, PENDING, ADDED, DISCARDED }

/** Every AI reply captured from the chat page, saved while it streams so nothing is lost. */
@Entity(
    tableName = "drafts",
    foreignKeys = [ForeignKey(
        entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId")],
)
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val service: String,
    val prompt: String?,
    val content: String,
    /** True when the chat site removed / replaced the reply after streaming it (guardrail). */
    val erased: Boolean = false,
    val status: DraftStatus = DraftStatus.STREAMING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Story bible entries (filled automatically by AI in Milestone 2; editable and lockable). */
@Entity(
    tableName = "bible_entries",
    foreignKeys = [ForeignKey(
        entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId")],
)
data class BibleEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** CHARACTER, PLACE, OUTFIT, OBJECT, RULE, TIMELINE ... */
    val category: String,
    val name: String,
    val details: String,
    val locked: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** EDIT = "Edit with AI" on selected paragraphs (result is only those paragraphs). */
enum class RunKind { CHECK, REWRITE, EDIT }
enum class RunStatus { PENDING, ACCEPTED, REJECTED }

/**
 * Every completed AI refactor step (consistency check or rewrite), kept until you delete it,
 * whether or not the result was used. chapterId / draftId are not foreign keys on purpose,
 * so history survives when a chapter or captured reply is removed.
 */
@Entity(
    tableName = "ai_runs",
    foreignKeys = [ForeignKey(
        entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId"), Index("chapterId")],
)
data class AiRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long?,
    val draftId: Long?,
    val kind: RunKind,
    val service: String,
    /** Chapter title at the time of the run. */
    val title: String,
    val prompt: String,
    val original: String,
    val result: String,
    val status: RunStatus = RunStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
)
