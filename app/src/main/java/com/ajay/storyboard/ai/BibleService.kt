package com.ajay.storyboard.ai

import androidx.compose.runtime.mutableStateMapOf
import com.ajay.storyboard.capture.WebLog
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps each book's story bible up to date: after a chapter is added or its text changes,
 * the chapter is sent to the AI chosen for "Story bible" and the reply is merged in
 * (locked entries are never changed).
 */
class BibleService(
    private val repo: Repository,
    private val ai: AiGateway,
    private val catalog: AiCatalog,
    private val scope: CoroutineScope,
) {
    /** bookId -> what is happening right now (for the UI). */
    val working = mutableStateMapOf<Long, String>()
    /** bookId -> last error. */
    val errors = mutableStateMapOf<Long, String>()
    /** bookId -> target id that failed last time (for "retry with another AI"). */
    val failedWith = mutableStateMapOf<Long, String>()

    private val mutex = Mutex()
    private val scheduled = mutableMapOf<Long, kotlinx.coroutines.Job>()

    /**
     * After edits the update waits a little ([delayMs]) and restarts with every further change, so editing
     * paragraph by paragraph doesn't send the chapter to the AI again and again.
     */
    fun schedule(bookId: Long, delayMs: Long) {
        synchronized(scheduled) {
            scheduled.remove(bookId)?.cancel()
            scheduled[bookId] = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                synchronized(scheduled) { scheduled.remove(bookId) }
                updatePending(bookId)
            }
        }
    }

    /** Processes every chapter of the book whose latest text is not yet in the bible. */
    fun updatePending(bookId: Long, override: String? = null) {
        scope.launch {
            mutex.withLock {
                errors.remove(bookId)
                try {
                    val book = repo.books.get(bookId) ?: return@withLock
                    val all = repo.chapters.listForBook(bookId)
                    for (ch in repo.chapters.pendingBible(bookId)) {
                        val number = all.indexOfFirst { it.id == ch.id } + 1
                        working[bookId] = "Updating story bible from Chapter $number"
                        val service = override?.let { catalog.resolve(it) } ?: catalog.targetFor(AiAction.BIBLE)
                        val reply = ai.ask(service, Prompts.bibleUpdate(book.title, repo.bible.listForBook(bookId), number, ch), "Story bible · Chapter $number")
                        val updates = Prompts.parseBible(reply.text)
                        if (updates == null) {
                            WebLog.add("bible", "could not read JSON: ${reply.text.take(300)}")
                            errors[bookId] = "${service.label} didn't reply in the expected format for Chapter $number. Try again."
                            return@withLock
                        }
                        val changed = repo.applyBible(bookId, updates)
                        repo.chapters.setInBible(ch.id, true)
                        WebLog.add("bible", "chapter $number: $changed entries added/updated")
                    }
                } catch (e: AiException) {
                    val svc = override?.let { catalog.resolve(it) } ?: catalog.targetFor(AiAction.BIBLE)
                    errors[bookId] = if (e.needsLogin) "Log in to ${svc.label} to update the story bible." else (e.message ?: "Story bible update failed")
                    failedWith[bookId] = svc.id
                } catch (e: Exception) {
                    errors[bookId] = e.message ?: "Story bible update failed"
                } finally {
                    working.remove(bookId)
                }
            }
        }
    }

    /** Clears all unlocked entries and rebuilds the bible from every chapter in order. */
    fun rebuild(bookId: Long) {
        scope.launch {
            mutex.withLock {
                repo.bible.deleteUnlocked(bookId)
                repo.chapters.resetBible(bookId)
            }
            updatePending(bookId)
        }
    }
}
