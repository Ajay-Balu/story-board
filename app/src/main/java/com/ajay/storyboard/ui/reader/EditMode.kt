package com.ajay.storyboard.ui.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiErrorPanel
import com.ajay.storyboard.ai.AiException
import com.ajay.storyboard.ai.AiStatusCard
import com.ajay.storyboard.ai.ImageData
import com.ajay.storyboard.ai.Prompts
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.AiRun
import com.ajay.storyboard.data.Chapter
import com.ajay.storyboard.data.ChapterBlocks
import com.ajay.storyboard.data.RunKind
import com.ajay.storyboard.data.RunStatus
import com.ajay.storyboard.ui.common.ConfirmDialog
import com.ajay.storyboard.ui.common.TargetPicker
import com.ajay.storyboard.ui.gallery.GalleryThumb
import com.ajay.storyboard.ui.theme.ReaderBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Selected paragraphs in edit mode: [from]..[to] of one chapter; [anchor] = where a range started. */
data class EditSelection(val chapterId: Long, val from: Int, val to: Int, val anchor: Int, val range: Boolean)

/** A picture request started from selected paragraphs; picked up by the New picture screen. */
data class ImageScene(val bookId: Long, val chapterId: Long, val chapterTitle: String, val insertAt: Int, val text: String)

object PendingScene {
    var scene: ImageScene? = null
}

/** Tap = select one paragraph (tap again to clear). Long-press = start a range, then tap the last paragraph. */
fun EditSelection?.tap(chapterId: Long, index: Int): EditSelection? {
    val s = this
    return when {
        s != null && s.range && s.chapterId == chapterId ->
            EditSelection(chapterId, minOf(s.anchor, index), maxOf(s.anchor, index), s.anchor, range = false)
        s != null && !s.range && s.chapterId == chapterId && s.from == index && s.to == index -> null
        else -> EditSelection(chapterId, index, index, index, range = false)
    }
}

fun longPress(chapterId: Long, index: Int) = EditSelection(chapterId, index, index, index, range = true)

