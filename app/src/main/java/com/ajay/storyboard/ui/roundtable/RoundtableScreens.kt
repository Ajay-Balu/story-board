package com.ajay.storyboard.ui.roundtable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.ajay.storyboard.ui.common.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiStatusCard
import com.ajay.storyboard.ai.Prompts
import com.ajay.storyboard.ai.RoundtableService
import com.ajay.storyboard.data.Roundtable
import com.ajay.storyboard.data.RoundtableMessage
import com.ajay.storyboard.ui.common.BackTopBar
import com.ajay.storyboard.ui.common.ConfirmDialog
import com.ajay.storyboard.ui.common.MetaText
import com.ajay.storyboard.ui.common.SectionHead
import com.ajay.storyboard.ui.common.Sheet
import com.ajay.storyboard.ui.common.TargetPicker
import com.ajay.storyboard.ui.common.relativeTime
import kotlinx.coroutines.launch

/** All roundtable sessions, newest first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundtableListScreen(app: StoryBoardApp, onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit) {
    val list by remember { app.repo.roundtables.observeAll() }.collectAsState(initial = null)
    val books by remember { app.repo.books.observeLibrary() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Roundtable?>(null) }
    Scaffold(
        topBar = { BackTopBar(title = "AI Roundtable", onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New roundtable") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp)) {
            if (list?.isEmpty() == true) item {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Put one question to several AIs", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Each answers on its own. The moderator lists where they disagree and tells each one what to answer next; " +
                            "you can edit that before every round. After the last round the moderator writes the verdict.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list.orEmpty(), key = { it.id }) { r ->
                Column(
                    Modifier.animateItem().fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(r.id) }, onLongClick = { deleting = r })
                        .padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val book = r.bookId?.let { id -> books.firstOrNull { it.id == id }?.title }
                    MetaText(
                        listOfNotNull(
                            book?.let { "About $it" },
                            "${r.targetIds.size} AIs, ${r.rounds} round${if (r.rounds == 1) "" else "s"}",
                            when {
                                app.roundtable.isRunning(r.id) -> "running"
                                r.status == "WAITING" -> "waiting for you"
                                r.status == "FAILED" -> "stopped with errors"
                                else -> relativeTime(r.updatedAt)
                            },
                        ).joinToString(", "),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    deleting?.let { r ->
        ConfirmDialog(
            title = "Delete this roundtable?", text = "\"${r.title}\" and all its answers are removed.", confirmLabel = "Delete",
            onConfirm = { deleting = null; app.roundtable.stop(r.id); scope.launch { app.repo.roundtables.delete(r) } },
            onDismiss = { deleting = null },
        )
    }
}

/** Who sits at the table, who moderates, how many rounds, and whether to pause for the guidance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TableSetup(
    app: StoryBoardApp,
    chosen: SnapshotStateList<String>,
    moderator: String, onModerator: (String) -> Unit,
    rounds: Int, onRounds: (Int) -> Unit,
    pause: Boolean, onPause: (Boolean) -> Unit,
) {
    val profiles by app.catalog.profiles.collectAsState()
    val sites by app.catalog.sites.collectAsState()
    val options = remember(profiles, sites) { app.catalog.forText() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHead("At the table", trailing = "${chosen.size} chosen")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { t ->
                val on = t.id in chosen
                FilterChip(selected = on, onClick = { if (on) chosen.remove(t.id) else chosen.add(t.id) }, label = { Text(t.label) })
            }
        }
        MetaText("Pick two or more. Each AI answers in its own background page or with its key, all at once.")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Moderator", style = MaterialTheme.typography.titleSmall)
                MetaText("Guides every round and writes the verdict")
            }
            TargetPicker(app.catalog, moderator) { onModerator(it.id) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Debate rounds", style = MaterialTheme.typography.titleSmall)
                MetaText(if (rounds == 0) "Answers, then straight to the verdict" else "After the first answers, before the verdict")
            }
            OutlinedIconButton(enabled = rounds > 0, onClick = { onRounds(rounds - 1) }) { Icon(Icons.Filled.Remove, "Fewer rounds") }
            Text("$rounds", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedIconButton(enabled = rounds < RoundtableService.MAX_ROUNDS, onClick = { onRounds(rounds + 1) }) { Icon(Icons.Filled.Add, "More rounds") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Let me edit the guidance", style = MaterialTheme.typography.titleSmall)
                MetaText("Pause before each round to read and change what the moderator tells the AIs")
            }
            Switch(pause, onPause)
        }
    }
}

/** Ask a new question: pick the AIs, the moderator and the rounds; in a book, the story context is included. */
@Composable
fun NewRoundtableScreen(app: StoryBoardApp, bookId: Long?, onBack: () -> Unit, onStarted: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val book by remember(bookId) { bookId?.let { app.repo.books.observe(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(initial = null)
    val chosen = remember { mutableStateListOf<String>() }
    var question by remember { mutableStateOf("") }
    var includeStory by remember { mutableStateOf(true) }
    var moderator by remember { mutableStateOf("web:CHATGPT") }
    var rounds by remember { mutableIntStateOf(1) }
    var pause by remember { mutableStateOf(true) }
    var context by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (chosen.isEmpty()) {
            listOf(app.settings.targetFor(com.ajay.storyboard.data.AiAction.WRITE), app.settings.targetFor(com.ajay.storyboard.data.AiAction.CHECK))
                .distinct().filter { app.catalog.resolve(it) != null }.forEach { chosen.add(it) }
        }
    }
    LaunchedEffect(bookId, includeStory) {
        context = if (bookId != null && includeStory) {
            val b = app.repo.books.get(bookId)
            if (b == null) "" else Prompts.roundtableContext(b.title, app.repo.bible.listForBook(bookId), app.repo.chapters.listForBook(bookId))
        } else ""
    }
    val ready = question.isNotBlank() && chosen.size >= 2

    Scaffold(
        topBar = {
            BackTopBar(title = "New roundtable", subtitle = book?.title?.let { "About $it" }, onBack = onBack, actions = {
                TextButton(enabled = ready, onClick = {
                    scope.launch { onStarted(app.roundtable.create(bookId, question, context, chosen.toList(), moderator, rounds, pause)) }
                }) { Text("Start") }
            })
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                question, { question = it },
                label = { Text(if (bookId != null) "What should happen next?" else "Your question") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            if (bookId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include the story", style = MaterialTheme.typography.titleSmall)
                    MetaText("Story bible and the last two chapters, ${context.length} characters")
                }
                Switch(includeStory, { includeStory = it })
            }
            TableSetup(app, chosen, moderator, { moderator = it }, rounds, { rounds = it }, pause, { pause = it })
            Button(enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch { onStarted(app.roundtable.create(bookId, question, context, chosen.toList(), moderator, rounds, pause)) }
            }) { Text(if (chosen.size < 2) "Pick at least two AIs" else "Start the roundtable") }
        }
    }
}

/** One roundtable: questions, each round's answers, the moderator's guidance, the verdict, and what to do next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoundtableScreen(app: StoryBoardApp, id: Long, onBack: () -> Unit, onSendToWrite: (bookId: Long) -> Unit) {
    val rt by remember(id) { app.repo.roundtables.observe(id) }.collectAsState(initial = null)
    val msgs by remember(id) { app.repo.roundtables.observeMessages(id) }.collectAsState(initial = emptyList())
    val running = app.roundtable.isRunning(id) || app.roundtable.working[id] != null
    val context = LocalContext.current
    var followUp by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    val r = rt ?: return
    val lastVerdict = msgs.lastOrNull { it.kind == RoundtableService.VERDICT && it.status == RoundtableService.DONE }
    val waiting = msgs.any { it.status == RoundtableService.REVIEW }

    Scaffold(
        topBar = {
            BackTopBar(title = r.title, subtitle = r.targetIds.joinToString(", ") { app.catalog.label(it) }, onBack = onBack, actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Table setup") }, onClick = { menu = false; showSetup = true })
                        if (r.context.isNotBlank()) DropdownMenuItem(text = { Text("Story context sent") }, onClick = { menu = false; showContext = true })
                        DropdownMenuItem(text = { Text("Share the transcript") }, onClick = {
                            menu = false
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, r.title).putExtra(Intent.EXTRA_TEXT, transcript(r, msgs)), "Share the roundtable"))
                        })
                        if (running) DropdownMenuItem(text = { Text("Stop") }, onClick = { menu = false; app.roundtable.stop(id) })
                        else if (!waiting) DropdownMenuItem(text = { Text("Continue unfinished") }, onClick = { menu = false; app.roundtable.resume(id) })
                    }
                }
            })
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val grouped = msgs.groupBy { Triple(it.turn, it.round, it.kind == RoundtableService.GUIDE) }
                    .toSortedMap(compareBy({ it.first }, { it.second }, { !it.third }))
                grouped.forEach { (key, list) ->
                    val first = list.first()
                    item(key = "h${key.first}-${key.second}-${key.third}") {
                        SectionHead(
                            when (first.kind) {
                                RoundtableService.QUESTION -> if (key.first == 1) "Question" else "Follow-up question"
                                RoundtableService.ANSWER -> "Round 1: answers"
                                RoundtableService.GUIDE -> "Moderator, before round ${key.second}"
                                RoundtableService.CHALLENGE -> "Round ${key.second}: rebuttals and revised answers"
                                else -> "Verdict"
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(list, key = { it.id }) { m ->
                        Box(Modifier.animateItem()) {
                            if (m.status == RoundtableService.REVIEW) GuidanceReview(app, m) else MessageBlock(app, m)
                        }
                    }
                }
                app.roundtable.working[id]?.let { w ->
                    item(key = "working") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(w, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !running && !waiting && lastVerdict != null, onClick = { app.roundtable.addRound(id) }) { Text("Another round") }
                        if (lastVerdict != null) OutlinedButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("verdict", lastVerdict.content))
                        }) { Text("Copy verdict") }
                        if (r.bookId != null && lastVerdict != null) Button(onClick = {
                            app.writeNote = lastVerdict.content
                            onSendToWrite(r.bookId)
                        }) { Text("Send to Write") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(followUp, { followUp = it }, placeholder = { Text("Follow-up question") },
                            modifier = Modifier.weight(1f).heightIn(max = 140.dp))
                        TextButton(enabled = !running && !waiting && followUp.isNotBlank(), onClick = {
                            app.roundtable.followUp(id, followUp); followUp = ""
                        }) { Text("Ask") }
                    }
                }
            }
        }
    }
    if (showContext) AlertDialog(
        onDismissRequest = { showContext = false },
        title = { Text("Story context sent to the AIs") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { Text(r.context, style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { showContext = false }) { Text("Close") } },
    )
    if (showSetup) {
        val chosen = remember { mutableStateListOf<String>().apply { addAll(r.targetIds) } }
        var moderator by remember { mutableStateOf(r.moderator) }
        var rounds by remember { mutableIntStateOf(r.rounds) }
        var pause by remember { mutableStateOf(r.pauseForGuidance) }
        AlertDialog(
            onDismissRequest = { showSetup = false },
            title = { Text("Table setup") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    TableSetup(app, chosen, moderator, { moderator = it }, rounds, { rounds = it }, pause, { pause = it })
                    Spacer(Modifier.height(8.dp))
                    MetaText("New AIs answer the current question first, then join the next round. Changes apply from the next step.")
                }
            },
            confirmButton = {
                TextButton(enabled = chosen.size >= 2, onClick = {
                    showSetup = false
                    app.roundtable.updateSetup(id, chosen.toList(), moderator, rounds, pause)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSetup = false }) { Text("Cancel") } },
        )
    }
}

/** The moderator's guidance for the next round, waiting for you: edit it, then run the round or skip to the verdict. */
@Composable
private fun GuidanceReview(app: StoryBoardApp, m: RoundtableMessage) {
    var text by remember(m.id) { mutableStateOf(m.content) }
    Sheet {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${m.targetLabel.ifBlank { m.target }} prepared round ${m.round}", style = MaterialTheme.typography.titleSmall)
            MetaText("This is what every AI reads before answering again. Change anything, add your own instructions, then run the round.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = text.isNotBlank(), onClick = { app.roundtable.approveGuidance(m.id, text) }) { Text("Run round ${m.round}") }
                OutlinedButton(onClick = { app.roundtable.skipToVerdict(m.id) }) { Text("Skip to verdict") }
            }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), textStyle = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { app.roundtable.retry(m.id) }) { Text("Ask the moderator again") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBlock(app: StoryBoardApp, m: RoundtableMessage) {
    val verdict = m.kind == RoundtableService.VERDICT
    val guide = m.kind == RoundtableService.GUIDE
    var expanded by remember(m.id) { mutableStateOf(verdict || m.kind == RoundtableService.QUESTION) }
    var retryMenu by remember { mutableStateOf(false) }
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp, horizontal = if (verdict) 14.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (m.kind != RoundtableService.QUESTION) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.targetLabel.ifBlank { m.target }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when (m.status) {
                    RoundtableService.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    RoundtableService.PENDING -> MetaText("waiting")
                    RoundtableService.DONE -> if (!expanded) MetaText("${m.content.split(Regex("\\s+")).size} words")
                    RoundtableService.FAILED -> Text("failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (m.status == RoundtableService.RUNNING) AiStatusCard(app.runner, m.target)
            if (m.status == RoundtableService.FAILED) {
                Text(m.error ?: "Failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Box {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.roundtable.retry(m.id) }) { Text("Try again") }
                        OutlinedButton(onClick = { retryMenu = true }) { Text("Use another AI") }
                    }
                    DropdownMenu(expanded = retryMenu, onDismissRequest = { retryMenu = false }) {
                        app.catalog.forText().filter { it.id != m.target }.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { retryMenu = false; app.roundtable.retry(m.id, t.id) })
                        }
                    }
                }
            }
            if (m.content.isNotBlank()) {
                if (expanded) {
                    SelectionContainer { Text(m.content, style = if (m.kind == RoundtableService.QUESTION) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium) }
                    if (m.status == RoundtableService.DONE && m.kind != RoundtableService.QUESTION && !guide && !app.roundtable.isRunning(m.roundtableId)) Box {
                        Row {
                            TextButton(onClick = { app.roundtable.retry(m.id) }) { Text("Ask again") }
                            TextButton(onClick = { retryMenu = true }) { Text("Ask another AI") }
                        }
                        DropdownMenu(expanded = retryMenu, onDismissRequest = { retryMenu = false }) {
                            app.catalog.forText().filter { it.id != m.target }.forEach { t ->
                                DropdownMenuItem(text = { Text(t.label) }, onClick = { retryMenu = false; app.roundtable.retry(m.id, t.id) })
                            }
                        }
                    }
                } else Text(m.content, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (verdict) Sheet { body() } else Column { body(); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
}

/** The whole session as plain text, for sharing. */
private fun transcript(r: Roundtable, msgs: List<RoundtableMessage>): String = buildString {
    appendLine(r.title); appendLine()
    msgs.filter { it.status == RoundtableService.DONE || it.kind == RoundtableService.QUESTION }.forEach { m ->
        val head = when (m.kind) {
            RoundtableService.QUESTION -> "Question"
            RoundtableService.ANSWER -> "${m.targetLabel}, round 1"
            RoundtableService.GUIDE -> "Moderator (${m.targetLabel}), before round ${m.round}"
            RoundtableService.CHALLENGE -> "${m.targetLabel}, round ${m.round}"
            else -> "Verdict (${m.targetLabel})"
        }
        appendLine("## $head"); appendLine(m.content.trim()); appendLine()
    }
}.trim()
