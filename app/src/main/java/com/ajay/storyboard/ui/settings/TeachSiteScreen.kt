package com.ajay.storyboard.ui.settings

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiTarget
import com.ajay.storyboard.capture.CaptureBridge
import com.ajay.storyboard.capture.CaptureEvent
import com.ajay.storyboard.capture.ChatWebView
import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.CustomSite
import com.ajay.storyboard.ui.common.BackTopBar
import com.ajay.storyboard.ui.common.ConfirmDialog
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The things the app needs to know about a site, in teaching order. */
private enum class TeachStep(val label: String, val how: String, val optional: Boolean = false) {
    PREPARE("Before typing", "Optional: a button the site needs pressed before each message, like \"New Image\" or a mode switch. Tap it.", optional = true),
    BOX("Message box", "Tap the box where you type."),
    SEND("Send button", "Tap the button that sends (or generates). If the site sends with Enter you can skip this.", optional = true),
    REPLY("Reply", "Send a short message on the page first, then tap Teach and tap the AI's answer."),
    IMAGE("Picture", "Optional: after a picture appears, tap it so only pictures like it are taken. Without it, any new picture is taken.", optional = true),
    STOP("Stop button", "Optional: while a reply is being written, tap the Stop button. Helps tell when the reply is finished.", optional = true),
}

private val TeachStep.key get() = name.lowercase()

/**
 * Teach a site: the page opens here; for each step you tap the element on the page and the app
 * remembers it (as a CSS selector, plus which frame it lives in). Test sends a real message
 * (or asks for a picture) on this page with what was taught.
 */
