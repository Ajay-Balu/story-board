package com.ajay.storyboard.ui.refactor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiException
import com.ajay.storyboard.ai.AiStatusCard
import com.ajay.storyboard.ai.Issue
import com.ajay.storyboard.ai.Prompts
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.AiRun
import com.ajay.storyboard.ai.AiTarget
import com.ajay.storyboard.data.RunKind
import com.ajay.storyboard.data.RunStatus
import com.ajay.storyboard.ui.common.wordCount
import com.ajay.storyboard.ui.theme.ReaderBody
import com.ajay.storyboard.ui.write.AddToBookDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface Step {
    data object Loading : Step
    data class CheckPrompt(val prompt: String) : Step
    data object Checking : Step
    data class Review(val issues: List<Issue>) : Step
    data class RewritePrompt(val prompt: String) : Step
    data object Rewriting : Step
    data class Compare(val rewritten: String, val runId: Long) : Step
    data class Error(val message: String, val service: AiTarget, val back: () -> Unit, val retryWith: (String) -> Unit) : Step
}

/**
 * Refactor with AI, one clear step at a time:
 *  1. see (and edit) the consistency-check prompt → send to the Check AI (Grok)
 *  2. pick the fixes, add your own instructions
 *  3. see (and edit) the rewrite prompt → send to the Rewrite AI (DeepSeek)
 *  4. compare Original / Rewritten → use it, or keep the original
 * Every completed check and rewrite is saved to AI history, used or not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefactorScreen(
    app: StoryBoardApp,
    draftId: Long?,
    chapterId: Long?,
    onDone: () -> Unit,
    onHistory: (bookId: Long, chapterId: Long?) -> Unit,
) {
    val repo = app.repo
    val scope = rememberCoroutineScope()

    var bookId by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<Step>(Step.Loading) }
    val selected = remember { mutableStateListOf<Int>() }
    var issues by remember { mutableStateOf<List<Issue>>(emptyList()) }
    var instructions by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val chapters by remember(bookId) { repo.chapters.observeForBook(bookId) }.collectAsState(initial = emptyList())
    // Start with the defaults from Settings; "Retry with another AI" changes them for this refactor only.
    var checkService by remember { mutableStateOf(app.catalog.targetFor(AiAction.CHECK)) }
    var rewriteService by remember { mutableStateOf(app.catalog.targetFor(AiAction.REWRITE)) }
    fun pick(id: String, fallback: AiTarget) = app.catalog.resolve(id) ?: fallback

    suspend fun buildCheckPrompt(): String {
        val book = repo.books.get(bookId)!!
        val all = repo.chapters.listForBook(bookId)
        val idx = chapterId?.let { id -> all.indexOfFirst { it.id == id } } ?: all.size
        return Prompts.consistencyCheck(book.title, repo.bible.listForBook(bookId), all.getOrNull(idx - 1), title, original)
    }

    suspend fun buildRewritePrompt(): String {
        val book = repo.books.get(bookId)!!
        val chosen = selected.sorted().mapNotNull { issues.getOrNull(it) }
        return Prompts.rewrite(book.title, repo.bible.listForBook(bookId), chosen, instructions, title, original)
    }

    fun saveRun(kind: RunKind, service: AiTarget, prompt: String, result: String, status: RunStatus = RunStatus.PENDING) =
        AiRun(
            bookId = bookId, chapterId = chapterId, draftId = draftId, kind = kind, service = (service as? AiTarget.Web)?.service?.name ?: service.label,
            title = title, prompt = prompt, original = original, result = result, status = status,
        )

    fun sendCheck(prompt: String) {
        job?.cancel()
        job = scope.launch {
            step = Step.Checking
            try {
                val reply = app.ai.ask(checkService, prompt, "Consistency check")
                val parsed = Prompts.parseIssues(reply.text)
                repo.runs.insert(saveRun(RunKind.CHECK, checkService, prompt, reply.text, RunStatus.ACCEPTED))
                if (parsed == null) {
                    step = Step.Error(
                        "${checkService.label} didn't reply in the expected format. Its reply is saved in AI history.", checkService,
                        back = { step = Step.CheckPrompt(prompt) },
                        retryWith = { s -> checkService = pick(s, checkService); sendCheck(prompt) },
                    )
                } else {
                    issues = parsed
                    selected.clear(); selected.addAll(parsed.indices)
                    step = Step.Review(parsed)
                }
            } catch (e: AiException) {
                step = Step.Error(
                    e.message ?: "Check failed", checkService,
                    back = { step = Step.CheckPrompt(prompt) },
                    retryWith = { s -> checkService = pick(s, checkService); sendCheck(prompt) },
                )
            }
        }
    }

    fun sendRewrite(prompt: String) {
        job?.cancel()
        job = scope.launch {
            step = Step.Rewriting
            try {
                val reply = app.ai.ask(rewriteService, prompt, "Rewrite")
                val text = Prompts.cleanChapter(reply.text)
                val runId = repo.runs.insert(saveRun(RunKind.REWRITE, rewriteService, prompt, text))
                step = Step.Compare(text, runId)
            } catch (e: AiException) {
                step = Step.Error(
                    e.message ?: "Rewrite failed", rewriteService,
                    back = { step = Step.RewritePrompt(prompt) },
                    retryWith = { s -> rewriteService = pick(s, rewriteService); sendRewrite(prompt) },
                )
            }
        }
    }

    LaunchedEffect(draftId, chapterId) {
        if (draftId != null) {
            val d = repo.drafts.get(draftId) ?: return@LaunchedEffect onDone()
            bookId = d.bookId; original = d.content; title = "Chapter ${repo.chapters.nextPosition(d.bookId) + 1}"
        } else if (chapterId != null) {
            val c = repo.chapters.get(chapterId) ?: return@LaunchedEffect onDone()
            bookId = c.bookId; original = c.content; title = c.title
        }
        step = Step.CheckPrompt(buildCheckPrompt())
    }

    val busy = step is Step.Checking || step is Step.Rewriting
    fun leave() { if (busy) confirmLeave = true else onDone() }
    BackHandler(enabled = busy) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Refactor with AI")
                        Text(title + ", ${wordCount(original)} words", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (bookId > 0) IconButton(onClick = { onHistory(bookId, chapterId) }) { Icon(Icons.Filled.History, "AI history") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            StepIndicator(step)
            when (val s = step) {
                Step.Loading -> Unit
                is Step.CheckPrompt -> PromptStep(
                    heading = "1, Consistency check",
                    explain = "${checkService.label} will compare this chapter with the story bible and the previous chapter. Review or edit the prompt, then send it.",
                    prompt = s.prompt,
                    sendLabel = "Send to ${checkService.label}",
                    secondaryLabel = "Skip the check",
                    onSecondary = { issues = emptyList(); selected.clear(); step = Step.Review(emptyList()) },
                    onSend = ::sendCheck,
                    modifier = Modifier.weight(1f),
                )
                Step.Checking -> Working(app, checkService, "Checking the chapter against the story bible…", Modifier.weight(1f))
                is Step.Review -> ReviewStep(
                    issues = s.issues,
                    selected = selected,
                    instructions = instructions,
                    onInstructions = { instructions = it },
                    onBack = { scope.launch { step = Step.CheckPrompt(buildCheckPrompt()) } },
                    onNext = { scope.launch { step = Step.RewritePrompt(buildRewritePrompt()) } },
                    modifier = Modifier.weight(1f),
                )
                is Step.RewritePrompt -> PromptStep(
                    heading = "3, Rewrite",
                    explain = "${rewriteService.label} will rewrite the chapter with the fixes and instructions below. Review or edit the prompt, then send it.",
                    prompt = s.prompt,
                    sendLabel = "Send to ${rewriteService.label}",
                    secondaryLabel = "Back to fixes",
                    onSecondary = { step = Step.Review(issues) },
                    onSend = ::sendRewrite,
                    modifier = Modifier.weight(1f),
                )
                Step.Rewriting -> Working(app, rewriteService, "Rewriting the chapter…", Modifier.weight(1f))
                is Step.Compare -> CompareStep(
                    original = original,
                    rewritten = s.rewritten,
                    onAccept = {
                        if (draftId != null) adding = s.rewritten to s.runId
                        else scope.launch {
                            repo.updateChapter(chapterId!!, title, com.ajay.storyboard.data.ChapterBlocks.keepPictures(original, s.rewritten), note = "Before AI refactor")
                            repo.runs.setStatus(s.runId, RunStatus.ACCEPTED)
                            onDone()
                        }
                    },
                    onAgain = { scope.launch { repo.runs.setStatus(s.runId, RunStatus.REJECTED); step = Step.Review(issues) } },
                    onKeepOriginal = { scope.launch { repo.runs.setStatus(s.runId, RunStatus.REJECTED); onDone() } },
                    modifier = Modifier.weight(1f),
                )
                is Step.Error -> Column(Modifier.padding(16.dp).weight(1f).verticalScroll(rememberScrollState())) {
                    com.ajay.storyboard.ai.AiErrorPanel(
                        runner = app.runner, catalog = app.catalog, targetId = s.service.id, message = s.message,
                        onRetryWith = s.retryWith, onBack = s.back,
                    )
                }
            }
        }
    }

    adding?.let { (text, runId) ->
        AddToBookDialog(
            text = text,
            nextChapterNumber = chapters.size + 1,
            onConfirm = { t, content ->
                adding = null
                scope.launch {
                    repo.addDraftToBook(draftId!!, t, content)
                    repo.runs.setStatus(runId, RunStatus.ACCEPTED)
                    onDone()
                }
            },
            onDismiss = { adding = null },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Stop waiting?") },
            text = { Text("The AI is still working. If you leave now this result is not saved.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; job?.cancel(); onDone() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

@Composable
private fun StepIndicator(step: Step) {
    val index = when (step) {
        is Step.CheckPrompt, Step.Checking -> 0
        is Step.Review -> 1
        is Step.RewritePrompt, Step.Rewriting -> 2
        is Step.Compare -> 3
        else -> -1
    }
    if (index < 0) return
    val labels = listOf("Check", "Fixes", "Rewrite", "Compare")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { if (i <= index) 1f else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
                Text(
                    l, style = MaterialTheme.typography.labelSmall,
                    color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PromptStep(
    heading: String,
    explain: String,
    prompt: String,
    sendLabel: String,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier,
) {
    var text by remember(prompt) { mutableStateOf(prompt) }
    var editing by remember(prompt) { mutableStateOf(false) }
    Column(modifier.imePadding()) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(heading, style = MaterialTheme.typography.titleMedium)
            Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Prompt, ${text.length} characters", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (text != prompt) TextButton(onClick = { text = prompt }) { Text("Reset") }
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done editing" else "Edit") }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                readOnly = !editing,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = com.ajay.storyboard.ui.theme.AppFonts.mono, fontSize = 12.sp, lineHeight = 16.sp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) { Text(secondaryLabel) }
                Button(onClick = { onSend(text) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.padding(end = 8.dp))
                    Text(sendLabel)
                }
            }
        }
    }
}

@Composable
private fun Working(app: StoryBoardApp, service: AiTarget, text: String, modifier: Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AiStatusCard(app.runner, service.id)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (service.hasPage) "This runs in a hidden ${service.label} chat with your login and usually takes under a minute. The result is saved to AI history."
            else "This uses your ${service.label} key and usually takes under a minute. The result is saved to AI history.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewStep(
    issues: List<Issue>,
    selected: MutableList<Int>,
    instructions: String,
    onInstructions: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("2, Choose the fixes", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (issues.isEmpty()) "No continuity problems found. You can still give your own instructions below."
                    else "${issues.size} possible problem${if (issues.size == 1) "" else "s"} found. Untick any you don't want fixed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(issues) { i, issue ->
                val on = i in selected
                Card(
                    Modifier.fillMaxWidth().clickable { if (on) selected.remove(i) else selected.add(i) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(checked = on, onCheckedChange = { if (it) selected.add(i) else selected.remove(i) })
                        Column(Modifier.weight(1f)) {
                            AssistChip(onClick = {}, label = { Text(issue.type.lowercase().replaceFirstChar { it.uppercase() }) })
                            if (issue.quote.isNotBlank()) Text("\"${issue.quote}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(issue.problem, style = MaterialTheme.typography.bodyMedium)
                            if (issue.fix.isNotBlank()) Text("Fix: ${issue.fix}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = instructions, onValueChange = onInstructions,
                    label = { Text("Your instructions (optional)") },
                    placeholder = { Text("e.g. She wore the red saree, not blue. Keep him cold, not friendly.") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Check again") }
                Button(onClick = onNext, enabled = selected.isNotEmpty() || instructions.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text("Next: review prompt")
                }
            }
        }
    }
}

@Composable
private fun CompareStep(
    original: String,
    rewritten: String,
    onAccept: () -> Unit,
    onAgain: () -> Unit,
    onKeepOriginal: () -> Unit,
    modifier: Modifier,
) {
    var tab by remember { mutableIntStateOf(1) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Original, ${wordCount(original)}") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Rewritten, ${wordCount(rewritten)}") })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            if (wordCount(rewritten) < wordCount(original) * 0.5) item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(
                        "The rewritten version is much shorter than the original (${wordCount(rewritten)} vs ${wordCount(original)} words). " +
                            "Check it before using it — the AI may have cut text or the reply may be incomplete.",
                        modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { Text(if (tab == 0) original else rewritten, style = ReaderBody) }
            item { Spacer(Modifier.height(24.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                Text(
                    "Either way, this rewrite stays in AI history until you delete it.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("Use the rewritten version") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Change fixes") }
                    TextButton(onClick = onKeepOriginal, modifier = Modifier.weight(1f)) { Text("Keep the original") }
                }
            }
        }
    }
}
