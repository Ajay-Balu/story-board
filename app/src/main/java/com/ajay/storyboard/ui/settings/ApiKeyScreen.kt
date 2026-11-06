package com.ajay.storyboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiException
import com.ajay.storyboard.ai.ApiClient
import com.ajay.storyboard.ai.ChatMsg
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.ApiProfile
import com.ajay.storyboard.data.ApiProvider
import com.ajay.storyboard.security.KeyVault
import com.ajay.storyboard.ui.common.BackTopBar
import com.ajay.storyboard.ui.common.ConfirmDialog
import kotlinx.coroutines.launch

private val SUGGESTED = mapOf(
    ApiProvider.OPENAI to ("gpt-4.1-mini" to "gpt-image-1"),
    ApiProvider.GEMINI to ("gemini-2.5-flash" to "gemini-2.5-flash-image"),
    ApiProvider.CLAUDE to ("claude-sonnet-4-5" to ""),
)

/** Add or edit one API key entry: provider, name, key, model (+ picture model), Test, Delete. */
@Composable
fun ApiKeyScreen(app: StoryBoardApp, profileId: Long?, onBack: () -> Unit) {
    val repo = app.repo
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(profileId == null) }
    var existing by remember { mutableStateOf<ApiProfile?>(null) }
    var provider by remember { mutableStateOf(ApiProvider.OPENAI) }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }          // blank on edit = keep the saved key
    var model by remember { mutableStateOf(SUGGESTED.getValue(ApiProvider.OPENAI).first) }
    var imageModel by remember { mutableStateOf(SUGGESTED.getValue(ApiProvider.OPENAI).second) }
    var baseUrl by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (profileId == null) return@LaunchedEffect
        repo.aiConfig.profile(profileId)?.let { p ->
            existing = p; provider = p.providerEnum; name = p.name; model = p.model; imageModel = p.imageModel; baseUrl = p.baseUrl
        }
        loaded = true
    }

    fun currentKey(): String = key.trim().ifBlank { existing?.let { runCatching { KeyVault.decrypt(it.keyEncrypted) }.getOrDefault("") }.orEmpty() }

    fun draft(): ApiProfile = (existing ?: ApiProfile(provider = provider.name, name = "", model = "", keyEncrypted = "")).copy(
        provider = provider.name,
        name = name.trim().ifBlank { "My ${provider.label}" },
        model = model.trim(),
        imageModel = if (provider.canMakeImages) imageModel.trim() else "",
        baseUrl = baseUrl.trim(),
        keyEncrypted = if (key.isNotBlank()) KeyVault.encrypt(key.trim()) else existing?.keyEncrypted.orEmpty(),
    )

    val canSave = loaded && model.isNotBlank() && (key.isNotBlank() || existing != null)

    Scaffold(topBar = { BackTopBar(title = if (existing == null) "Add API key" else "Edit API key", onBack = onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApiProvider.entries.forEach { p ->
                    FilterChip(selected = provider == p, onClick = {
                        if (provider != p) {
                            provider = p; models = emptyList(); result = null
                            model = SUGGESTED.getValue(p).first; imageModel = SUGGESTED.getValue(p).second
                        }
                    }, label = { Text(p.label) })
                }
            }
            if (!provider.canMakeImages) Text("${provider.label} writes text only (no pictures).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. My ${provider.label})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                key, { key = it.trim(); result = null },
                label = { Text(if (existing != null) "API key (saved: ${KeyVault.hint(existing!!.keyEncrypted)}; type to replace)" else "API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            ModelField("Model for text", model, models, { model = it })
            if (provider.canMakeImages) ModelField("Model for pictures (empty = no pictures)", imageModel, models, { imageModel = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = busy == null && currentKey().isNotBlank(), onClick = {
                    busy = "Loading models…"; result = null
                    scope.launch {
                        try {
                            models = ApiClient.listModels(provider, baseUrl, currentKey())
                            result = true to "${models.size} models found. Pick one from the ▾ lists."
                        } catch (e: Exception) { result = false to (e.message ?: "Could not load models") }
                        busy = null
                    }
                }) { Text("Load models") }
                OutlinedButton(enabled = busy == null && canSave, onClick = {
                    busy = "Testing…"; result = null
                    scope.launch {
                        try {
                            val reply = ApiClient.chat(draft(), null, listOf(ChatMsg("user", "Reply with just: OK")))
                            result = true to "Works. ${provider.label} replied: ${reply.trim().take(80)}"
                        } catch (e: AiException) { result = false to (e.message ?: e.code) }
                        catch (e: Exception) { result = false to (e.message ?: e.javaClass.simpleName) }
                        busy = null
                    }
                }) { Text("Test") }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp)); Text(it) } }
            result?.let { (ok, msg) ->
                Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                    Text(msg, Modifier.padding(12.dp))
                }
            }
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Server address (optional, for compatible services)") },
                placeholder = { Text(provider.defaultBaseUrl) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Button(enabled = canSave, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val p = draft()
                    if (existing == null) repo.aiConfig.insertProfile(p) else repo.aiConfig.updateProfile(p)
                    onBack()
                }
            }) { Text("Save") }
            if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete this key", color = MaterialTheme.colorScheme.error) }
            Text(
                "The key is encrypted with this phone's keystore. It is never included in backups or shared files.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${existing?.name}?",
            text = "Actions that use it go back to their default AI.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    existing?.let { p ->
                        AiAction.entries.filter { app.settings.targetFor(it) == "api:${p.id}" }
                            .forEach { app.settings.setTargetFor(it, it.defaultTarget) }
                        repo.aiConfig.deleteProfile(p)
                    }
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ModelField(label: String, value: String, models: List<String>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = if (models.isNotEmpty()) ({ IconButton(onClick = { open = true }) { Icon(Icons.Filled.ArrowDropDown, "Choose") } }) else null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { open = false; onChange(m) }) }
        }
    }
}
