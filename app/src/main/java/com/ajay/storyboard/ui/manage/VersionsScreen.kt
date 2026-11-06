package com.ajay.storyboard.ui.manage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.Chapter
import com.ajay.storyboard.data.ChapterVersion
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.common.relativeTime
import com.ajay.storyboard.ui.common.wordCount
import com.ajay.storyboard.ui.theme.ReaderBody
import kotlinx.coroutines.launch

/** Older versions of one chapter: compare with the current text and roll back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(repo: Repository, chapterId: Long, onBack: () -> Unit) {
    val versions by remember(chapterId) { repo.versions.observeForChapter(chapterId) }.collectAsState(initial = emptyList())
    var current by remember { mutableStateOf<Chapter?>(null) }
    var open by remember { mutableStateOf<ChapterVersion?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chapterId, reload, versions.size) { current = repo.chapters.get(chapterId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Versions of ${current?.title.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        if (versions.isEmpty()) {
            Text(
                "No older versions yet. A version is saved every time this chapter is edited, refactored or restored.",
                modifier = Modifier.padding(pad).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            items(versions, key = { it.id }) { v ->
                Card(onClick = { open = v }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(relativeTime(v.createdAt), style = MaterialTheme.typography.titleSmall)
                        Text("${v.note}, ${wordCount(v.content)} words", style = MaterialTheme.typography.bodySmall)
                        Text(v.content.take(160), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    open?.let { v ->
        var tab by remember(v) { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("This version") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Current") })
                }
            },
            text = {
                val text = if (tab == 0) v.content else current?.content.orEmpty()
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(if (tab == 0) v.title else current?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(text, style = ReaderBody, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.restoreVersion(v); reload++ }
                    open = null
                }) { Text("Restore this version") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { scope.launch { repo.versions.delete(v) }; open = null }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { open = null }) { Text("Close") }
                }
            },
        )
    }
}
