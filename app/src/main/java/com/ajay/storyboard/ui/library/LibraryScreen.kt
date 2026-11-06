@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.ajay.storyboard.ui.library

import com.ajay.storyboard.ui.common.MetaText

import com.ajay.storyboard.ui.common.Sheet

import androidx.compose.material.icons.outlined.Image

import androidx.compose.material3.HorizontalDivider

import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.clickable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.ajay.storyboard.ui.common.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.BookWithStats
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.data.Settings
import com.ajay.storyboard.ui.common.ConfirmDialog
import com.ajay.storyboard.ui.common.pressable
import com.ajay.storyboard.ui.common.relativeTime
import kotlinx.coroutines.launch

private enum class LibraryView(val label: String, val icon: ImageVector) {
    SHELF("Shelf", Icons.Outlined.ViewWeek),
    GRID("Grid", Icons.Outlined.GridView),
    LIST("List", Icons.Outlined.ViewAgenda),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(repo: Repository, settings: Settings, onOpenBook: (Long) -> Unit, onNavigate: (com.ajay.storyboard.ui.common.DrawerDest) -> Unit) {
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val books by remember { repo.books.observeLibrary() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<BookDialog?>(null) }
    var confirmDelete by remember { mutableStateOf<BookWithStats?>(null) }
    var coverFor by remember { mutableStateOf<Long?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(BookSort.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    val view = runCatching { LibraryView.valueOf(settings.libraryView) }.getOrDefault(LibraryView.SHELF)
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val context = androidx.compose.ui.platform.LocalContext.current
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { com.ajay.storyboard.backup.StoryShare.import(context, repo, it) }
                }
            }
            id.onSuccess { onOpenBook(it) }
                .onFailure { android.widget.Toast.makeText(context, "Import failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val actions = BookActions(
        open = { onOpenBook(it.id) },
        rename = { dialog = BookDialog(it.id, it.title) },
        delete = { confirmDelete = it },
        cover = { coverFor = it.id },
    )

    com.ajay.storyboard.ui.common.AppDrawer(drawer, com.ajay.storyboard.ui.common.DrawerDest.LIBRARY, onNavigate) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") } },
                title = {
                    if (searching) OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search books") }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    ) else Text("Library", style = MaterialTheme.typography.headlineSmall)
                },
                actions = {
                    IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, "Import a story file") }
                    if ((books?.size ?: 0) > 1 || searching) IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search")
                    }
                    if ((books?.size ?: 0) > 1) Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            BookSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, null) },
                                    onClick = { sort = s; sortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = BookDialog(null, "") },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New book") },
            )
        },
    ) { pad ->
        val all = books
        val list = all?.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }?.let { l ->
            when (sort) {
                BookSort.RECENT -> l
                BookSort.TITLE -> l.sortedBy { it.title.lowercase() }
                BookSort.CHAPTERS -> l.sortedByDescending { it.chapterCount }
            }
        }
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!all.isNullOrEmpty()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LibraryView.entries.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = v == view,
                            onClick = { settings.updateLibraryView(v.name) },
                            shape = SegmentedButtonDefaults.itemShape(i, LibraryView.entries.size, baseShape = com.ajay.storyboard.ui.common.ControlShape),
                            icon = { Icon(v.icon, null, Modifier.size(18.dp)) },
                            label = { Text(v.label) },
                        )
                    }
                }
            }
            when {
                list == null -> Unit
                all.isNullOrEmpty() -> EmptyLibrary(Modifier, onNew = { dialog = BookDialog(null, "") })
                list.isEmpty() -> Text("No books match \"$query\".", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> AnimatedContent(
                    targetState = view,
                    transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220))) togetherWith fadeOut(tween(120)) },
                    label = "library-view",
                ) { v ->
                    val hero = if (query.isBlank()) all.firstOrNull { it.readingChapter != null && it.chapterCount > 0 } else null
                    when (v) {
                        LibraryView.SHELF -> ShelfView(repo, list, hero, actions)
                        LibraryView.GRID -> GridView(repo, list, hero, actions)
                        LibraryView.LIST -> ListView(repo, list, hero, actions)
                    }
                }
            }
        }
    }
    }

    dialog?.let { d ->
        var title by remember(d) { mutableStateOf(d.title) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (d.bookId == null) "New book" else "Rename book") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        scope.launch { if (d.bookId == null) onOpenBook(repo.createBook(title)) else repo.renameBook(d.bookId, title) }
                        dialog = null
                    },
                ) { Text(if (d.bookId == null) "Create" else "Save") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }

    coverFor?.let { id -> CoverPickerDialog(repo, id) { coverFor = null } }

    confirmDelete?.let { b ->
        ConfirmDialog(
            title = "Delete \"${b.title}\"?",
            text = "This removes the book with all ${b.chapterCount} chapters, their versions, the story bible, AI history and captured replies.",
            confirmLabel = "Delete book",
            requireTick = true,
            onConfirm = { scope.launch { repo.deleteBook(b.id) }; confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

private class BookActions(val open: (BookWithStats) -> Unit, val rename: (BookWithStats) -> Unit, val delete: (BookWithStats) -> Unit, val cover: (BookWithStats) -> Unit)

private data class BookDialog(val bookId: Long?, val title: String)

private enum class BookSort(val label: String) { RECENT("Recently updated"), TITLE("Title A–Z"), CHAPTERS("Most chapters") }

private fun progressText(book: BookWithStats) = when {
    book.chapterCount == 0 -> "No chapters yet"
    book.readingChapter != null -> "Chapter ${book.readingChapter} of ${book.chapterCount}"
    else -> "${book.chapterCount} chapter${if (book.chapterCount == 1) "" else "s"}, not started"
}

private fun progress(book: BookWithStats) =
    if (book.chapterCount == 0 || book.readingChapter == null) 0f else book.readingChapter.toFloat() / book.chapterCount

@Composable
private fun BookMenu(book: BookWithStats, expanded: Boolean, onDismiss: () -> Unit, actions: BookActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Cover picture") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
            onClick = { onDismiss(); actions.cover(book) })
        DropdownMenuItem(text = { Text("Rename book") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
            onClick = { onDismiss(); actions.rename(book) })
        DropdownMenuItem(text = { Text("Delete book", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); actions.delete(book) })
    }
}

/** The book you were reading most recently, set on the printed sheet with its cover, progress and the one action. */
@Composable
private fun ContinueCard(repo: Repository, book: BookWithStats, actions: BookActions, modifier: Modifier = Modifier) {
    Sheet(modifier.clickable { actions.open(book) }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(repo, book, Modifier.width(72.dp).aspectRatio(2f / 3f), titleSize = 9)
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaText(progressText(book))
                ReadingRule(progress(book))
                com.ajay.storyboard.ui.common.Button(onClick = { actions.open(book) }, modifier = Modifier.padding(top = 4.dp)) { Text("Continue reading") }
            }
        }
    }
}

