package com.ajay.storyboard.ui.history

import androidx.compose.material.icons.outlined.Image

import androidx.compose.foundation.layout.aspectRatio

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajay.storyboard.ai.Prompts
import com.ajay.storyboard.data.AiRun
import com.ajay.storyboard.data.AiService
import com.ajay.storyboard.data.DraftStatus
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.data.RunKind
import com.ajay.storyboard.data.RunStatus
import com.ajay.storyboard.ui.common.ConfirmDialog
import com.ajay.storyboard.ui.common.relativeTime
import com.ajay.storyboard.ui.common.wordCount
import com.ajay.storyboard.ui.theme.ReaderBody
import com.ajay.storyboard.ui.write.AddToBookDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private fun serviceLabel(name: String) = runCatching { AiService.valueOf(name).label }.getOrDefault(name)

@Composable
fun StatusChip(run: AiRun) {
    val (text, strong) = when {
        run.kind == RunKind.CHECK -> "Check" to false
        run.status == RunStatus.ACCEPTED -> "Used" to true
        run.status == RunStatus.REJECTED -> "Not used" to false
        else -> "Not decided" to false
    }
    AssistChip(
        onClick = {}, label = { Text(text) },
        colors = if (strong) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else AssistChipDefaults.assistChipColors(),
    )
}

