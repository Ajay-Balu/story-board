package com.ajay.storyboard.ui.common

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajay.storyboard.capture.WebLog

/** Top-bar button that shows the web log, lets you copy it, and can reset site logins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLogButton(onResetDone: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.BugReport, "Web log") }
    if (open) WebLogSheet(onClose = { open = false }, onResetDone = onResetDone)
}

/** The web log sheet itself, so it can also be opened from a menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLogSheet(onClose: () -> Unit, onResetDone: () -> Unit = {}) {
    var open by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(open, confirmReset) { if (!open && !confirmReset) onClose() }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Web log", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Copy this and send it to whoever is fixing the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(WebLog.text())) }) { Text("Copy") }
                    OutlinedButton(onClick = { WebLog.clear() }) { Text("Clear") }
                    OutlinedButton(onClick = { confirmReset = true }) { Text("Reset logins") }
                }
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            if (WebLog.lines.isEmpty()) "(empty)" else WebLog.lines.joinToString("\n"),
                            fontFamily = com.ajay.storyboard.ui.theme.AppFonts.mono, fontSize = 11.sp, lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset logins?") },
            text = { Text("Clears cookies and site storage for all AI sites inside this app. You'll need to log in again. Your books are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    WebLog.add("reset", "cookies and site storage cleared")
                    confirmReset = false
                    open = false
                    onResetDone()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
