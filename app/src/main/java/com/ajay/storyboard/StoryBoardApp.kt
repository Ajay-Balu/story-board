package com.ajay.storyboard

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ajay.storyboard.ai.AiCatalog
import com.ajay.storyboard.ai.AiGateway
import com.ajay.storyboard.ai.AiRunner
import com.ajay.storyboard.ai.BibleService
import com.ajay.storyboard.ai.RoundtableService
import com.ajay.storyboard.backup.BackupManager
import com.ajay.storyboard.data.AppDatabase
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StoryBoardApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var settings: Settings
        private set
    val runner = AiRunner()
    lateinit var bible: BibleService
        private set
    lateinit var catalog: AiCatalog
        private set
    /** Ask any AI (web chat, taught site or API key). */
    lateinit var ai: AiGateway
        private set
    lateinit var roundtable: RoundtableService
        private set
    lateinit var backup: BackupManager
        private set

    /** Text to put in the message box the next time the writing page opens (e.g. a roundtable verdict). */
    var writeNote by mutableStateOf<String?>(null)

    /** Survives screen changes, so a captured reply is always written to the database. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repo = Repository(AppDatabase.create(this))
        settings = Settings(this)
        catalog = AiCatalog(repo, settings, appScope)
        ai = AiGateway(this, repo, runner, catalog)
        bible = BibleService(repo, ai, catalog, appScope)
        roundtable = RoundtableService(repo, ai, catalog, appScope)
        backup = BackupManager(this, repo, appScope)
        // a new chapter updates the story bible right away; edits wait until you pause for 3 minutes
        repo.onChapterChanged = { bookId, _, added -> if (added) bible.updatePending(bookId) else bible.schedule(bookId, 3 * 60_000L) }
        appScope.launch {
            repo.drafts.recoverInterrupted()
            repo.images.failInterrupted()
            repo.roundtables.failInterrupted()
            repo.roundtables.failInterruptedMessages()
        }
        backup.start()
    }
}
