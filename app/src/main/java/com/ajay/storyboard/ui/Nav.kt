package com.ajay.storyboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ajay.storyboard.ai.AiHost
import com.ajay.storyboard.ui.bible.BibleScreen
import com.ajay.storyboard.ui.history.AiHistoryScreen
import com.ajay.storyboard.ui.history.AiRunScreen
import com.ajay.storyboard.ui.refactor.RefactorScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.data.AiService
import com.ajay.storyboard.ui.editor.ChapterEditorScreen
import com.ajay.storyboard.ui.library.LibraryScreen
import com.ajay.storyboard.ui.manage.ManageChaptersScreen
import com.ajay.storyboard.ui.manage.VersionsScreen
import com.ajay.storyboard.ui.reader.ReaderScreen
import com.ajay.storyboard.ui.settings.LoginScreen
import com.ajay.storyboard.ui.settings.SettingsScreen
import com.ajay.storyboard.ui.write.DraftsScreen
import com.ajay.storyboard.ui.write.WriteScreen

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val GALLERY = "gallery"
    const val ROUNDTABLES = "roundtables"
    const val BACKUP = "backup"
    fun newRoundtable(bookId: Long? = null) = "roundtable-new?bookId=${bookId ?: -1L}"
    fun roundtable(id: Long) = "roundtable/$id"
    fun apiKey(id: Long) = "apikey/$id"
    fun teach(siteId: Long) = "teach/$siteId"
    fun generate(prompt: String? = null, bookId: Long? = null) =
        "generate?prompt=${android.net.Uri.encode(prompt.orEmpty())}&bookId=${bookId ?: -1L}"
    fun reader(bookId: Long, chapterId: Long = -1L) = "reader/$bookId?chapterId=$chapterId"
    fun editor(bookId: Long, chapterId: Long = -1L) = "editor/$bookId?chapterId=$chapterId"
    fun manage(bookId: Long) = "manage/$bookId"
    fun versions(chapterId: Long) = "versions/$chapterId"
    fun write(bookId: Long, newSession: Boolean = false) = "write/$bookId?new=$newSession"
    fun drafts(bookId: Long) = "drafts/$bookId"
    fun login(service: AiService) = "login/${service.name}"
    fun bible(bookId: Long) = "bible/$bookId"
    fun refactorDraft(draftId: Long) = "refactor?draftId=$draftId"
    fun refactorChapter(chapterId: Long) = "refactor?chapterId=$chapterId"
    fun history(bookId: Long, chapterId: Long = -1L) = "history/$bookId?chapterId=$chapterId"
    fun run(runId: Long) = "run/$runId"
}