@Composable
fun TeachSiteScreen(app: StoryBoardApp, siteId: Long, onBack: () -> Unit) {
    val repo = app.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf<CustomSite?>(null) }
    val config = remember { mutableStateOf(JSONObject()) }
    var teaching by remember { mutableStateOf<TeachStep?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editSite by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(true) }

    LaunchedEffect(siteId) {
        val s = repo.aiConfig.site(siteId) ?: return@LaunchedEffect onBack()
        site = s
        config.value = runCatching { JSONObject(s.config) }.getOrDefault(JSONObject())
    }

    var webRef by remember { mutableStateOf<WebView?>(null) }
    fun js(code: String, then: (String) -> Unit = {}) { webRef?.evaluateJavascript(code) { then(it) } }
    fun pushConfig() = js("window.__sbSetConfig && window.__sbSetConfig(${config.value})")

    val bridge = remember {
        CaptureBridge { e ->
            when (e) {
                is CaptureEvent.Taught -> {
                    val step = TeachStep.entries.firstOrNull { it.key == e.streamId } ?: return@CaptureBridge
                    val j = runCatching { JSONObject(e.json) }.getOrNull() ?: return@CaptureBridge
                    val c = JSONObject(config.value.toString())
                    c.put(step.key, j.optString("selector"))
                    if (step == TeachStep.BOX) {
                        if (j.optBoolean("top")) c.remove("frame") else c.put("frame", j.optString("frame"))
                    }
                    config.value = c
                    dirty = true
                    teaching = null
                    panelOpen = true
                    info = "${step.label}: ${j.optString("selector")}" +
                        (j.optString("text").takeIf { it.isNotBlank() }?.let { "\n“${it.take(80)}”" } ?: "") +
                        (if (j.optInt("matches") > 1) "\n(${j.optInt("matches")} matches on the page)" else "")
                    pushConfig()
                }
                is CaptureEvent.Ended -> if (testing != null) {
                    testing = null
                    testResult = true to "Works. Reply read from the page:\n${e.text.take(300)}"
                }
                is CaptureEvent.Updated -> if (testing != null) testing = "Reading the reply… ${e.text.length} characters"
                is CaptureEvent.Failed -> if (testing != null) {
                    testing = null
                    testResult = false to "${e.code}: ${e.message}"
                }
                is CaptureEvent.Images -> if (testing != null) {
                    testing = "Saving ${e.urls.size} pictures…"
                    val s = site
                    scope.launch {
                        val saved = app.ai.saveUrls(e.urls, s?.url.orEmpty(), null, null, null, "Teach test", s?.let { AiTarget.Site(it) })
                        testing = null
                        testResult = (saved.isNotEmpty()) to
                            if (saved.isNotEmpty()) "Works. ${saved.size} picture${if (saved.size == 1) "" else "s"} saved to the Gallery."
                            else "Found ${e.urls.size} pictures but couldn't download them."
                    }
                }
                else -> Unit
            }
        }
    }

    val s = site
    if (s == null) {
        Scaffold(topBar = { BackTopBar(title = "Teach a site", onBack = onBack) }) { pad ->
            CircularProgressIndicator(Modifier.padding(pad).padding(24.dp))
        }
        return
    }

    val web = remember(s.id) {
        val target = AiTarget.Site(s)
        ChatWebView.create(
            context = context,
            allowedOrigin = "*",
            captureScript = target.page!!.script(context),
            bridge = bridge,
            onUrlChanged = {},
            onProgress = { progress = it },
        ).also { webRef = it; ChatWebView.loadWhenSized(it, s.url) }
    }
    DisposableEffect(web) { onDispose { runCatching { web.destroy() } } }

    fun save(after: () -> Unit = {}) {
        val current = site ?: return
        scope.launch {
            val updated = current.copy(config = config.value.toString(), taughtAt = if (config.value.has("box")) System.currentTimeMillis() else current.taughtAt)
            repo.aiConfig.updateSite(updated)
            site = updated
            dirty = false
            app.runner.drop("site:${updated.id}")   // background page picks up the new selectors
            after()
        }
    }

    // System back keeps what was taught too
    androidx.activity.compose.BackHandler(enabled = dirty) { save(onBack) }

    val steps = TeachStep.entries.filter {
        when (it) {
            TeachStep.REPLY -> s.forText
            TeachStep.IMAGE -> s.forImages
            else -> true
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(
                title = "Teach: ${s.name}",
                subtitle = s.url.removePrefix("https://"),
                onBack = { if (dirty) save(onBack) else onBack() },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Reload page") }, onClick = { menu = false; web.reload() })
                        DropdownMenuItem(text = { Text("Edit name / address") }, onClick = { menu = false; editSite = true })
                        DropdownMenuItem(text = { Text("Forget what was taught") }, onClick = {
                            menu = false; config.value = JSONObject(); dirty = true; pushConfig(); info = null; testResult = null
                        })
                        DropdownMenuItem(text = { Text("Delete site", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            // the banner floats over the page so the page doesn't move when teaching starts
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                teaching?.let { t ->
                    Surface(modifier = Modifier.align(Alignment.TopCenter), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (t == TeachStep.PREPARE) "Tap the button to press before typing" else "Tap the ${t.label.lowercase()} on the page", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { teaching = null; panelOpen = true; js("window.__sbTeach && window.__sbTeach(null)") }) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(12.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Log in on the page if needed, then teach each part.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { panelOpen = !panelOpen }) { Text(if (panelOpen) "Hide" else "Show") }
                    }
                    if (panelOpen) {
                        steps.forEach { step ->
                            val done = config.value.has(step.key)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(step.label + if (step.optional) " (optional)" else "", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (done) config.value.optString(step.key) else step.how,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(onClick = {
                                    teaching = step; info = null; panelOpen = false
                                    js("window.__sbTeach && window.__sbTeach('${step.key}')")
                                }) {
                                    if (done) Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                                    Text(if (done) " Re-teach" else "Teach")
                                }
                            }
                        }
                        info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        testing?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        testResult?.let { (ok, msg) ->
                            Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                                Text(msg, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.forText) OutlinedButton(enabled = testing == null && config.value.has("reply"), onClick = {
                                testResult = null; testing = "Sending a test message…"; pushConfig()
                                js("window.__sbRun('teach-test-${System.currentTimeMillis()}', 'Reply with just these words: Story-Board test OK')")
                            }) { Text("Test text") }
                            if (s.forImages) OutlinedButton(enabled = testing == null, onClick = {
                                testResult = null; testing = "Asking for a picture… (can take a minute)"; pushConfig()
                                js("window.__sbImage('teach-img-${System.currentTimeMillis()}', 'A small red umbrella on a wooden table, simple watercolor illustration')")
                            }) { Text("Test picture") }
                            Button(enabled = dirty, onClick = { save() }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }

    if (editSite) {
        AddSiteDialog(
            initial = s,
            onDismiss = { editSite = false },
            onAdd = { updated ->
                editSite = false
                scope.launch {
                    repo.aiConfig.updateSite(updated.copy(config = config.value.toString()))
                    site = updated.copy(config = config.value.toString())
                    app.runner.drop("site:${updated.id}")
                    if (updated.url != s.url) web.loadUrl(updated.url)
                }
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${s.name}?",
            text = "Actions that use it go back to their default AI. Pictures it made stay in the Gallery.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    AiAction.entries.filter { app.settings.targetFor(it) == "site:${s.id}" }.forEach { app.settings.setTargetFor(it, it.defaultTarget) }
                    app.runner.drop("site:${s.id}")
                    repo.aiConfig.deleteSite(s)
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