/** One paragraph (or picture) in edit mode, highlighted when selected. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditableParagraph(
    app: StoryBoardApp, line: String, selected: Boolean, modifier: Modifier,
    onTap: () -> Unit, onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val block = remember(line) { MarkdownLite.block(line) }
    Column(
        modifier
            .padding(vertical = 3.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(10.dp),
    ) {
        when (block) {
            is Block.Picture -> StoryPicture(app.repo, block.imageId, block.caption, block.widthPct, Modifier.fillMaxWidth())
            is Block.Heading -> Text(block.text, style = MaterialTheme.typography.titleMedium)
            is Block.Paragraph -> Text(block.text, style = ReaderBody, color = MaterialTheme.colorScheme.onSurface)
            Block.Divider -> Text("— — —", color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * The actions for the selected paragraphs (bottom of the reader in edit mode). All changes save a
 * chapter version first, so anything can be undone from Versions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditActionBar(
    app: StoryBoardApp,
    chapter: Chapter,
    selection: EditSelection,
    onSelection: (EditSelection?) -> Unit,
    onGeneratePicture: (ImageScene) -> Unit,
    onMessage: (String) -> Unit,
) {
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val lines = remember(chapter.content) { ChapterBlocks.lines(chapter.content) }
    val from = selection.from.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val to = selection.to.coerceIn(from, (lines.size - 1).coerceAtLeast(0))
    val selected = lines.subList(from, (to + 1).coerceAtMost(lines.size))
    val picture = if (selected.size == 1) ChapterBlocks.picture(selected[0]) else null
    var dialog by remember { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selection.range) "Range: tap the last paragraph" else
                        if (selected.size == 1) (if (picture != null) "Picture selected" else "Paragraph ${from + 1} selected")
                        else "Paragraphs ${from + 1}–${to + 1} selected",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = { onSelection(null) }) { Icon(Icons.Filled.Close, "Clear selection") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (picture == null) {
                    BarButton(Icons.Filled.Edit, "Edit") { dialog = "edit" }
                    BarButton(Icons.Filled.AutoFixHigh, "Edit with AI") { dialog = "ai" }
                    BarButton(Icons.Filled.Image, "Generate picture") {
                        onGeneratePicture(ImageScene(chapter.bookId, chapter.id, chapter.title, to + 1,
                            selected.filter { ChapterBlocks.picture(it) == null }.joinToString("\n\n")))
                    }
                } else {
                    BarButton(Icons.Filled.PhotoSizeSelectLarge, "Size") { dialog = "size" }
                    BarButton(Icons.Filled.ShortText, "Caption") { dialog = "caption" }
                }
                BarButton(Icons.Filled.AddPhotoAlternate, "Insert picture") { dialog = "insert" }
                BarButton(Icons.Filled.PlaylistAdd, "Add paragraph") { dialog = "add" }
                BarButton(Icons.Filled.ArrowUpward, "Move up") {
                    scope.launch { if (repo.moveParagraphs(chapter.id, from, to, -1)) onSelection(selection.copy(from = from - 1, to = to - 1, anchor = selection.anchor - 1)) }
                }
                BarButton(Icons.Filled.ArrowDownward, "Move down") {
                    scope.launch { if (repo.moveParagraphs(chapter.id, from, to, 1)) onSelection(selection.copy(from = from + 1, to = to + 1, anchor = selection.anchor + 1)) }
                }
                BarButton(Icons.Outlined.Delete, "Delete") { dialog = "delete" }
            }
        }
    }

    when (dialog) {
        "edit" -> TextEditDialog(
            title = if (selected.size == 1) "Edit paragraph" else "Edit ${selected.size} paragraphs",
            initial = selected.joinToString("\n\n"),
            hint = "Leave a blank line between paragraphs.",
            onSave = { text -> dialog = null; scope.launch { repo.replaceParagraphs(chapter.id, from, to, listOf(text), "Edited paragraphs"); onSelection(null) } },
            onDismiss = { dialog = null },
        )
        "add" -> TextEditDialog(
            title = "New paragraph after the selection", initial = "", hint = "Leave a blank line between paragraphs.",
            onSave = { text -> dialog = null; scope.launch { repo.insertParagraphs(chapter.id, to + 1, listOf(text), "Added a paragraph"); onSelection(null) } },
            onDismiss = { dialog = null },
        )
        "caption" -> TextEditDialog(
            title = "Picture caption", initial = picture?.caption.orEmpty(), hint = "Shown under the picture. Leave empty for none.", singleLine = true,
            onSave = { text ->
                dialog = null
                picture?.let { p -> scope.launch { repo.replaceParagraphs(chapter.id, from, to, listOf(ChapterBlocks.pictureLine(p.copy(caption = text))), "Picture caption") } }
            },
            onDismiss = { dialog = null },
        )
        "size" -> picture?.let { p ->
            var pct by remember { mutableFloatStateOf(p.widthPct.toFloat()) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Picture size") },
                text = {
                    Column {
                        Text("${pct.toInt()}% of the text width")
                        Slider(pct, { pct = it }, valueRange = 20f..100f, steps = 15)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        scope.launch { repo.replaceParagraphs(chapter.id, from, to, listOf(ChapterBlocks.pictureLine(p.copy(widthPct = pct.toInt()))), "Picture size") }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "delete" -> ConfirmDialog(
            title = if (picture != null) "Remove this picture from the chapter?" else "Delete ${selected.size} paragraph${if (selected.size == 1) "" else "s"}?",
            text = if (picture != null) "It stays in the Gallery." else "The old text is kept in the chapter's versions.",
            confirmLabel = if (picture != null) "Remove" else "Delete",
            onConfirm = { dialog = null; scope.launch { repo.replaceParagraphs(chapter.id, from, to, emptyList(), "Deleted paragraphs"); onSelection(null) } },
            onDismiss = { dialog = null },
        )
        "insert" -> InsertPictureSheet(
            app = app, bookId = chapter.bookId,
            onPick = { imageId ->
                dialog = null
                scope.launch { repo.insertPicture(chapter.id, to + 1, imageId); onSelection(null); onMessage("Picture added") }
            },
            onDismiss = { dialog = null },
        )
        "ai" -> EditWithAiDialog(
            app = app, chapter = chapter, lines = lines, from = from, to = to,
            onDone = { accepted -> dialog = null; if (accepted) { onSelection(null); onMessage("Paragraphs replaced. The old text is in Versions.") } },
        )
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun TextEditDialog(title: String, initial: String, hint: String, singleLine: Boolean = false, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it }, singleLine = singleLine,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = if (singleLine) 0.dp else 160.dp, max = 420.dp))
            }
        },
        confirmButton = { TextButton(enabled = singleLine || text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a gallery picture, or import one from the phone (copied into the private gallery). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsertPictureSheet(app: StoryBoardApp, bookId: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val images by remember { app.repo.images.observeAll() }.collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var onlyBook by remember { mutableStateOf(true) }
    val phone = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                    ImageData(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }, mime)
                }.getOrNull()
            } ?: return@launch
            val saved = app.ai.save(data, null, bookId, null, "Imported from the phone", null, sourceOverride = "phone", sourceLabelOverride = "From phone")
            onPick(saved.id)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Insert a picture", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { phone.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("From phone") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilterChip(onlyBook, { onlyBook = true }, label = { Text("This story") })
                Text(" ")
                androidx.compose.material3.FilterChip(!onlyBook, { onlyBook = false }, label = { Text("All pictures") })
            }
            val shown = images.filter { !onlyBook || it.bookId == bookId }
            if (shown.isEmpty()) Text("No pictures here yet. Use Generate picture or From phone.", Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { img ->
                    Card(onClick = { onPick(img.id) }) { GalleryThumb(img, 300, Modifier.fillMaxWidth().aspectRatio(1f)) }
                }
            }
        }
    }
}

/**
 * Edit with AI: your instruction + the selected paragraphs (with the text around them and the story
 * bible as reference) go to the chosen AI; you see before / after and accept or reject.
 * Every result is kept in AI history.
 */
