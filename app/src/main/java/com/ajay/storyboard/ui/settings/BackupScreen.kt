package com.ajay.storyboard.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.backup.BackupFile
import com.ajay.storyboard.ui.common.BackTopBar
import com.ajay.storyboard.ui.common.ConfirmDialog
import com.ajay.storyboard.ui.common.relativeTime
import com.ajay.storyboard.ui.gallery.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Automatic backups to a folder you choose, plus manual backup / restore with one file. */
@Composable
fun BackupScreen(app: StoryBoardApp, onBack: () -> Unit) {
    val b = app.backup
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var list by remember { mutableStateOf<List<BackupFile>?>(null) }
    var restoring by remember { mutableStateOf<BackupFile?>(null) }
    var restoreFile by remember { mutableStateOf<android.net.Uri?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) b.setFolder(uri)
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { b.backupToZip(it) } } }
            snackbar.showSnackbar(ok.fold({ "Backup file saved" }, { "Backup failed: ${it.message}" }))
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreFile = uri }

    Scaffold(topBar = { BackTopBar(title = "Backup & restore", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Automatic backup", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Choose a folder: on the phone, or a Google Drive folder (pick Drive in the folder picker). About 2 minutes after each change " +
                    "a backup is written there; the last 10 are kept. Pictures are stored once in an \"images\" folder. API keys and website logins are never backed up.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(b.folderName()?.let { "Folder: $it" } ?: "No folder chosen", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            b.working != null -> b.working!!
                            b.lastError != null -> "Last backup failed: ${b.lastError}"
                            b.lastAt > 0 -> "Last backup ${relativeTime(b.lastAt)}"
                            else -> "No backup yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (b.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(if (b.folder == null) "Choose folder" else "Change folder") }
                        if (b.folder != null) Button(enabled = b.working == null, onClick = {
                            scope.launch { snackbar.showSnackbar(if (b.backupToFolder()) "Backed up" else "Backup failed: ${b.lastError}") }
                        }) { Text("Back up now") }
                    }
                    if (b.folder != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Back up automatically after changes", Modifier.weight(1f))
                        Switch(b.auto, { b.updateAuto(it) })
                    }
                }
            }
            if (b.folder != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Backups in the folder", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { list = b.listBackups() } }) { Text(if (list == null) "Show" else "Refresh") }
                }
                list?.let { l ->
                    if (l.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                    l.forEach { f ->
                        Card(onClick = { restoring = f }, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(f.name.removePrefix("storyboard-backup-").removeSuffix(".json"), style = MaterialTheme.typography.bodyMedium)
                                Text("${relativeTime(f.time)}, ${formatBytes(f.bytes)}, tap to restore", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Manual backup", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("One .zip file with all stories, bibles, AI history, roundtables and pictures. Keep it anywhere, in case the folder backup can't be reached.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = b.working == null, onClick = {
                    saveZip.launch("story-board-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.zip")
                }) { Text("Save backup file") }
                OutlinedButton(enabled = b.working == null, onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Restore from file") }
            }
            if (b.working != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  ${b.working}")
            }
        }
    }

    val confirmText = "Everything in the app (stories, bible, history, roundtables, gallery list) is replaced by the backup. " +
        "API keys and logins stay. A copy of the current data is kept inside the app first."
    restoring?.let { f ->
        ConfirmDialog(
            title = "Restore this backup?", text = confirmText, confirmLabel = "Restore", requireTick = true,
            onConfirm = {
                restoring = null
                scope.launch {
                    val r = runCatching { b.restoreFromFolder(f) }
                    snackbar.showSnackbar(r.fold({ "Restored" }, { "Restore failed: ${it.message}" }))
                }
            },
            onDismiss = { restoring = null },
        )
    }
    restoreFile?.let { uri ->
        ConfirmDialog(
            title = "Restore from this file?", text = confirmText, confirmLabel = "Restore", requireTick = true,
            onConfirm = {
                restoreFile = null
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { b.restoreFromFile(it) } } }
                    snackbar.showSnackbar(r.fold({ "Restored" }, { "Restore failed: ${it.message}" }))
                }
            },
            onDismiss = { restoreFile = null },
        )
    }
}
