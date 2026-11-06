package com.ajay.storyboard.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiErrorPanel
import com.ajay.storyboard.ai.AiException
import com.ajay.storyboard.ai.AiStatusCard
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.BibleEntry
import com.ajay.storyboard.data.GalleryImage
import com.ajay.storyboard.ui.common.BackTopBar
import com.ajay.storyboard.ui.common.TargetPicker
import com.ajay.storyboard.ui.common.relativeTime
import com.ajay.storyboard.ui.reader.PendingScene
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The final prompt: your words plus the chosen story-bible details (looks, outfits, places). */
fun buildImagePrompt(userPrompt: String, details: List<BibleEntry>, scene: String = ""): String = buildString {
    append(userPrompt.trim())
    if (scene.isNotBlank()) {
        if (isNotEmpty()) { appendLine(); appendLine() }
        appendLine("The picture shows this moment of the story:")
        append(scene.trim().take(3000))
    }
    if (details.isNotEmpty()) {
        appendLine(); appendLine()
        appendLine("Keep these story details exact:")
        details.forEach { appendLine("- ${it.name} (${it.category.lowercase()}): ${it.details}") }
    }
}.trim()

/** Generation state kept outside the screen, so leaving doesn't lose the result. */
object ImageJobs {
    var running by mutableStateOf<String?>(null)        // target id
    var lastError by mutableStateOf<Pair<String, String>?>(null)   // target id, message
    var lastResult by mutableStateOf<List<GalleryImage>>(emptyList())
}

