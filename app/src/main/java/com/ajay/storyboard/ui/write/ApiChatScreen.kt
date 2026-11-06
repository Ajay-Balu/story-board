package com.ajay.storyboard.ui.write

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiException
import com.ajay.storyboard.ai.AiTarget
import com.ajay.storyboard.ai.ApiClient
import com.ajay.storyboard.ai.ChatMsg
import com.ajay.storyboard.ai.Prompts
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.ApiChatMessage
import com.ajay.storyboard.data.Draft
import com.ajay.storyboard.data.DraftStatus
import com.ajay.storyboard.ui.common.BackTopBar
import com.ajay.storyboard.ui.common.wordCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Writing with an API key: an in-app chat for the book. Every message carries the story bible and the
 * last two chapters ("smart context") plus the last 10 messages, so long chats stay cheap.
 * Replies stream in; any reply can be added as the next chapter or refactored.
 */
@Composable
fun ApiChatScreen(
    app: StoryBoardApp,
    bookId: Long,
    newSession: Boolean,
    onBack: () -> Unit,
    onRefactor: (Long) -> Unit,
    onReopen: () -> Unit,
) {
    val repo = app.repo
    val target = remember { app.catalog.targetFor(AiAction.WRITE) as? AiTarget.Api }
    if (target == null) { LaunchedEffect(Unit) { onReopen() }; return }
    val profile = target.profile
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val book by remember(bookId) { repo.books.observe(bookId) }.collectAsState(initial = null)
    val chapters by remember(bookId) { repo.chapters.observeForBook(bookId) }.collectAsState(initial = emptyList())
    val nextNumber = chapters.size + 1

    var session by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val latest = repo.apiChat.latestSession(bookId)
        val hasAny = repo.apiChat.list(bookId, latest).isNotEmpty()
        session = if (newSession && hasAny) latest + 1 else latest
    }
    val messages by remember(session) { if (session == 0) kotlinx.coroutines.flow.flowOf(emptyList()) else repo.apiChat.observe(bookId, session) }
        .collectAsState(initial = emptyList())

    var input by remember { mutableStateOf(app.writeNote?.let { "Plan for the next chapter (from an AI roundtable):\n$it" }.orEmpty().also { app.writeNote = null }) }
    var job by remember { mutableStateOf<Job?>(null) }
    var streaming by remember { mutableStateOf<Pair<Long, String>?>(null) }   // message id -> text so far
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var aiMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<ApiChatMessage?>(null) }
    var editing by remember { mutableStateOf<ApiChatMessage?>(null) }
    val list = rememberLazyListState()

    /** Sends the conversation (ending with a user message) and streams the reply into a new message. */
    fun runReply() {
        job?.cancel()
        error = null
        job = scope.launch {
            val all = repo.apiChat.list(bookId, session)
            val b = repo.books.get(bookId) ?: return@launch
            val system = Prompts.writingSystem(b.title, repo.bible.listForBook(bookId), repo.chapters.listForBook(bookId))
            val history = all.takeLast(10).map { ChatMsg(it.role, it.content) }
            val replyId = repo.apiChat.insert(ApiChatMessage(bookId = bookId, session = session, role = "assistant", content = "", target = target.id))
            streaming = replyId to ""
            var last = 0L
            var text = ""
            try {
                text = ApiClient.chat(profile, system, history) { soFar ->
                    text = soFar
                    streaming = replyId to soFar
                    val now = System.currentTimeMillis()
                    if (now - last > 1500) { last = now; scope.launch { repo.apiChat.update(ApiChatMessage(replyId, bookId, session, "assistant", soFar, target.id)) } }
                }
            } catch (e: AiException) {
                error = e.message
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stop: keep what arrived so far
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (text.isBlank()) repo.apiChat.deleteFrom(bookId, session, replyId)
                    else repo.apiChat.update(ApiChatMessage(replyId, bookId, session, "assistant", text, target.id))
                }
                streaming = null
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || session == 0) return
        scope.launch {
            repo.apiChat.insert(ApiChatMessage(bookId = bookId, session = session, role = "user", content = text.trim(), target = target.id))
            input = ""
            runReply()
        }
    }

    /** Saves a reply as a captured reply (draft), so it can be added or refactored like web replies. */
    suspend fun draftOf(m: ApiChatMessage): Long {
        val prompt = messages.lastOrNull { it.id < m.id && it.role == "user" }?.content
        return repo.drafts.insert(Draft(bookId = bookId, service = target.label, prompt = prompt, content = m.content, status = DraftStatus.PENDING))
    }

    LaunchedEffect(messages.size, streaming?.second?.length) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1 + if (error != null) 1 else 0)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BackTopBar(
                title = book?.title.orEmpty(),
                subtitle = "Next: chapter $nextNumber, chat $session",
                onBack = onBack,
                actions = {
                    Box {
                        TextButton(onClick = { aiMenu = true }) { Text(target.label, maxLines = 1) }
                        DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                            Text("Write with", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            app.catalog.forText().forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    trailingIcon = { if (t.id == target.id) Icon(Icons.Filled.Check, null) },
                                    onClick = {
                                        aiMenu = false
                                        if (t.id != target.id) { app.settings.setTargetFor(AiAction.WRITE, t.id); onReopen() }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        job?.cancel()
                        scope.launch { session = repo.apiChat.latestSession(bookId) + if (messages.isEmpty()) 0 else 1 }
                    }) { Icon(Icons.Filled.Add, "New chat") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Earlier chats") }, enabled = session > 1, onClick = {
                                menu = false; if (session > 1) session -= 1
                            })
                            DropdownMenuItem(text = { Text("Later chat") }, onClick = {
                                menu = false
                                scope.launch { if (session < repo.apiChat.latestSession(bookId)) session += 1 }
                            })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) item {
                    Text(
                        "Tell ${profile.name} what the next chapter should be about. The story bible and the last two chapters " +
                            "are sent with every message, so you don't need a recap.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(messages, key = { it.id }) { m ->
                    val live = streaming?.takeIf { it.first == m.id }?.second
                    val text = live ?: m.content
                    val isLast = m.id == messages.last().id
                    if (m.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Card(
                                modifier = Modifier.widthIn(max = 320.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                onClick = { if (streaming == null) editing = m },
                            ) { Text(text, Modifier.padding(12.dp)) }
                        }
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SelectionContainer { Text(text.ifBlank { "…" }, style = com.ajay.storyboard.ui.theme.ReaderBody) }
                                if (live == null && text.isNotBlank()) {
                                    Text("${wordCount(text)} words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(onClick = { scope.launch { onRefactor(draftOf(m)) } }, modifier = Modifier.weight(1f)) { Text("Refactor") }
                                        com.ajay.storyboard.ui.common.Button(onClick = { adding = m }, modifier = Modifier.weight(1f)) { Text("Add as Ch. $nextNumber") }
                                    }
                                    Row {
                                        TextButton(onClick = {
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                                .setPrimaryClip(ClipData.newPlainText("reply", text))
                                        }) { Text("Copy") }
                                        if (isLast) TextButton(onClick = {
                                            scope.launch { repo.apiChat.deleteFrom(bookId, session, m.id); runReply() }
                                        }) { Text("Regenerate") }
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let { e ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(e)
                                Row {
                                    TextButton(onClick = { runReply() }) { Text("Try again") }
                                    TextButton(onClick = { aiMenu = true }) { Text("Use another AI") }
                                }
                            }
                        }
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        placeholder = { Text("Instructions for the next chapter…") },
                        modifier = Modifier.weight(1f).heightIn(max = 200.dp),
                    )
                    if (streaming != null) {
                        FilledIconButton(onClick = { job?.cancel() }, modifier = Modifier.padding(start = 8.dp)) { Icon(Icons.Filled.Stop, "Stop") }
                    } else {
                        FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        }
    }

    adding?.let { m ->
        AddToBookDialog(
            text = m.content,
            nextChapterNumber = nextNumber,
            onConfirm = { title, content ->
                adding = null
                scope.launch {
                    repo.addDraftToBook(draftOf(m), title, content)
                    snackbar.showSnackbar("Added as Chapter $nextNumber")
                }
            },
            onDismiss = { adding = null },
        )
    }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit and send again") },
            text = {
                Column {
                    Text("Messages after this one are removed and a new reply is written.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 120.dp, max = 320.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    editing = null
                    scope.launch { repo.apiChat.deleteFrom(bookId, session, m.id); send(text) }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
