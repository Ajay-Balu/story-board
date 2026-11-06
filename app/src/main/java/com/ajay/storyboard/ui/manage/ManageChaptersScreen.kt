package com.ajay.storyboard.ui.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import com.ajay.storyboard.ui.common.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.ChapterSource
import com.ajay.storyboard.data.ChapterSummary
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.common.relativeTime
import com.ajay.storyboard.ui.common.wordCount
import kotlinx.coroutines.launch

/**
 * Chapters of a book as clear cards. Tap a chapter for its actions; reordering is a separate mode
 * so arrows can't be hit by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageChaptersScreen(
    repo: Repository,
    bookId: Long,
    onBack: () -> Unit,
    onRead: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onNew: () -> Unit,
    onVersions: (Long) -> Unit,
    onRefactor: (Long) -> Unit,
    onHistory: (Long) -> Unit,
    onForked: (Long) -> Unit = {},
) {
    var forkAt by remember { mutableStateOf<Int?>(null) }
    val chapters by remember(bookId) { repo.chapters.observeSummaries(bookId) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var reorder by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Pair<Int, ChapterSummary>?>(null) }
    var confirmDelete by remember { mutableStateOf<ChapterSummary?>(null) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (reorder) "Reorder chapters" else "Chapters")
                        val list = chapters.orEmpty()
                        Text("${list.size} chapter${if (list.size == 1) "" else "s"}, ${list.sumOf { wordCount(it.chapter.content) }} words", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (reorder) TextButton(onClick = { reorder = false }) {
                        Icon(Icons.Filled.Check, null); Text(" Done")
                    } else if ((chapters?.size ?: 0) > 1) TextButton(onClick = { reorder = true }) {
                        Icon(Icons.Outlined.SwapVert, null); Text(" Reorder")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!reorder) ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Write a chapter") })
        },
    ) { pad ->
        val list = chapters ?: return@Scaffold
        if (list.isEmpty()) {
            Text("No chapters yet.", modifier = Modifier.padding(pad).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.chapter.id }) { idx, s ->
                if (reorder) ReorderRow(
                    number = idx + 1, title = s.chapter.title,
                    canUp = idx > 0, canDown = idx < list.lastIndex,
                    onUp = { scope.launch { repo.moveChapter(bookId, s.chapter.id, -1) } },
                    onDown = { scope.launch { repo.moveChapter(bookId, s.chapter.id, +1) } },
                    modifier = Modifier.animateItem(),
                ) else ChapterCard(idx + 1, s, onClick = { actionsFor = idx + 1 to s }, modifier = Modifier.animateItem())
            }
        }
    }

    forkAt?.let { n ->
        var title by remember { mutableStateOf("") }
        androidx.compose.runtime.LaunchedEffect(n) { title = "${repo.books.get(bookId)?.title.orEmpty()} (fork)" }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forkAt = null },
            title = { Text("Fork from Chapter $n") },
            text = {
                Column {
                    Text("A separate book with chapters 1–$n, the story bible and the pictures. This book is not changed.")
                    androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("New book title") }, singleLine = true,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                com.ajay.storyboard.ui.common.TextButton(enabled = title.isNotBlank(), onClick = {
                    forkAt = null
                    scope.launch { onForked(repo.forkBook(bookId, n, title)) }
                }) { Text("Fork") }
            },
            dismissButton = { com.ajay.storyboard.ui.common.TextButton(onClick = { forkAt = null }) { Text("Cancel") } },
        )
    }

    actionsFor?.let { (number, s) ->
        val ch = s.chapter
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Chapter $number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Text(ch.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                fun go(action: () -> Unit) { actionsFor = null; action() }
                SheetAction(Icons.AutoMirrored.Outlined.MenuBook, "Read", "Open the book at this chapter") { go { onRead(ch.id) } }
                SheetAction(Icons.Outlined.Edit, "Edit", "Change the title and text") { go { onEdit(ch.id) } }
                SheetAction(Icons.Filled.AutoFixHigh, "Refactor with AI", "Check consistency and rewrite, step by step") { go { onRefactor(ch.id) } }
                SheetAction(Icons.Outlined.History, "Version history", "${s.versionCount} older version${if (s.versionCount == 1) "" else "s"}, compare and restore") { go { onVersions(ch.id) } }
                SheetAction(Icons.Outlined.CallSplit, "Fork from here", "New separate book with chapters 1–$number") { go { forkAt = number } }
                SheetAction(Icons.Outlined.Psychology, "AI history", "${s.aiRunCount} saved check${if (s.aiRunCount == 1) "" else "s"} and rewrites") { go { onHistory(ch.id) } }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SheetAction(Icons.Outlined.Delete, "Delete chapter", null, danger = true) { go { confirmDelete = s } }
            }
        }
    }


    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${s.chapter.title}\"?") },
            text = { Text("The chapter and its ${s.versionCount} saved versions are removed. AI history for it is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        val snap = repo.deleteChapter(s.chapter.id) ?: return@launch
                        val r = snackbar.showSnackbar("Deleted \"${s.chapter.title}\"", actionLabel = "Undo", duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) repo.restoreChapter(snap)
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChapterCard(number: Int, s: ChapterSummary, onClick: () -> Unit, modifier: Modifier) {
    val ch = s.chapter
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberBadge(number)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(ch.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    remember(ch.content, ch.title) {
                        // first words of the story text: no repeated title, pictures or divider lines
                        com.ajay.storyboard.data.ChapterBlocks.lines(com.ajay.storyboard.ui.common.dropRepeatedTitle(ch.content, ch.title))
                            .filter { com.ajay.storyboard.data.ChapterBlocks.picture(it) == null && !it.matches(Regex("^[-*_\\s]{3,}$")) }
                            .joinToString(" ").replace(Regex("[#*_]"), "").trim().take(160)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp),
                )
                val src = when (ch.source) {
                    ChapterSource.MANUAL -> "Written by you"
                    ChapterSource.AI -> "AI"
                    ChapterSource.AI_RECOVERED -> "AI, recovered"
                }
                val extras = buildList {
                    if (s.versionCount > 0) add("${s.versionCount} version${if (s.versionCount == 1) "" else "s"}")
                    if (s.aiRunCount > 0) add("${s.aiRunCount} AI result${if (s.aiRunCount == 1) "" else "s"}")
                    if (!ch.inBible) add("not in bible yet")
                }
                Text(
                    (listOf("${wordCount(com.ajay.storyboard.data.ChapterBlocks.textOnly(ch.content))} words", src, "edited ${relativeTime(ch.updatedAt)}") + extras).joinToString(", "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun NumberBadge(number: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ReorderRow(
    number: Int, title: String, canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, modifier: Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", Modifier.widthIn(min = 28.dp), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String?, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.onSurface) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}