@Composable
fun EditWithAiDialog(app: StoryBoardApp, chapter: Chapter, lines: List<String>, from: Int, to: Int, onDone: (accepted: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val selectedText = lines.subList(from, to + 1).filter { ChapterBlocks.picture(it) == null }.joinToString("\n\n")
    var instruction by remember { mutableStateOf("") }
    var targetId by remember { mutableStateOf(app.settings.targetFor(AiAction.REWRITE)) }
    var phase by remember { mutableStateOf("input") }     // input, working, result, error
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var runId by remember { mutableStateOf(0L) }
    var tab by remember { mutableIntStateOf(0) }

    fun send(id: String) {
        val target = app.catalog.resolve(id) ?: return
        targetId = id
        phase = "working"
        scope.launch {
            val book = app.repo.books.get(chapter.bookId)
            val before = lines.subList((from - 2).coerceAtLeast(0), from).filter { ChapterBlocks.picture(it) == null }.joinToString("\n\n")
            val after = lines.subList((to + 1).coerceAtMost(lines.size), (to + 3).coerceAtMost(lines.size)).filter { ChapterBlocks.picture(it) == null }.joinToString("\n\n")
            val prompt = Prompts.editParagraphs(book?.title.orEmpty(), app.repo.bible.listForBook(chapter.bookId), chapter.title, before, selectedText, after, instruction)
            try {
                val reply = app.ai.ask(target, prompt, "Edit with AI")
                result = Prompts.cleanChapter(reply.text)
                runId = app.repo.runs.insert(AiRun(bookId = chapter.bookId, chapterId = chapter.id, draftId = null, kind = RunKind.EDIT,
                    service = (target as? com.ajay.storyboard.ai.AiTarget.Web)?.service?.name ?: target.label,
                    title = "${chapter.title}, paragraphs ${from + 1} to ${to + 1}", prompt = prompt, original = selectedText, result = result))
                tab = 0
                phase = "result"
            } catch (e: AiException) {
                error = e.message ?: e.code; phase = "error"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName; phase = "error"
            }
        }
    }

    // a background AI page shown with "Show page" sits under dialogs: step aside while it is open
    if (app.runner.shown == null) Dialog(onDismissRequest = { if (phase != "working") onDone(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                fun accept() {
                    scope.launch {
                        // pictures inside the selection stay where they were (after the new text)
                        val pics = lines.subList(from, to + 1).filter { ChapterBlocks.picture(it) != null }
                        app.repo.replaceParagraphs(chapter.id, from, to, listOf(result) + pics, "Before Edit with AI")
                        app.repo.runs.setStatus(runId, RunStatus.ACCEPTED)
                        onDone(true)
                    }
                }
                // the next step always sits in the header, never below a long selection
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
                    IconButton(onClick = { onDone(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                    Text("Edit with AI", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    when (phase) {
                        "input" -> Button(enabled = instruction.isNotBlank() && selectedText.isNotBlank(), onClick = { send(targetId) }) { Text("Send") }
                        "result" -> Button(onClick = { accept() }) { Text("Accept") }
                    }
                }
                when (phase) {
                    "input" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ask", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TargetPicker(app.catalog, targetId) { targetId = it.id }
                        }
                        OutlinedTextField(instruction, { instruction = it }, label = { Text("What should change?") },
                            placeholder = { Text("e.g. make the dialogue tenser and add the rain") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp))
                        com.ajay.storyboard.ui.common.SectionHead("Selected text", trailing = "${selectedText.split(Regex("\\s+")).size} words")
                        Text(selectedText, style = ReaderBody)
                    }
                    "working" -> Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AiStatusCard(app.runner, targetId)
                        Text("Rewriting the selected paragraphs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "error" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AiErrorPanel(app.runner, app.catalog, targetId, error, onRetryWith = { send(it) }, onBack = { phase = "input" })
                    }
                    "result" -> {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch { app.repo.runs.setStatus(runId, RunStatus.REJECTED) }
                                phase = "input"
                            }) { Text("Try again") }
                            OutlinedButton(onClick = {
                                scope.launch { app.repo.runs.setStatus(runId, RunStatus.REJECTED); onDone(false) }
                            }) { Text("Reject") }
                        }
                        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("After") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("Before") })
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            SelectionContainer { Text(if (tab == 0) result else selectedText, style = ReaderBody) }
                        }
                    }
                }
            }
        }
    }
}
