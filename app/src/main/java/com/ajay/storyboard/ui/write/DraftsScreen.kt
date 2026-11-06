package com.ajay.storyboard.ui.write

import com.ajay.storyboard.ui.common.BackTopBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.Draft
import com.ajay.storyboard.data.DraftStatus
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.common.relativeTime
import com.ajay.storyboard.ui.common.wordCount
import kotlinx.coroutines.launch

/** Replies captured but not yet added or discarded (e.g. you left the chat page, or the app was closed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(repo: Repository, bookId: Long, onBack: () -> Unit, onRefactor: (Long) -> Unit) {
    val drafts by remember(bookId) { repo.drafts.observePending(bookId) }.collectAsState(initial = emptyList())
    val chapters by remember(bookId) { repo.chapters.observeForBook(bookId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var reading by remember { mutableStateOf<Draft?>(null) }
    var adding by remember { mutableStateOf<Draft?>(null) }
    var discarding by remember { mutableStateOf<Draft?>(null) }
    var discardAll by remember { mutableStateOf(false) }
    val earlier by remember(bookId) { repo.drafts.observeEarlier(bookId) }.collectAsState(initial = emptyList())
    var showEarlier by remember { mutableStateOf(false) }
    var reAdding by remember { mutableStateOf<Draft?>(null) }
    if (discardAll) {
        com.ajay.storyboard.ui.common.ConfirmDialog(
            title = "Discard all ${drafts.size} replies?",
            text = "None of them will be added to the book.",
            confirmLabel = "Discard all",
            onConfirm = { discardAll = false; scope.launch { repo.drafts.discardAll(bookId) } },
            onDismiss = { discardAll = false },
        )
    }
    val next = chapters.size + 1

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BackTopBar(
                title = "Captured replies",
                onBack = onBack,
                actions = { if (drafts.size > 1) TextButton(onClick = { discardAll = true }) { Text("Discard all") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            if (drafts.isEmpty()) item {
                Text(
                    "Nothing waiting. Every AI reply is captured here until you add or discard it.",
                    modifier = Modifier.padding(bottom = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(drafts, key = { it.id }) { d ->
                Card(Modifier.animateItem().fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${wordCount(d.content)} words, ${relativeTime(d.createdAt)}", style = MaterialTheme.typography.titleSmall)
                        if (d.erased) RecoveredChip()
                        d.prompt?.let {
                            Text("Your prompt: $it", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        Text(d.content.take(300), maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                        DraftActions(
                            nextChapterNumber = next,
                            onRead = { reading = d },
                            onDiscard = { discarding = d },
                            onRefactor = { onRefactor(d.id) },
                            onAdd = { adding = d },
                        )
                    }
                }
            }
            if (earlier.isNotEmpty()) {
                item {
                    TextButton(onClick = { showEarlier = !showEarlier }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (showEarlier) "Hide earlier replies" else "Show earlier replies (${earlier.size})")
                    }
                    if (showEarlier) Text(
                        "Replies you already added or discarded. If you deleted a chapter by mistake, you can add its reply again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (showEarlier) items(earlier, key = { "e" + it.id }) { d ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "${wordCount(d.content)} words, ${relativeTime(d.createdAt)}, ${if (d.status == DraftStatus.ADDED) "was added" else "was discarded"}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(d.content.take(240), maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                            Row {
                                TextButton(onClick = { reading = d }) { Text("Read") }
                                TextButton(onClick = { reAdding = d }) { Text("Add to book again") }
                            }
                        }
                    }
                }
            }
        }
    }

    reading?.let { ReadReplyDialog(it.content, it.erased) { reading = null } }
    reAdding?.let { d ->
        AddToBookDialog(
            text = d.content,
            nextChapterNumber = next,
            onConfirm = { title, content ->
                reAdding = null
                scope.launch { repo.addEarlierReply(d, title, content); snackbar.showSnackbar("Added as Chapter $next") }
            },
            onDismiss = { reAdding = null },
        )
    }
    discarding?.let { d ->
        ConfirmDiscardDialog(
            onConfirm = { discarding = null; scope.launch { repo.drafts.setStatus(d.id, DraftStatus.DISCARDED) } },
            onDismiss = { discarding = null },
        )
    }
    adding?.let { d ->
        AddToBookDialog(
            text = d.content,
            nextChapterNumber = next,
            onConfirm = { title, content ->
                adding = null
                scope.launch {
                    repo.addDraftToBook(d.id, title, content)
                    snackbar.showSnackbar("Added as Chapter $next")
                }
            },
            onDismiss = { adding = null },
        )
    }
}