/**
 * Ask an AI for a picture. Pick story-bible details to include, write your prompt, check the final
 * prompt, choose the AI. Every request is kept in the prompt history so you can retry it with the
 * same or another AI; every picture goes to the Gallery.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenerateImageScreen(app: StoryBoardApp, initialPrompt: String?, initialBookId: Long?, onBack: () -> Unit, onGallery: () -> Unit) {
    val repo = app.repo
    val books by remember { repo.books.observeLibrary() }.collectAsState(initial = emptyList())
    var bookId by remember { mutableStateOf(initialBookId) }
    val entries by remember(bookId) { bookId?.let { repo.bible.observeForBook(it) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val history by remember { repo.images.observeRequests() }.collectAsState(initial = emptyList())
    val picked = remember { mutableStateListOf<Long>() }
    var prompt by remember { mutableStateOf(initialPrompt.orEmpty()) }
    var finalPrompt by remember { mutableStateOf(initialPrompt.orEmpty()) }
    var finalEdited by remember { mutableStateOf(initialPrompt != null) }
    var targetId by remember { mutableStateOf(app.settings.targetFor(AiAction.IMAGE)) }
    var bookMenu by remember { mutableStateOf(false) }
    // Started from selected paragraphs in edit mode: the scene, where to insert, bible entries it mentions.
    val scene = remember { PendingScene.scene.also { PendingScene.scene = null } }
    var insertAt by remember { mutableStateOf(scene?.insertAt ?: 0) }
    var useScene by remember { mutableStateOf(scene != null) }
    val inserted = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(scene) { if (scene != null) { bookId = scene.bookId; ImageJobs.lastResult = emptyList(); ImageJobs.lastError = null } }
    LaunchedEffect(entries) {
        if (scene != null && picked.isEmpty()) {
            val text = scene.text.lowercase()
            entries.filter { e ->
                e.category != "TIMELINE" && e.name.split(" - ", "(").first().trim().lowercase().let { n -> n.length > 1 && text.contains(n) }
            }.forEach { picked.add(it.id) }
        }
    }

    LaunchedEffect(prompt, picked.toList(), entries, useScene) {
        if (!finalEdited) finalPrompt = buildImagePrompt(prompt, entries.filter { it.id in picked }, if (useScene) scene?.text.orEmpty() else "")
    }

    fun generate(id: String) {
        val target = app.catalog.resolve(id) ?: return
        targetId = id
        ImageJobs.lastError = null
        ImageJobs.running = id
        app.appScope.launch {
            try {
                ImageJobs.lastResult = app.ai.generateImages(target, finalPrompt, bookId, scene?.chapterId,
                    anchor = scene?.let { "${it.chapterId}:${it.insertAt}" })
            } catch (e: AiException) {
                ImageJobs.lastError = id to (e.message ?: e.code)
            } catch (e: Exception) {
                ImageJobs.lastError = id to (e.message ?: e.javaClass.simpleName)
            } finally {
                ImageJobs.running = null
            }
        }
    }

    Scaffold(topBar = { BackTopBar(title = "New picture", onBack = onBack, actions = { TextButton(onClick = onGallery) { Text("Gallery") } }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Story", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { bookMenu = true }) {
                        Text(books.firstOrNull { it.id == bookId }?.title ?: "No story", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                        DropdownMenuItem(text = { Text("No story") }, onClick = { bookMenu = false; bookId = null; picked.clear() })
                        books.forEach { b -> DropdownMenuItem(text = { Text(b.title) }, onClick = { bookMenu = false; bookId = b.id; picked.clear() }) }
                    }
                }
            }
            if (entries.isNotEmpty()) {
                Text("Story details to include (characters, outfits, places)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    entries.filter { it.category != "TIMELINE" }.forEach { e ->
                        FilterChip(
                            selected = e.id in picked,
                            onClick = { if (e.id in picked) picked.remove(e.id) else picked.add(e.id); finalEdited = false },
                            label = { Text(e.name, maxLines = 1) },
                        )
                    }
                }
            }
            if (scene != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include the selected paragraphs", style = MaterialTheme.typography.bodyLarge)
                    Text(scene.text.take(140) + if (scene.text.length > 140) "…" else "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                androidx.compose.material3.Switch(useScene, { useScene = it; finalEdited = false })
            }
            OutlinedTextField(
                prompt, { prompt = it; finalEdited = false },
                label = { Text("Describe the picture") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            OutlinedTextField(
                finalPrompt, { finalPrompt = it; finalEdited = true },
                label = { Text("Final prompt (sent as is; you can edit it)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TargetPicker(app.catalog, targetId, images = true) { targetId = it.id }
            }
            Button(
                enabled = finalPrompt.isNotBlank() && ImageJobs.running == null,
                onClick = { generate(targetId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate with ${app.catalog.label(targetId)}") }

            ImageJobs.running?.let { AiStatusCard(app.runner, it) }
            ImageJobs.lastError?.let { (id, msg) ->
                if (ImageJobs.running == null) AiErrorPanel(
                    runner = app.runner, catalog = app.catalog, targetId = id, message = msg,
                    onRetryWith = { generate(it) }, forImages = true,
                )
            }
            if (ImageJobs.lastResult.isNotEmpty() && ImageJobs.running == null) {
                Text("Saved to the Gallery", style = MaterialTheme.typography.labelLarge)
                ThumbRow(ImageJobs.lastResult) { onGallery() }
                if (scene != null) {
                    Text("Put a picture into \"${scene.chapterTitle}\"", style = MaterialTheme.typography.labelLarge)
                    ImageJobs.lastResult.forEachIndexed { i, img ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Picture ${i + 1}", Modifier.weight(1f))
                            if (img.id in inserted) Text("added", color = MaterialTheme.colorScheme.primary)
                            else {
                                TextButton(onClick = {
                                    scope.launch { repo.insertPicture(scene.chapterId, insertAt, img.id); insertAt += 1; inserted.add(img.id) }
                                }) { Text("After the paragraphs") }
                                TextButton(onClick = {
                                    scope.launch { repo.insertPicture(scene.chapterId, 0, img.id); insertAt += 1; inserted.add(img.id) }
                                }) { Text("Start") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val n = repo.chapters.get(scene.chapterId)?.let { com.ajay.storyboard.data.ChapterBlocks.lines(it.content).size } ?: 0
                                        repo.insertPicture(scene.chapterId, n, img.id); inserted.add(img.id)
                                    }
                                }) { Text("End") }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Prompt history", style = MaterialTheme.typography.titleSmall)
            if (history.isEmpty()) Text("Every prompt you send is kept here to try again.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.take(50).forEach { r ->
                Card(
                    onClick = {
                        finalPrompt = r.prompt; finalEdited = true
                        if (app.catalog.resolve(r.target) != null) targetId = r.target
                        r.bookId?.let { bookId = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(r.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${r.targetLabel}, ${relativeTime(r.createdAt)}, " + when (r.status) {
                                "DONE" -> "${r.imageCount} picture${if (r.imageCount == 1) "" else "s"}"
                                "FAILED" -> "failed" + (r.error?.let { ": ${it.take(60)}" } ?: "")
                                else -> "working…"
                            },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
