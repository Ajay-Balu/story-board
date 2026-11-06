package com.ajay.storyboard.data

import androidx.room.withTransaction

class Repository(val db: AppDatabase) {
    /** Called after a chapter is added ([added] = true) or its text changed (used to refresh the story bible). */
    var onChapterChanged: ((bookId: Long, chapterId: Long, added: Boolean) -> Unit)? = null

    val books = db.books()
    val chapters = db.chapters()
    val versions = db.versions()
    val drafts = db.drafts()
    val bible = db.bible()
    val runs = db.runs()
    val aiConfig = db.aiConfig()
    val images = db.images()
    val apiChat = db.apiChat()
    val roundtables = db.roundtables()
    val backup = db.backup()

    suspend fun createBook(title: String): Long = books.insert(Book(title = title.trim()))

    /** Cover: [CoverMode.AUTO], a chosen gallery picture, or [CoverMode.NONE] (a cloth cover). */
    suspend fun setCover(id: Long, mode: Int, pictureId: Long? = null) {
        val b = books.get(id) ?: return
        books.update(b.copy(coverMode = mode, coverPictureId = if (mode == CoverMode.CHOSEN) pictureId else null))
    }

    suspend fun renameBook(id: Long, title: String) {
        val b = books.get(id) ?: return
        books.update(b.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteBook(id: Long) {
        books.get(id)?.let { books.delete(it) }
    }

    suspend fun addChapter(bookId: Long, title: String, content: String, source: ChapterSource): Long =
        db.withTransaction {
            val pos = chapters.nextPosition(bookId)
            val id = chapters.insert(
                Chapter(bookId = bookId, position = pos, title = title.trim(), content = content.trim(), source = source)
            )
            books.touch(bookId)
            id
        }.also { onChapterChanged?.invoke(bookId, it, true) }

    /** Replaces chapter text, keeping the old text as a version. */
    suspend fun updateChapter(chapterId: Long, title: String, content: String, note: String) {
        val changed = db.withTransaction {
            val old = chapters.get(chapterId) ?: return@withTransaction null
            if (old.title == title.trim() && old.content == content.trim()) return@withTransaction null
            versions.insert(ChapterVersion(chapterId = old.id, title = old.title, content = old.content, note = note))
            // pictures added / moved / resized don't change the story text (no story-bible update needed)
            val textChanged = ChapterBlocks.textOnly(old.content) != ChapterBlocks.textOnly(content.trim())
            chapters.update(
                old.copy(
                    title = title.trim(), content = content.trim(), updatedAt = System.currentTimeMillis(),
                    inBible = old.inBible && !textChanged,
                )
            )
            books.touch(old.bookId)
            if (textChanged) old.bookId else null
        }
        if (changed != null) onChapterChanged?.invoke(changed, chapterId, false)
    }

    /** Clears the story bible; [keepLocked] keeps entries you locked. Chapters are marked to be read again. */
    suspend fun clearBible(bookId: Long, keepLocked: Boolean) {
        db.withTransaction {
            if (keepLocked) bible.deleteUnlocked(bookId) else bible.deleteAll(bookId)
            chapters.resetBible(bookId)
        }
    }

    suspend fun restoreVersion(version: ChapterVersion) =
        updateChapter(version.chapterId, version.title, version.content, note = "Before restoring an older version")

    /** Deletes a chapter and returns a snapshot (chapter + versions) that [restoreChapter] can put back. */
    suspend fun deleteChapter(chapterId: Long): Pair<Chapter, List<ChapterVersion>>? =
        db.withTransaction {
            val ch = chapters.get(chapterId) ?: return@withTransaction null
            val vs = versions.listForChapter(chapterId)
            chapters.delete(ch)
            renumber(ch.bookId)
            ch to vs
        }

    /** Undo for [deleteChapter]: puts the chapter back at its old position with its versions. */
    suspend fun restoreChapter(snapshot: Pair<Chapter, List<ChapterVersion>>) {
        val (ch, vs) = snapshot
        db.withTransaction {
            val list = chapters.listForBook(ch.bookId).toMutableList()
            chapters.insert(ch)
            list.add(ch.position.coerceIn(0, list.size), ch)
            chapters.updateAll(list.mapIndexed { i, c -> c.copy(position = i) })
            if (vs.isNotEmpty()) versions.insertAll(vs)
            books.touch(ch.bookId)
        }
    }

    /** Adds an earlier (already added or discarded) reply to the book again as a new chapter. */
    suspend fun addEarlierReply(draft: Draft, title: String, content: String): Long =
        addChapter(draft.bookId, title, content, if (draft.erased) ChapterSource.AI_RECOVERED else ChapterSource.AI)

    /** Moves a chapter up (-1) or down (+1). */
    suspend fun moveChapter(bookId: Long, chapterId: Long, delta: Int) {
        db.withTransaction {
            val list = chapters.listForBook(bookId).toMutableList()
            val i = list.indexOfFirst { it.id == chapterId }
            val j = i + delta
            if (i < 0 || j !in list.indices) return@withTransaction
            val item = list.removeAt(i)
            list.add(j, item)
            chapters.updateAll(list.mapIndexed { idx, c -> c.copy(position = idx) })
        }
    }

    private suspend fun renumber(bookId: Long) {
        val list = chapters.listForBook(bookId)
        chapters.updateAll(list.mapIndexed { idx, c -> c.copy(position = idx) })
    }

    /**
     * Adds a captured reply as the next chapter. When [content] differs from the reply (refactored),
     * the original reply is kept as the chapter's first version.
     */
    suspend fun addDraftToBook(draftId: Long, title: String, content: String): Long? {
        val d = drafts.get(draftId) ?: return null
        val id = addChapter(d.bookId, title, content, if (d.erased) ChapterSource.AI_RECOVERED else ChapterSource.AI)
        if (content.trim() != d.content.trim()) {
            versions.insert(ChapterVersion(chapterId = id, title = title, content = d.content, note = "Original AI reply before refactor"))
        }
        drafts.setStatus(draftId, DraftStatus.ADDED)
        return id
    }

    // ------------------------------------------------------------------ edit mode

    /** Replaces paragraphs [from]..[to] (inclusive, indexes into [ChapterBlocks.lines]) with [replacement] lines. */
    suspend fun replaceParagraphs(chapterId: Long, from: Int, to: Int, replacement: List<String>, note: String) {
        val ch = chapters.get(chapterId) ?: return
        val lines = ChapterBlocks.lines(ch.content).toMutableList()
        val a = from.coerceIn(0, lines.size)
        val b = to.coerceIn(a - 1, lines.size - 1)
        repeat(b - a + 1) { lines.removeAt(a) }
        lines.addAll(a, replacement.flatMap { ChapterBlocks.lines(it) })
        updateChapter(chapterId, ch.title, ChapterBlocks.join(lines), note)
    }

    /** Inserts lines before index [at] (0 = chapter start, size = end). */
    suspend fun insertParagraphs(chapterId: Long, at: Int, newLines: List<String>, note: String) =
        replaceParagraphs(chapterId, at, at - 1, newLines, note)

    /** Moves paragraphs [from]..[to] up (-1) or down (+1) by one place. */
    suspend fun moveParagraphs(chapterId: Long, from: Int, to: Int, delta: Int): Boolean {
        val ch = chapters.get(chapterId) ?: return false
        val lines = ChapterBlocks.lines(ch.content).toMutableList()
        if (from < 0 || to >= lines.size) return false
        val target = from + delta
        if (target < 0 || to + delta >= lines.size) return false
        val block = lines.subList(from, to + 1).toList()
        repeat(block.size) { lines.removeAt(from) }
        lines.addAll(target, block)
        updateChapter(chapterId, ch.title, ChapterBlocks.join(lines), "Moved paragraphs")
        return true
    }

    /** Puts a gallery picture into a chapter at [at] and marks it as used. */
    suspend fun insertPicture(chapterId: Long, at: Int, imageId: Long, caption: String = "") {
        insertParagraphs(chapterId, at, listOf(ChapterBlocks.pictureLine(imageId, caption)), "Added a picture")
        images.byIds(listOf(imageId)).firstOrNull()?.let { if (!it.used) images.update(it.copy(used = true)) }
    }

    // ------------------------------------------------------------------ fork

    /**
     * Copies chapters 1..[chapterCount] and the whole story bible into a new, separate book.
     * Pictures are shared (same gallery entries). Returns the new book id.
     */
    suspend fun forkBook(bookId: Long, chapterCount: Int, title: String): Long = db.withTransaction {
        val book = books.get(bookId) ?: error("Book not found")
        val newId = books.insert(
            Book(title = title.trim(), forkOfBookId = book.id, forkChapter = chapterCount, forkOfTitle = book.title)
        )
        chapters.listForBook(bookId).take(chapterCount).forEachIndexed { i, ch ->
            chapters.insert(ch.copy(id = 0, bookId = newId, position = i, inBible = true,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        }
        bible.listForBook(bookId)
            // timeline entries of chapters that are not in the fork are left out
            .filterNot { it.category == "TIMELINE" && (Regex("\\d+").find(it.name)?.value?.toIntOrNull() ?: 0) > chapterCount }
            .forEach { bible.insert(it.copy(id = 0, bookId = newId)) }
        newId
    }

    /** Applies AI bible updates: adds new entries and updates existing ones, never touching locked ones. */
    suspend fun applyBible(bookId: Long, updates: List<Triple<String, String, String>>): Int {
        var n = 0
        db.withTransaction {
            for ((category, name, details) in updates) {
                if (name.isBlank() || details.isBlank()) continue
                val existing = bible.find(bookId, category, name)
                when {
                    existing == null -> { bible.insert(BibleEntry(bookId = bookId, category = category, name = name.trim(), details = details.trim())); n++ }
                    existing.locked -> Unit
                    existing.details != details.trim() -> { bible.update(existing.copy(details = details.trim(), updatedAt = System.currentTimeMillis())); n++ }
                }
            }
        }
        return n
    }
}
