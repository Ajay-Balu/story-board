package com.ajay.storyboard.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/** Small dialog with one text field (rename a book or chapter). */
@Composable
fun RenameDialog(title: String, label: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && text.trim() != initial.trim(), onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirmation for destructive actions. With [requireTick] the user must tick
 * "I understand this can't be undone" first, so a stray tap can't delete anything big.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    requireTick: Boolean = false,
) {
    var ticked by remember { mutableStateOf(!requireTick) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text)
                if (requireTick) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ticked, onCheckedChange = { ticked = it })
                    Text("I understand this can't be undone", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ticked, onClick = onConfirm) {
                Text(confirmLabel, color = if (danger && ticked) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