@Composable
fun StoryBoardNav(app: StoryBoardApp) {
    val nav = rememberNavController()
    val repo = app.repo
    val back: () -> Unit = { nav.popBackStack() }

    Box(Modifier.fillMaxSize()) {
    AiHost(app.runner)
    NavHost(
        navController = nav,
        startDestination = Routes.LIBRARY,
        // light motion: screens slide in a little and fade
        enterTransition = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 10 }
        },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) },
        popExitTransition = {
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220)) { it / 10 }
        },
    ) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                repo = repo,
                settings = app.settings,
                onOpenBook = { nav.navigate(Routes.reader(it)) },
                onNavigate = { d ->
                    when (d) {
                        com.ajay.storyboard.ui.common.DrawerDest.ACCOUNTS -> nav.navigate(Routes.ACCOUNTS)
                        com.ajay.storyboard.ui.common.DrawerDest.SETTINGS -> nav.navigate(Routes.SETTINGS)
                        com.ajay.storyboard.ui.common.DrawerDest.GALLERY -> nav.navigate(Routes.GALLERY)
                        com.ajay.storyboard.ui.common.DrawerDest.ROUNDTABLE -> nav.navigate(Routes.ROUNDTABLES)
                        com.ajay.storyboard.ui.common.DrawerDest.BACKUP -> nav.navigate(Routes.BACKUP)
                        else -> Unit
                    }
                },
            )
        }
        composable(
            "reader/{bookId}?chapterId={chapterId}",
            listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            val bookId = it.arguments!!.getLong("bookId")
            ReaderScreen(
                app = app,
                bookId = bookId,
                onBack = back,
                onContinue = { newSession -> nav.navigate(Routes.write(bookId, newSession)) },
                onNewChapter = { nav.navigate(Routes.editor(bookId)) },
                onManage = { nav.navigate(Routes.manage(bookId)) },
                onDrafts = { nav.navigate(Routes.drafts(bookId)) },
                onBible = { nav.navigate(Routes.bible(bookId)) },
                onHistory = { nav.navigate(Routes.history(bookId)) },
                bibleStatus = app.bible.working[bookId],
                jumpToChapter = it.arguments!!.getLong("chapterId").takeIf { id -> id > 0 },
                onGeneratePicture = { b -> nav.navigate(Routes.generate(null, b)) },
                onRoundtable = { nav.navigate(Routes.newRoundtable(bookId)) },
                onOpenBook = { id -> nav.navigate(Routes.reader(id)) },
            )
        }
        composable(
            "editor/{bookId}?chapterId={chapterId}",
            listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            ChapterEditorScreen(
                repo = repo,
                bookId = it.arguments!!.getLong("bookId"),
                chapterId = it.arguments!!.getLong("chapterId").takeIf { id -> id > 0 },
                onDone = back,
            )
        }
        composable(
            "history/{bookId}?chapterId={chapterId}",
            listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            AiHistoryScreen(
                repo = repo,
                bookId = it.arguments!!.getLong("bookId"),
                chapterId = it.arguments!!.getLong("chapterId").takeIf { id -> id > 0 },
                onBack = back,
                onOpen = { runId -> nav.navigate(Routes.run(runId)) },
            )
        }
        composable("run/{runId}", listOf(navArgument("runId") { type = NavType.LongType })) {
            AiRunScreen(repo = repo, runId = it.arguments!!.getLong("runId"), onBack = back)
        }
        composable("manage/{bookId}", listOf(navArgument("bookId") { type = NavType.LongType })) {
            val bookId = it.arguments!!.getLong("bookId")
            ManageChaptersScreen(
                repo = repo,
                bookId = bookId,
                onBack = back,
                onRead = { chapterId ->
                    nav.navigate(Routes.reader(bookId, chapterId)) { popUpTo("reader/{bookId}?chapterId={chapterId}") { inclusive = true } }
                },
                onEdit = { chapterId -> nav.navigate(Routes.editor(bookId, chapterId)) },
                onNew = { nav.navigate(Routes.editor(bookId)) },
                onVersions = { chapterId -> nav.navigate(Routes.versions(chapterId)) },
                onRefactor = { chapterId -> nav.navigate(Routes.refactorChapter(chapterId)) },
                onHistory = { chapterId -> nav.navigate(Routes.history(bookId, chapterId)) },
                onForked = { id -> nav.navigate(Routes.reader(id)) },
            )
        }
        composable("versions/{chapterId}", listOf(navArgument("chapterId") { type = NavType.LongType })) {
            VersionsScreen(repo = repo, chapterId = it.arguments!!.getLong("chapterId"), onBack = back)
        }
        composable(
            "write/{bookId}?new={new}",
            listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("new") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            val bookId = it.arguments!!.getLong("bookId")
            val reopen = { nav.popBackStack(); nav.navigate(Routes.write(bookId)) }
            val onRefactor = { draftId: Long -> nav.navigate(Routes.refactorDraft(draftId)) }
            if (app.catalog.targetFor(com.ajay.storyboard.data.AiAction.WRITE) is com.ajay.storyboard.ai.AiTarget.Api) {
                com.ajay.storyboard.ui.write.ApiChatScreen(
                    app = app, bookId = bookId, newSession = it.arguments!!.getBoolean("new"),
                    onBack = back, onRefactor = onRefactor, onReopen = { reopen() },
                )
            } else {
                WriteScreen(
                    app = app, bookId = bookId, newSession = it.arguments!!.getBoolean("new"),
                    onBack = back, onRefactor = onRefactor, onReopen = { reopen() },
                )
            }
        }
        composable("drafts/{bookId}", listOf(navArgument("bookId") { type = NavType.LongType })) {
            DraftsScreen(
                repo = repo, bookId = it.arguments!!.getLong("bookId"), onBack = back,
                onRefactor = { draftId -> nav.navigate(Routes.refactorDraft(draftId)) },
            )
        }
        composable("bible/{bookId}", listOf(navArgument("bookId") { type = NavType.LongType })) {
            BibleScreen(app = app, bookId = it.arguments!!.getLong("bookId"), onBack = back)
        }
        composable(
            "refactor?draftId={draftId}&chapterId={chapterId}",
            listOf(
                navArgument("draftId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("chapterId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            RefactorScreen(
                app = app,
                draftId = it.arguments!!.getLong("draftId").takeIf { id -> id > 0 },
                chapterId = it.arguments!!.getLong("chapterId").takeIf { id -> id > 0 },
                onDone = back,
                onHistory = { b, c -> nav.navigate(Routes.history(b, c ?: -1L)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = app.settings,
                catalog = app.catalog,
                onBackup = { nav.navigate(Routes.BACKUP) },
                onBack = back,
                onAccounts = { nav.navigate(Routes.ACCOUNTS) },
            )
        }
        composable(Routes.ACCOUNTS) {
            com.ajay.storyboard.ui.settings.AccountsScreen(
                app = app,
                onBack = back,
                onOpen = { service -> nav.navigate(Routes.login(service)) },
                onEditKey = { id -> nav.navigate(Routes.apiKey(id)) },
                onTeach = { id -> nav.navigate(Routes.teach(id)) },
            )
        }
        composable("apikey/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            com.ajay.storyboard.ui.settings.ApiKeyScreen(app = app, profileId = it.arguments!!.getLong("id").takeIf { id -> id > 0 }, onBack = back)
        }
        composable("teach/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            com.ajay.storyboard.ui.settings.TeachSiteScreen(app = app, siteId = it.arguments!!.getLong("id"), onBack = back)
        }
        composable(Routes.GALLERY) {
            com.ajay.storyboard.ui.gallery.GalleryScreen(
                app = app, onBack = back,
                onGenerate = { prompt, bookId -> nav.navigate(Routes.generate(prompt, bookId)) },
            )
        }
        composable(
            "generate?prompt={prompt}&bookId={bookId}",
            listOf(
                navArgument("prompt") { type = NavType.StringType; defaultValue = "" },
                navArgument("bookId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            com.ajay.storyboard.ui.gallery.GenerateImageScreen(
                app = app,
                initialPrompt = it.arguments!!.getString("prompt")?.takeIf { p -> p.isNotBlank() },
                initialBookId = it.arguments!!.getLong("bookId").takeIf { id -> id > 0 },
                onBack = back,
                onGallery = { if (!nav.popBackStack(Routes.GALLERY, false)) nav.navigate(Routes.GALLERY) },
            )
        }
        composable(Routes.ROUNDTABLES) {
            com.ajay.storyboard.ui.roundtable.RoundtableListScreen(
                app = app, onBack = back,
                onOpen = { id -> nav.navigate(Routes.roundtable(id)) },
                onNew = { nav.navigate(Routes.newRoundtable()) },
            )
        }
        composable("roundtable-new?bookId={bookId}", listOf(navArgument("bookId") { type = NavType.LongType; defaultValue = -1L })) {
            com.ajay.storyboard.ui.roundtable.NewRoundtableScreen(
                app = app, bookId = it.arguments!!.getLong("bookId").takeIf { id -> id > 0 }, onBack = back,
                onStarted = { id -> nav.popBackStack(); nav.navigate(Routes.roundtable(id)) },
            )
        }
        composable("roundtable/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            com.ajay.storyboard.ui.roundtable.RoundtableScreen(
                app = app, id = it.arguments!!.getLong("id"), onBack = back,
                onSendToWrite = { b -> nav.navigate(Routes.write(b)) },
            )
        }
        composable(Routes.BACKUP) {
            com.ajay.storyboard.ui.settings.BackupScreen(app = app, onBack = back)
        }
        composable("login/{service}") {
            val service = AiService.valueOf(it.arguments!!.getString("service")!!)
            LoginScreen(service = service, onBack = back)
        }
    }
    }
}
