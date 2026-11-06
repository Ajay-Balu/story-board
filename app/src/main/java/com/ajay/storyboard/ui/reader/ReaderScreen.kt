package com.ajay.storyboard.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AssistChip
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.ajay.storyboard.ui.common.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.Chapter
import com.ajay.storyboard.data.ChapterSource
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.common.ConfirmDialog
import com.ajay.storyboard.ui.common.RenameDialog
import com.ajay.storyboard.ui.theme.ReaderBody
import com.ajay.storyboard.ui.theme.ReaderHeading
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** One row in the continuous-scroll reader. */
private sealed interface ReaderRow {
    val key: String
    val chapterId: Long
    val paragraph: Int
    data class Header(val chapter: Chapter, val number: Int) : ReaderRow {
        override val key get() = "h${chapter.id}"
        override val chapterId get() = chapter.id
        override val paragraph get() = -1
    }
    data class Body(override val chapterId: Long, override val paragraph: Int, val block: Block) : ReaderRow {
        override val key get() = "p$chapterId-$paragraph"
    }
    data class End(override val chapterId: Long) : ReaderRow {
        override val key get() = "e$chapterId"
        override val paragraph get() = Int.MAX_VALUE
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(
    app: com.ajay.storyboard.StoryBoardApp,
    bookId: Long,
    onBack: () -> Unit,
    onContinue: (newSession: Boolean) -> Unit,
    onNewChapter: () -> Unit,
    onManage: () -> Unit,
    onDrafts: () -> Unit,
    onBible: () -> Unit,
    onHistory: () -> Unit,
    bibleStatus: String?,
    jumpToChapter: Long? = null,
    onGeneratePicture: (bookId: Long) -> Unit = {},
    onRoundtable: () -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
) {
    val repo: Repository = app.repo
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<EditSelection?>(null) }
    val editState = rememberLazyListState()
    var forking by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val kind = exporting; exporting = null
        if (uri == null || kind == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        when (kind) {
                            "epub" -> com.ajay.storyboard.backup.StoryExport.epub(context, repo, bookId, out)
                            "pdf" -> com.ajay.storyboard.backup.StoryExport.pdf(context, repo, bookId, out)
                            else -> com.ajay.storyboard.backup.StoryShare.write(context, repo, bookId, out)
                        }
                    }
                }
                when (kind) { "epub" -> "EPUB saved"; "pdf" -> "PDF saved"; else -> "Story file saved" }
            }.getOrElse { "Export failed: ${it.message}" }
            snackbar.showSnackbar(msg)
        }
    }
    val book by remember(bookId) { repo.books.observe(bookId) }.collectAsState(initial = null)
    val chapters by remember(bookId) { repo.chapters.observeForBook(bookId) }.collectAsState(initial = null)
    val drafts by remember(bookId) { repo.drafts.observePending(bookId) }.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var coverPicking by remember { mutableStateOf(false) }
    var penMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val bannerOffsetState = remember { mutableStateOf(0) }

    val rows = remember(chapters) {
        buildList {
            chapters.orEmpty().forEachIndexed { idx, ch ->
                add(ReaderRow.Header(ch, idx + 1))
                MarkdownLite.blocks(com.ajay.storyboard.ui.common.dropRepeatedTitle(ch.content, ch.title))
                    .forEachIndexed { p, b -> add(ReaderRow.Body(ch.id, p, b)) }
                add(ReaderRow.End(ch.id))
            }
        }
    }
    val currentChapterId by remember(rows) {
        androidx.compose.runtime.derivedStateOf {
            rows.getOrNull(listState.firstVisibleItemIndex - bannerOffsetState.value)?.chapterId
        }
    }
    // Pending-drafts banner occupies index 0 when shown, so row indexes are shifted by this.
    val bannerOffset = (if (drafts.isNotEmpty()) 1 else 0) + (if (bibleStatus != null) 1 else 0)
    bannerOffsetState.value = bannerOffset

    // Restore the last reading position once.
    LaunchedEffect(rows, book) {
        val b = book ?: return@LaunchedEffect
        if (restored || chapters == null) return@LaunchedEffect
        restored = true
        if (jumpToChapter != null) {
            val t = rows.indexOfFirst { it is ReaderRow.Header && it.chapter.id == jumpToChapter }
            if (t >= 0) { listState.scrollToItem(t + bannerOffset); return@LaunchedEffect }
        }
        val target = rows.indexOfFirst { it.chapterId == b.lastChapterId && it.paragraph >= b.lastParagraph }
            .takeIf { it >= 0 } ?: rows.indexOfFirst { it.chapterId == b.lastChapterId }
        if (target >= 0) listState.scrollToItem(target + bannerOffset, b.lastOffset)
    }

    // Save the position while reading.
    LaunchedEffect(rows, bannerOffset) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(500)
            .collect { (index, offset) ->
                if (!restored) return@collect
                val row = rows.getOrNull(index - bannerOffset) ?: return@collect
                repo.books.savePosition(bookId, row.chapterId, row.paragraph.coerceIn(-1, 100_000), offset)
            }
    }

    // Edit mode: rows are the chapters' raw paragraphs (pictures included).
    val editRows = remember(chapters) {
        buildList {
            chapters.orEmpty().forEachIndexed { idx, ch ->
                add(Triple(ch, idx + 1, -1))
                com.ajay.storyboard.data.ChapterBlocks.lines(ch.content).forEachIndexed { i, _ -> add(Triple(ch, idx + 1, i)) }
                add(Triple(ch, idx + 1, Int.MAX_VALUE))
            }
        }
    }
    fun enterEdit() {
        val target = currentChapterId
        editing = true
        selection = null
        scope.launch {
            kotlinx.coroutines.delay(50)
            val i = editRows.indexOfFirst { it.first.id == target && it.third == -1 }
            if (i >= 0) editState.scrollToItem(i)
        }
    }
    androidx.activity.compose.BackHandler(enabled = editing) { if (selection != null) selection = null else editing = false }

    Scaffold(
        // the story reads on the pale printed sheet
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        bottomBar = {
            val sel = selection
            val ch = sel?.let { s -> chapters?.firstOrNull { it.id == s.chapterId } }
            androidx.compose.animation.AnimatedVisibility(
                visible = editing && sel != null && ch != null,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            ) {
                if (sel != null && ch != null) EditActionBar(
                    app = app, chapter = ch, selection = sel,
                    onSelection = { selection = it },
                    onGeneratePicture = { scene -> PendingScene.scene = scene; onGeneratePicture(bookId) },
                    onMessage = { m -> scope.launch { snackbar.showSnackbar(m) } },
                )
            }
        },
        topBar = {
            if (editing) TopAppBar(
                title = {
                    Column {
                        Text("Editing", maxLines = 1)
                        Text("Tap a paragraph. Press and hold to select several.", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { editing = false; selection = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Done") } },
                actions = { TextButton(onClick = { editing = false; selection = null }) { Text("Done") } },
            ) else TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                title = {
                    Column {
                        Text(book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val total = chapters?.size ?: 0
                        val current = chapters?.indexOfFirst { it.id == currentChapterId }?.takeIf { it >= 0 }?.plus(1)
                        if (total > 0) Text(
                            (if (current != null) "Chapter $current of $total" else "$total chapter${if (total == 1) "" else "s"}") +
                                (book?.forkOfTitle?.let { ", forked from \"$it\" at chapter ${book?.forkChapter}" } ?: ""),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!chapters.isNullOrEmpty()) IconButton(onClick = { enterEdit() }) { Icon(Icons.Filled.EditNote, "Edit mode") }
                    IconButton(onClick = onManage) { Icon(Icons.Outlined.ViewList, "Chapters") }
                    IconButton(onClick = { menu = true }) {
                        BadgedBox(badge = { if (drafts.isNotEmpty()) Badge { Text("${drafts.size}") } }) { Icon(Icons.Filled.MoreVert, "More") }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Write a chapter myself") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onNewChapter() })
                        DropdownMenuItem(text = { Text("Continue in a new chat") }, leadingIcon = { Icon(Icons.Outlined.AddComment, null) },
                            onClick = { menu = false; onContinue(true) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Story bible") }, leadingIcon = { Icon(Icons.Outlined.AutoStories, null) },
                            onClick = { menu = false; onBible() })
                        DropdownMenuItem(text = { Text("Jump to chapter") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                            enabled = !chapters.isNullOrEmpty(), onClick = { menu = false; showToc = true })
                        DropdownMenuItem(text = { Text("Captured replies (${drafts.size})") }, leadingIcon = { Icon(Icons.Outlined.Inbox, null) },
                            onClick = { menu = false; onDrafts() })
                        DropdownMenuItem(text = { Text("AI history") }, leadingIcon = { Icon(Icons.Outlined.Psychology, null) },
                            onClick = { menu = false; onHistory() })
                        DropdownMenuItem(text = { Text("AI Roundtable: what next?") }, leadingIcon = { Icon(Icons.Outlined.Forum, null) },
                            onClick = { menu = false; onRoundtable() })
                        DropdownMenuItem(text = { Text("Edit mode") }, leadingIcon = { Icon(Icons.Filled.EditNote, null) },
                            enabled = !chapters.isNullOrEmpty(), onClick = { menu = false; enterEdit() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Share story file") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            enabled = !chapters.isNullOrEmpty(), onClick = {
                                menu = false
                                scope.launch {
                                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching { com.ajay.storyboard.backup.StoryShare.shareFile(context, repo, bookId) }.getOrNull()
                                    }
                                    if (f == null) snackbar.showSnackbar("Could not create the story file")
                                    else com.ajay.storyboard.backup.StoryShare.send(context, f)
                                }
                            })
                        DropdownMenuItem(text = { Text("Export EPUB") }, leadingIcon = { Icon(Icons.Outlined.MenuBook, null) },
                            enabled = !chapters.isNullOrEmpty(), onClick = {
                                menu = false; exporting = "epub"; exportLauncher.launch(com.ajay.storyboard.backup.StoryExport.fileName(book?.title, "epub"))
                            })
                        DropdownMenuItem(text = { Text("Export PDF") }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                            enabled = !chapters.isNullOrEmpty(), onClick = {
                                menu = false; exporting = "pdf"; exportLauncher.launch(com.ajay.storyboard.backup.StoryExport.fileName(book?.title, "pdf"))
                            })
                        DropdownMenuItem(text = { Text("Fork from this chapter") }, leadingIcon = { Icon(Icons.Outlined.CallSplit, null) },
                            enabled = !chapters.isNullOrEmpty(), onClick = { menu = false; forking = true })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Cover picture") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                            onClick = { menu = false; coverPicking = true })
                        DropdownMenuItem(text = { Text("Rename book") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                            onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Delete book", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        floatingActionButton = {
            // Small pen button so it doesn't cover the text; tap for the two ways to write the next chapter.
            if (!chapters.isNullOrEmpty() && !editing) Box {
                com.ajay.storyboard.ui.common.SmallFloatingActionButton(onClick = { penMenu = true }) {
                    Icon(Icons.Outlined.Edit, "Write the next chapter")
                }
                DropdownMenu(expanded = penMenu, onDismissRequest = { penMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Continue with AI") }, leadingIcon = { Icon(Icons.Filled.EditNote, null) },
                        onClick = { penMenu = false; onContinue(false) },
                    )
                    DropdownMenuItem(
                        text = { Text("Write it yourself") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { penMenu = false; onNewChapter() },
                    )
                }
            }
        },
    ) { pad ->
        val list = chapters
        if (list == null) return@Scaffold
        if (list.isEmpty()) {
            EmptyBook(Modifier.padding(pad), onNewChapter, { onContinue(false) }, drafts.size, onDrafts)
            return@Scaffold
        }
        if (editing) {
            LazyColumn(
                state = editState,
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 160.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(editRows, key = { "${it.first.id}-${it.third}" }) { (ch, number, i) ->
                    val mod = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    when (i) {
                        -1 -> Text(if (ch.title.startsWith("chapter", ignoreCase = true)) ch.title else "Chapter $number: ${ch.title}", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface, modifier = mod.padding(top = 20.dp, bottom = 8.dp))
                        Int.MAX_VALUE -> TextButton(modifier = mod, onClick = {
                            val n = com.ajay.storyboard.data.ChapterBlocks.lines(ch.content).size
                            selection = EditSelection(ch.id, n - 1, n - 1, n - 1, range = false)
                        }) { Text("Select the last paragraph to add after it") }
                        else -> {
                            val line = com.ajay.storyboard.data.ChapterBlocks.lines(ch.content).getOrNull(i).orEmpty()
                            val sel = selection
                            EditableParagraph(
                                app, line,
                                selected = sel != null && sel.chapterId == ch.id && i in sel.from..sel.to,
                                modifier = mod,
                                onTap = { selection = selection.tap(ch.id, i) },
                                onLongPress = { selection = longPress(ch.id, i) },
                            )
                        }
                    }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bibleStatus != null) {
                item(key = "bible-status") {
                    Text(
                        "$bibleStatus…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onBible).padding(bottom = 8.dp),
                    )
                }
            }
            if (drafts.isNotEmpty()) {
                item(key = "banner") {
                    Card(
                        onClick = onDrafts,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Text(
                            "${drafts.size} captured repl${if (drafts.size == 1) "y is" else "ies are"} waiting for review",
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            items(rows, key = { it.key }) { row ->
                val mod = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                when (row) {
                    is ReaderRow.Header -> ChapterHeader(row, mod)
                    is ReaderRow.Body -> when (val b = row.block) {
                        is Block.Heading -> Text(b.text, style = ReaderHeading.copy(fontSize = ReaderBody.fontSize * 1.15f), modifier = mod.padding(vertical = 10.dp))
                        is Block.Paragraph -> Text(b.text, style = ReaderBody, color = MaterialTheme.colorScheme.onBackground, modifier = mod.padding(bottom = 14.dp))
                        Block.Divider -> HorizontalDivider(mod.padding(vertical = 16.dp))
                        is Block.Picture -> StoryPicture(repo, b.imageId, b.caption, b.widthPct, mod)
                    }
                    is ReaderRow.End -> Spacer(Modifier.height(40.dp))
                }
            }
            item(key = "end") {
                Column(Modifier.padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("End of written chapters", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onNewChapter) { Text("Write the next chapter myself") }
                }
            }
        }
    }

    if (coverPicking) com.ajay.storyboard.ui.library.CoverPickerDialog(repo, bookId) { coverPicking = false }
    if (renaming) {
        RenameDialog("Rename book", "Book title", book?.title.orEmpty(),
            onSave = { t -> renaming = false; scope.launch { repo.renameBook(bookId, t) } },
            onDismiss = { renaming = false })
    }
    if (deleting) {
        ConfirmDialog(
            title = "Delete \"${book?.title.orEmpty()}\"?",
            text = "This removes the book with all ${chapters?.size ?: 0} chapters, their versions, the story bible, AI history and captured replies.",
            confirmLabel = "Delete book",
            requireTick = true,
            onConfirm = { deleting = false; scope.launch { repo.deleteBook(bookId); onBack() } },
            onDismiss = { deleting = false },
        )
    }

    if (forking) {
        val list = chapters.orEmpty()
        val start = list.indexOfFirst { it.id == currentChapterId }.takeIf { it >= 0 }?.plus(1) ?: list.size
        var upTo by remember { mutableStateOf(start.toFloat()) }
        var title by remember { mutableStateOf("${book?.title.orEmpty()} (fork)") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forking = false },
            title = { Text("Fork this story") },
            text = {
                Column {
                    Text("Makes a separate book with chapters 1–${upTo.toInt()}, the story bible and the pictures. This book is not changed.",
                        style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("New book title") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Keep chapters 1 to ${upTo.toInt()}: ${list.getOrNull(upTo.toInt() - 1)?.title.orEmpty()}",
                        modifier = Modifier.padding(top = 12.dp))
                    if (list.size > 1) androidx.compose.material3.Slider(upTo, { upTo = it }, valueRange = 1f..list.size.toFloat(),
                        steps = (list.size - 2).coerceAtLeast(0))
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    forking = false
                    scope.launch { onOpenBook(repo.forkBook(bookId, upTo.toInt(), title)) }
                }) { Text("Fork") }
            },
            dismissButton = { TextButton(onClick = { forking = false }) { Text("Cancel") } },
        )
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(chapters.orEmpty().withIndex().toList(), key = { it.value.id }) { (idx, ch) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val target = rows.indexOfFirst { it is ReaderRow.Header && it.chapter.id == ch.id }
                                showToc = false
                                if (target >= 0) scope.launch { listState.scrollToItem(target + bannerOffset) }
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${idx + 1}", modifier = Modifier.widthIn(min = 32.dp), color = MaterialTheme.colorScheme.onSurface)
                        Text(ch.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (book?.lastChapterId == ch.id) Text("reading", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterHeader(row: ReaderRow.Header, modifier: Modifier) {
    Column(modifier.padding(top = 24.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Chapter ${row.number}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val shown = row.chapter.title.replace(Regex("^(chapter|ch\\.?)\\s*\\d+\\s*[:.\\-–—]\\s*", RegexOption.IGNORE_CASE), "")
        Text(shown.ifBlank { row.chapter.title }, style = ReaderHeading, color = MaterialTheme.colorScheme.onBackground,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (row.chapter.source == ChapterSource.AI_RECOVERED) {
            Spacer(Modifier.height(8.dp))
            AssistChip(onClick = {}, label = { Text("Recovered: erased by the AI") })
        }
    }
}

@Composable
private fun EmptyBook(modifier: Modifier, onWrite: () -> Unit, onContinue: () -> Unit, drafts: Int, onDrafts: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This book has no chapters yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Write with AI") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onWrite, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Edit, null)
            Text("  Write it myself")
        }
        if (drafts > 0) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDrafts) { Text("Review $drafts captured repl${if (drafts == 1) "y" else "ies"}") }
        }
    }
}
