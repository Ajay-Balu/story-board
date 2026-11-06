package com.ajay.storyboard.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import com.ajay.storyboard.ui.common.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiTarget
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.AiService
import com.ajay.storyboard.data.CustomSite
import com.ajay.storyboard.security.KeyVault
import com.ajay.storyboard.ui.common.BackTopBar
import kotlinx.coroutines.launch

/**
 * Every AI the app can use, in three groups:
 *  - web chats with your login (open to log in),
 *  - API keys (OpenAI, Gemini, Claude),
 *  - sites you taught the app to use.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(
    app: StoryBoardApp,
    onBack: () -> Unit,
    onOpen: (AiService) -> Unit,
    onEditKey: (Long) -> Unit,
    onTeach: (Long) -> Unit,
) {
    val settings = app.settings
    val profiles by app.catalog.profiles.collectAsState()
    val sites by app.catalog.sites.collectAsState()
    var addingSite by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    @Composable
    fun UsedFor(id: String) {
        val usedFor = AiAction.entries.filter { settings.targetFor(it) == id }
        if (usedFor.isEmpty()) Text("Not used for any action", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Used for:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
            usedFor.forEach { AssistChip(onClick = {}, label = { Text(it.label) }) }
        }
    }

    Scaffold(topBar = { BackTopBar(title = "AI accounts", onBack = onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header("Web chats", "Each runs as a web page inside this app with your login. Log in once per site; logins never leave the app.") }
            items(AiService.entries) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.label, style = MaterialTheme.typography.titleMedium)
                                Text(s.origin.removePrefix("https://") + if (s in AiTarget.IMAGE_SERVICES) ", text + pictures" else ", text",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledTonalButton(onClick = { onOpen(s) }) { Text("Open") }
                        }
                        UsedFor("web:${s.name}")
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Header("API keys", "Your own keys for OpenAI, Gemini or Claude. Stored encrypted on this phone, never backed up.", Modifier.weight(1f))
                }
            }
            items(profiles, key = { "p${it.id}" }) { p ->
                Card(onClick = { onEditKey(p.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${p.providerEnum.label}, ${p.model}" + (if (p.imageModel.isNotBlank()) ", pictures: ${p.imageModel}" else "") +
                                ", key ${KeyVault.hint(p.keyEncrypted)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UsedFor("api:${p.id}")
                    }
                }
            }
            item {
                OutlinedButton(onClick = { onEditKey(-1L) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text("  Add an API key")
                }
            }

            item { Header("Taught sites", "Any chat or picture website: open it, tap its message box, send button and reply once, and the app can use it.", Modifier.padding(top = 12.dp)) }
            items(sites, key = { "s${it.id}" }) { s ->
                val taught = s.taughtAt > 0
                Card(onClick = { onTeach(s.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            s.url.removePrefix("https://") + ", " + listOfNotNull("text".takeIf { s.forText }, "pictures".takeIf { s.forImages }).joinToString(" + ") +
                                if (taught) "" else ", not taught yet",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (taught) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        UsedFor("site:${s.id}")
                    }
                }
            }
            item {
                OutlinedButton(onClick = { addingSite = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text("  Add a website")
                }
            }
        }
    }

    if (addingSite) {
        AddSiteDialog(
            onDismiss = { addingSite = false },
            onAdd = { site ->
                addingSite = false
                scope.launch { onTeach(app.repo.aiConfig.insertSite(site)) }
            },
        )
    }
}

@Composable
private fun Header(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        com.ajay.storyboard.ui.common.SectionHead(title)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun AddSiteDialog(onDismiss: () -> Unit, onAdd: (CustomSite) -> Unit, initial: CustomSite? = null) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url ?: "https://") }
    var text by remember { mutableStateOf(initial?.forText ?: true) }
    var images by remember { mutableStateOf(initial?.forImages ?: false) }
    val cleanUrl = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
    val valid = name.isNotBlank() && Regex("^https?://[^/\\s]+\\.[^/\\s]+.*").matches(cleanUrl) && (text || images)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add a website" else "Edit website") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Address of the chat / generator page") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { text = !text }) {
                    Checkbox(text, { text = it }); Text("Writes text")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { images = !images }) {
                    Checkbox(images, { images = it }); Text("Makes pictures")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onAdd((initial ?: CustomSite(name = "", url = "", forText = true, forImages = false))
                    .copy(name = name.trim(), url = cleanUrl, forText = text, forImages = images))
            }) { Text(if (initial == null) "Next: teach it" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