/** Reading progress as a ruled line: ink for what is read, a hairline for the rest. */
@Composable
private fun ReadingRule(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.dp).background(MaterialTheme.colorScheme.onSurface))
    }
}

// ------------------------------------------------------------------ shelf

@Composable
private fun ShelfView(repo: Repository, books: List<BookWithStats>, hero: BookWithStats?, actions: BookActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        hero?.let { ContinueCard(repo, it, actions, Modifier.padding(16.dp, 8.dp, 16.dp, 12.dp)) }
        // the back wall of the bookcase
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ShelfRows(books, gap = 6.dp) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    row.forEach { b ->
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (b.pendingDrafts > 0) Badge { Text("${b.pendingDrafts}") }
                                BookSpine(b, Modifier.pressable(onClick = { actions.open(b) }, onLongClick = { menu = true }, pressedScale = 1f, lift = 10f))
                            }
                            BookMenu(b, menu, { menu = false }, actions)
                        }
                    }
                }
                ShelfBoard()
            }
            // a bookcase has room for more: empty shelves under the books
            repeat(1) {
                Spacer(Modifier.height(150.dp))
                ShelfBoard()
            }
            Text(
                "Tap a book to read it. Press and hold for options.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ grid

@Composable
private fun GridView(repo: Repository, books: List<BookWithStats>, hero: BookWithStats?, actions: BookActions) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        hero?.let { h -> item(key = "hero", span = { GridItemSpan(maxLineSpan) }) { ContinueCard(repo, h, actions) } }
        items(books, key = { it.id }) { b ->
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.animateItem()) {
                Column(Modifier.combinedClickable(onClick = { actions.open(b) }, onLongClick = { menu = true })) {
                    Box {
                        BookCover(repo, b, Modifier.fillMaxWidth().aspectRatio(2f / 3f), titleSize = 12)
                        if (b.pendingDrafts > 0) Badge(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Text("${b.pendingDrafts}") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    MetaText(progressText(b), maxLines = 1)
                    if (b.readingChapter != null) ReadingRule(progress(b), Modifier.padding(top = 6.dp))
                }
                BookMenu(b, menu, { menu = false }, actions)
            }
        }
    }
}

// ------------------------------------------------------------------ list

@Composable
private fun ListView(repo: Repository, books: List<BookWithStats>, hero: BookWithStats?, actions: BookActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
    ) {
        hero?.let { h -> item(key = "hero") { ContinueCard(repo, h, actions, Modifier.padding(bottom = 12.dp)) } }
        items(books, key = { it.id }) { b ->
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.animateItem()) {
                Column {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { actions.open(b) }, onLongClick = { menu = true }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BookCover(repo, b, Modifier.width(52.dp).aspectRatio(2f / 3f), titleSize = 7)
                        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            MetaText(progressText(b))
                            MetaText(
                                listOfNotNull(
                                    "Updated ${relativeTime(b.updatedAt)}",
                                    if (b.bibleEntries > 0) "${b.bibleEntries} bible entries" else null,
                                    b.forkOfTitle?.let { "forked from $it" },
                                ).joinToString(", "),
                                maxLines = 2,
                            )
                        }
                        if (b.pendingDrafts > 0) Badge { Text("${b.pendingDrafts} waiting") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                BookMenu(b, menu, { menu = false }, actions)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onNew: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Your shelf is empty", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Start a new book, then write a chapter yourself or continue with an AI. You can also import a shared story file.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        com.ajay.storyboard.ui.common.Button(onClick = onNew) { Text("New book") }
    }
}
