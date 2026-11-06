package com.ajay.storyboard.ui.settings

import androidx.compose.foundation.layout.height

import com.ajay.storyboard.ui.common.BackTopBar
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ajay.storyboard.capture.ChatWebView
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.AiService
import com.ajay.storyboard.data.Settings
import com.ajay.storyboard.ui.common.WebLogButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, catalog: com.ajay.storyboard.ai.AiCatalog, onBack: () -> Unit, onAccounts: () -> Unit, onBackup: () -> Unit = {}) {
    Scaffold(
        topBar = {
            BackTopBar(
                title = "Settings",
                onBack = onBack,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Default AI for each action")
            AiAction.entries.forEach { action ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(action.label, style = MaterialTheme.typography.bodyLarge)
                        Text(action.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    com.ajay.storyboard.ui.common.TargetPicker(catalog, settings.targetFor(action), images = action.needsImages) { settings.setTargetFor(action, it.id) }
                }
            }
            Text(
                "Web chats and taught sites open a new chat each time. API keys and sites are added in AI accounts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
            Section("AI accounts")
            OutlinedButton(onClick = onAccounts, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Open AI accounts (logins, API keys, taught sites)")
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
            Section("Appearance")
            Text("Theme for the app and the chat pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                com.ajay.storyboard.data.ThemeMode.entries.forEach { m ->
                    androidx.compose.material3.FilterChip(
                        selected = settings.themeMode == m,
                        onClick = { settings.updateTheme(m) },
                        label = { Text(m.label) },
                    )
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Wallpaper colors")
                    Text("Use your phone's wallpaper colors instead of Story-Board's own slate and ink colours",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.wallpaperColors, onCheckedChange = settings::updateWallpaperColors)
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
            Section("Writing")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen on while a reply streams", Modifier.weight(1f))
                Switch(checked = settings.keepScreenOnWhileWriting, onCheckedChange = settings::updateKeepScreenOn)
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
            Section("Backup")
            OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Open Backup & restore")
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    com.ajay.storyboard.ui.common.SectionHead(title, Modifier.padding(top = 8.dp, bottom = 4.dp))
}

/** Plain browser page for logging in to a service; cookies are shared with the writing pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(service: AiService, onBack: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    val web: WebView = remember {
        ChatWebView.create(
            context = context,
            allowedOrigin = service.homeUrl.trimEnd('/'),
            // no capture script on the login page, so a login problem can't be caused by it
            captureScript = null,
            bridge = null,
            onUrlChanged = {},
            onProgress = { progress = it },
        ).also { ChatWebView.loadWhenSized(it, service.homeUrl) }
    }
    DisposableEffect(Unit) { onDispose { web.destroy() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(service.label) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    WebLogButton(onResetDone = { web.loadUrl(service.homeUrl) })
                    IconButton(onClick = { web.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                    TextButton(onClick = onBack) { Text("Done") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        }
    }
}
