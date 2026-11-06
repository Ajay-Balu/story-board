package com.ajay.storyboard.ui.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.ui.common.splitTitle
import com.ajay.storyboard.ui.theme.ReaderBody

@Composable
fun RecoveredChip() {
    AssistChip(
        onClick = {},
        label = { Text("Recovered: erased by the AI") },
        leadingIcon = { Icon(Icons.Filled.Warning, null, Modifier.padding(0.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    )
}

/** The two main buttons plus Read/Discard, shared by the write page and the drafts list. */
@Composable
fun DraftActions(
    nextChapterNumber: Int,
    onRead: () -> Unit,
    onDiscard: () -> Unit,
    onRefactor: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRefactor, modifier = Modifier.weight(1f)) { Text("Refactor with AI") }
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("Add as Chapter $nextChapterNumber") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onRead, modifier = Modifier.weight(1f)) { Text("Read full reply") }
            TextButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ReadReplyDialog(text: String, erased: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Captured reply") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (erased) RecoveredChip()
                Text(text, style = ReaderBody, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Confirms the chapter title (suggested from the reply's first line) before adding. */
@Composable
fun AddToBookDialog(
    text: String,
    nextChapterNumber: Int,
    onConfirm: (title: String, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (suggested, body) = remember(text) { splitTitle(text, "Chapter $nextChapterNumber") }
    var title by remember(text) { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add as Chapter $nextChapterNumber") },
        text = {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Chapter title") }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title, body) }) { Text("Add to book") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard this reply?") },
        text = { Text("It won't be added to the book and disappears from Captured replies.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}
