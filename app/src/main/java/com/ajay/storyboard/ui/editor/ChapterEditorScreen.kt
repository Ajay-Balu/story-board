package com.ajay.storyboard.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.ChapterSource
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.common.wordCount
import com.ajay.storyboard.ui.theme.ReaderBody
import kotlinx.coroutines.launch

/** Write a new chapter yourself, or edit an existing one (old text is kept as a version). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditorScreen(repo: Repository, bookId: Long, chapterId: Long?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var original by remember { mutableStateOf("" to "") }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(chapterId) {
        if (loaded) return@LaunchedEffect
        if (chapterId != null) {
            repo.chapters.get(chapterId)?.let { title = it.title; content = it.content }
        } else {
            title = "Chapter ${repo.chapters.nextPosition(bookId) + 1}"
        }
        original = title to content
        loaded = true
    }
    val dirty = loaded && (title to content) != original

    fun save() {
        scope.launch {
            if (chapterId == null) repo.addChapter(bookId, title, content, ChapterSource.MANUAL)
            else repo.updateChapter(chapterId, title, content, note = "Before manual edit")
            onDone()
        }
    }

    BackHandler(enabled = dirty) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (chapterId == null) "New chapter" else "Edit chapter") },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmLeave = true else onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = title.isNotBlank() && content.isNotBlank()) { Text("Save") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).imePadding()) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Chapter title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = content, onValueChange = { content = it },
                label = { Text("Chapter text") },
                textStyle = ReaderBody,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
            )
            Text(
                "${wordCount(com.ajay.storyboard.data.ChapterBlocks.textOnly(content))} words" +
                    (if (content.contains("](sbimg:")) ". Lines like ![caption](sbimg:12) are pictures: keep them, or use Edit mode to move them" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onDone() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text("Save") } },
        )
    }
}
