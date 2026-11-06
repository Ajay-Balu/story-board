package com.ajay.storyboard.ui.bible

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.ajay.storyboard.ui.common.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiStatusCard
import com.ajay.storyboard.ai.BIBLE_CATEGORIES
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.BibleEntry
import com.ajay.storyboard.ui.common.ConfirmDialog
import kotlinx.coroutines.launch

private fun label(category: String) = when (category) {
    "CHARACTER" -> "Characters"; "RELATIONSHIP" -> "Relationships"; "OUTFIT" -> "Outfits"
    "PLACE" -> "Places & rooms"; "OBJECT" -> "Objects"; "RULE" -> "World rules"; "TIMELINE" -> "Timeline"
    else -> "Other"
}

/** View, edit and lock the story bible; locked entries are never changed by the AI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibleScreen(app: StoryBoardApp, bookId: Long, onBack: () -> Unit) {
    val repo = app.repo
    val entries by remember(bookId) { repo.bible.observeForBook(bookId) }.collectAsState(initial = null)
    val pending by remember(bookId) { repo.chapters.observePendingBible(bookId) }.collectAsState(initial = 0)
    val book by remember(bookId) { repo.books.observe(bookId) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<BibleEntry?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }  // (show, keepLocked)
    val working = app.bible.working[bookId]
    val error = app.bible.errors[bookId]
    val service = app.catalog.targetFor(AiAction.BIBLE)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Story bible")
                        Text(book?.title.orEmpty(), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rebuild from all chapters") }, onClick = { menu = false; confirmRebuild = true })
                        DropdownMenuItem(text = { Text("Delete unlocked entries") }, onClick = { menu = false; clearing = true to true })
                        DropdownMenuItem(text = { Text("Delete the whole bible", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; clearing = true to false })
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = BibleEntry(bookId = bookId, category = "CHARACTER", name = "", details = "") }) {
                Icon(Icons.Filled.Add, "Add entry")
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (working != null) {
                        AiStatusCard(app.runner, service.id)
                        if (app.runner.status[service.id] == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("  $working", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (error != null && working == null) {
                        com.ajay.storyboard.ai.AiErrorPanel(
                            runner = app.runner,
                            catalog = app.catalog,
                            targetId = app.bible.failedWith[bookId] ?: service.id,
                            message = error,
                            onRetryWith = { s -> app.bible.updatePending(bookId, s) },
                        )
                    }
                    if (pending > 0 && working == null) {
                        Card(onClick = { app.bible.updatePending(bookId) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "$pending chapter${if (pending == 1) " is" else "s are"} not in the bible yet. Tap to update with ${service.label}.",
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
            val list = entries
            if (list != null && list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No entries yet. The bible fills in automatically from each chapter you add. You can also add entries yourself and lock them so the AI never changes them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            list.orEmpty().groupBy { it.category }.toSortedMap(compareBy { BIBLE_CATEGORIES.indexOf(it).let { i -> if (i < 0) 99 else i } })
                .forEach { (category, group) ->
                    item(key = "h-$category") {
                        Text(label(category), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                    items(group, key = { it.id }) { e ->
                        Card(onClick = { editing = e }, modifier = Modifier.animateItem().fillMaxWidth()) {
                            Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                                    Text(e.details, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { scope.launch { repo.bible.update(e.copy(locked = !e.locked)) } }) {
                                    Icon(
                                        if (e.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                        if (e.locked) "Locked" else "Unlocked",
                                        tint = if (e.locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    editing?.let { e ->
        var category by remember(e) { mutableStateOf(e.category) }
        var name by remember(e) { mutableStateOf(e.name) }
        var details by remember(e) { mutableStateOf(e.details) }
        var locked by remember(e) { mutableStateOf(e.locked || e.id == 0L) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (e.id == 0L) "New entry" else "Edit entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        CategoryPicker(category) { category = it }
                        FilterChip(selected = locked, onClick = { locked = !locked }, label = { Text(if (locked) "Locked" else "Not locked") },
                            leadingIcon = { Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, Modifier.size(16.dp)) })
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value = details, onValueChange = { details = it }, label = { Text("Details") },
                        modifier = Modifier.heightIn(min = 120.dp))
                    if (locked) Text("Locked entries are treated as fixed facts and never changed by the AI.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && details.isNotBlank(), onClick = {
                    val updated = e.copy(category = category, name = name.trim(), details = details.trim(), locked = locked, updatedAt = System.currentTimeMillis())
                    scope.launch { if (e.id == 0L) repo.bible.insert(updated) else repo.bible.update(updated) }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (e.id != 0L) TextButton(onClick = { scope.launch { repo.bible.delete(e) }; editing = null }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }

    clearing?.let { (_, keepLocked) ->
        val count = entries?.size ?: 0
        val locked = entries?.count { it.locked } ?: 0
        ConfirmDialog(
            title = if (keepLocked) "Delete unlocked entries?" else "Delete the whole story bible?",
            text = if (keepLocked) "${count - locked} unlocked entries are removed; your $locked locked entries stay. All chapters are marked to be read again, so the bible refills when you tap Update."
            else "All $count entries are removed, including $locked locked ones. All chapters are marked to be read again.",
            confirmLabel = "Delete",
            requireTick = !keepLocked,
            onConfirm = { clearing = null; scope.launch { repo.clearBible(bookId, keepLocked) } },
            onDismiss = { clearing = null },
        )
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text("Rebuild the story bible?") },
            text = { Text("All unlocked entries are removed and ${service.label} reads every chapter again in order. Locked entries are kept. This can take a while for long books.") },
            confirmButton = { TextButton(onClick = { confirmRebuild = false; app.bible.rebuild(bookId) }) { Text("Rebuild") } },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryPicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(label(selected)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        BIBLE_CATEGORIES.forEach { c -> DropdownMenuItem(text = { Text(label(c)) }, onClick = { open = false; onSelect(c) }) }
    }
}