/** All AI checks and rewrites for a book (or one chapter), kept until you delete them. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiHistoryScreen(repo: Repository, bookId: Long, chapterId: Long?, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val flow: Flow<List<AiRun>> = remember(bookId, chapterId) {
        if (chapterId != null) repo.runs.observeForChapter(chapterId) else repo.runs.observeForBook(bookId)
    }
    val runs by flow.collectAsState(initial = null)
    val pictureRequests by remember(bookId, chapterId) { repo.images.observeRequestsFor(bookId, chapterId) }.collectAsState(initial = emptyList())
    val requestImages by remember(bookId) { repo.images.observeRequestImagesForBook(bookId) }.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf<RunKind?>(null) }
    var picturesOnly by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf<Boolean?>(null) }   // true = all, false = unused only
    val scope = rememberCoroutineScope()

    bulk?.let { everything ->
        val list = runs.orEmpty()
        val n = if (everything) list.size else list.count { it.kind == RunKind.CHECK || it.status != RunStatus.ACCEPTED }
        ConfirmDialog(
            title = if (everything) "Delete all AI history?" else "Delete checks and unused rewrites?",
            text = if (everything) "$n saved results ${if (chapterId != null) "for this chapter" else "for this book"} are removed. Your chapters are not changed."
            else "$n saved results are removed (consistency checks, and rewrites you didn't use). Rewrites you used are kept.",
            confirmLabel = "Delete $n",
            requireTick = everything,
            onConfirm = {
                bulk = null
                scope.launch { if (everything) repo.runs.deleteAll(bookId, chapterId) else repo.runs.deleteUnused(bookId, chapterId) }
            },
            onDismiss = { bulk = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI history")
                        Text(if (chapterId != null) "This chapter" else "Whole book", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!runs.isNullOrEmpty()) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Delete checks and unused rewrites") }, onClick = { menu = false; bulk = false })
                            DropdownMenuItem(text = { Text("Delete all", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; bulk = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        val all = runs ?: return@Scaffold
        val shown = if (picturesOnly) emptyList() else all.filter { filter == null || it.kind == filter }
        val pics = if (filter == null || picturesOnly) pictureRequests else emptyList()
        // runs and picture requests, newest first
        val entries: List<Any> = (shown.map { it.createdAt to it } + pics.map { it.createdAt to it }).sortedByDescending { it.first }.map { it.second }
        val imagesByRequest = requestImages.groupBy { it.requestId }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    FilterChip(selected = filter == null && !picturesOnly, onClick = { filter = null; picturesOnly = false }, label = { Text("All ${all.size + pictureRequests.size}") })
                    FilterChip(selected = filter == RunKind.REWRITE, onClick = { filter = RunKind.REWRITE; picturesOnly = false },
                        label = { Text("Rewrites ${all.count { it.kind == RunKind.REWRITE }}") })
                    FilterChip(selected = filter == RunKind.EDIT, onClick = { filter = RunKind.EDIT; picturesOnly = false },
                        label = { Text("Edits ${all.count { it.kind == RunKind.EDIT }}") })
                    FilterChip(selected = filter == RunKind.CHECK, onClick = { filter = RunKind.CHECK; picturesOnly = false },
                        label = { Text("Checks ${all.count { it.kind == RunKind.CHECK }}") })
                    FilterChip(selected = picturesOnly, onClick = { picturesOnly = true; filter = null },
                        label = { Text("Pictures ${pictureRequests.size}") })
                }
            }
            if (entries.isEmpty()) item {
                Text(
                    "Nothing yet. Every rewrite, edit, consistency check and picture request is saved here, used or not, until you delete it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(entries, key = { e -> if (e is AiRun) "r${e.id}" else "p${(e as com.ajay.storyboard.data.ImageRequest).id}" }) { e ->
                if (e is AiRun) RunRow(e) { onOpen(e.id) }
                else PictureRow(e as com.ajay.storyboard.data.ImageRequest, imagesByRequest[e.id].orEmpty())
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun RunRow(run: AiRun, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (run.kind != RunKind.CHECK) Icons.Filled.AutoFixHigh else Icons.Outlined.FactCheck, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(when (run.kind) { RunKind.REWRITE -> "Rewrite"; RunKind.EDIT -> "Edit with AI"; else -> "Consistency check" } + " by " + serviceLabel(run.service),
                style = MaterialTheme.typography.titleSmall)
            Text(run.title + (if (run.draftId != null && run.chapterId == null) " (captured reply)" else ""),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            val detail = if (run.kind == RunKind.CHECK) {
                val n = Prompts.parseIssues(run.result)?.size
                if (n == null) "reply not readable" else "$n issue${if (n == 1) "" else "s"}"
            } else wordCount(run.result).let { n -> "$n word${if (n == 1) "" else "s"}" }
            Text("$detail, ${relativeTime(run.createdAt)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(run)
    }
}

/** A picture request: its pictures, the prompt (tap to read it all) and how it went. */
@Composable
private fun PictureRow(req: com.ajay.storyboard.data.ImageRequest, images: List<com.ajay.storyboard.data.GalleryImage>) {
    var open by remember(req.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Picture by ${req.targetLabel}", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (req.status) {
                        "DONE" -> "${images.size} picture${if (images.size == 1) "" else "s"}, ${relativeTime(req.createdAt)}"
                        "FAILED" -> "Failed: ${req.error.orEmpty()}"
                        else -> "Working, ${relativeTime(req.createdAt)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (req.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            images.take(4).forEach { img ->
                com.ajay.storyboard.ui.gallery.GalleryThumb(img, 300, Modifier.weight(1f).aspectRatio(1f))
            }
            repeat((4 - images.size).coerceAtLeast(0)) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
        }
        Text(req.prompt, style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One saved result: read it, compare with the original, see the prompt, use it, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRunScreen(repo: Repository, runId: Long, onBack: () -> Unit) {
    val run by remember(runId) { repo.runs.observe(runId) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUse by remember { mutableStateOf(false) }
    var addAsNew by remember { mutableStateOf<Int?>(null) }

    val r = run
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(when (r?.kind) { RunKind.CHECK -> "Consistency check"; RunKind.EDIT -> "Edit with AI"; else -> "Rewrite" })
                        Text(r?.let { "${serviceLabel(it.service)}, ${relativeTime(it.createdAt)}: ${it.title}" }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }, enabled = r != null) { Icon(Icons.Filled.Delete, "Delete") } },
            )
        },
    ) { pad ->
        if (r == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(pad)) {
            val tabs = if (r.kind != RunKind.CHECK) listOf(if (r.kind == RunKind.EDIT) "Edited" else "Rewritten", "Original", "Prompt") else listOf("Issues", "Chapter", "Prompt")
            TabRow(selectedTabIndex = tab) { tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) } }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    tab == 2 -> item {
                        SelectionContainer { Text(r.prompt, fontFamily = com.ajay.storyboard.ui.theme.AppFonts.mono, fontSize = 12.sp, lineHeight = 16.sp) }
                    }
                    tab == 1 -> item { SelectionContainer { Text(r.original, style = ReaderBody) } }
                    r.kind != RunKind.CHECK -> item { SelectionContainer { Text(r.result, style = ReaderBody) } }
                    else -> {
                        val issues = Prompts.parseIssues(r.result)
                        if (issues == null) item { SelectionContainer { Text(r.result, fontFamily = com.ajay.storyboard.ui.theme.AppFonts.mono, fontSize = 12.sp) } }
                        else if (issues.isEmpty()) item { Text("No continuity problems were found.") }
                        else items(issues) { issue ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(issue.type.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                                    if (issue.quote.isNotBlank()) Text("\"${issue.quote}\"", style = MaterialTheme.typography.bodySmall)
                                    Text(issue.problem)
                                    if (issue.fix.isNotBlank()) Text("Fix: ${issue.fix}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (r.kind == RunKind.REWRITE) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(r)
                        Button(onClick = { confirmUse = true }, modifier = Modifier.weight(1f)) { Text("Use this version") }
                    }
                }
            }
        }
    }

    if (confirmUse && r != null) {
        val chapterExists = remember(r) { mutableStateOf<Boolean?>(null) }
        androidx.compose.runtime.LaunchedEffect(r) {
            chapterExists.value = r.chapterId?.let { repo.chapters.get(it) != null } ?: false
        }
        when (chapterExists.value) {
            null -> Unit
            true -> AlertDialog(
                onDismissRequest = { confirmUse = false },
                title = { Text("Replace the chapter text?") },
                text = { Text("\"${r.title}\" will get this rewritten text. The current text is kept in its version history.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUse = false
                        scope.launch {
                            val current = repo.chapters.get(r.chapterId!!)?.content.orEmpty()
                            repo.updateChapter(r.chapterId, r.title, com.ajay.storyboard.data.ChapterBlocks.keepPictures(current, r.result), note = "Before applying a rewrite from AI history")
                            repo.runs.setStatus(r.id, RunStatus.ACCEPTED)
                            snackbar.showSnackbar("Chapter updated")
                        }
                    }) { Text("Replace") }
                },
                dismissButton = { TextButton(onClick = { confirmUse = false }) { Text("Cancel") } },
            )
            false -> {
                confirmUse = false
                scope.launch { addAsNew = repo.chapters.nextPosition(r.bookId) + 1 }
            }
        }
    }

    addAsNew?.let { n ->
        val current = r ?: return@let
        AddToBookDialog(
            text = current.result,
            nextChapterNumber = n,
            onConfirm = { t, content ->
                addAsNew = null
                scope.launch {
                    val draft = current.draftId?.let { repo.drafts.get(it) }
                    if (draft != null && draft.status != DraftStatus.ADDED) repo.addDraftToBook(draft.id, t, content)
                    else repo.addChapter(current.bookId, t, content, com.ajay.storyboard.data.ChapterSource.AI)
                    repo.runs.setStatus(current.id, RunStatus.ACCEPTED)
                    snackbar.showSnackbar("Added as Chapter $n")
                }
            },
            onDismiss = { addAsNew = null },
        )
    }

    if (confirmDelete && r != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete from AI history?") },
            text = { Text("This saved ${if (r.kind == RunKind.CHECK) "check" else "result"} will be removed for good. Your chapters are not affected.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { repo.runs.delete(r); onBack() } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
