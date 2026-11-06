package com.ajay.storyboard.ui.write

import android.webkit.WebView
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.Prompts
import com.ajay.storyboard.capture.CaptureBridge
import com.ajay.storyboard.capture.CaptureEvent
import com.ajay.storyboard.capture.ChatWebView
import com.ajay.storyboard.data.AiService
import com.ajay.storyboard.ai.AiTarget
import com.ajay.storyboard.data.Draft
import com.ajay.storyboard.data.DraftStatus
import com.ajay.storyboard.ui.common.WebLogButton
import com.ajay.storyboard.ui.common.wordCount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private sealed interface CaptureUi {
    data object Idle : CaptureUi
    data class Streaming(val words: Int) : CaptureUi
    data class Done(val draftId: Long, val text: String, val erased: Boolean) : CaptureUi
}

/**
 * The real chat page (DeepSeek, ChatGPT, Claude, Gemini or Grok, as set for "Write") for this book. The capture script copies every reply as it
 * streams; when the reply ends the review panel appears with Add / Refactor / Discard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteScreen(
    app: StoryBoardApp, bookId: Long, newSession: Boolean, onBack: () -> Unit, onRefactor: (Long) -> Unit,
    onReopen: () -> Unit,
) {
    val repo = app.repo
    val target = remember { app.catalog.targetFor(com.ajay.storyboard.data.AiAction.WRITE) }
    val service = remember { target.page ?: AiTarget.Web(AiService.DEEPSEEK).page!! }
    val sourceName = (target as? AiTarget.Web)?.service?.name ?: target.label
    var serviceMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val book by remember(bookId) { repo.books.observe(bookId) }.collectAsState(initial = null)
    val chapters by remember(bookId) { repo.chapters.observeForBook(bookId) }.collectAsState(initial = emptyList())
    val nextNumber = chapters.size + 1

    var progress by remember { mutableIntStateOf(0) }
    var capture by remember { mutableStateOf<CaptureUi>(CaptureUi.Idle) }
    var reading by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var recapRequest by remember { mutableIntStateOf(0) }
    var noteRequest by remember { mutableIntStateOf(0) }
    var discarding by remember { mutableStateOf(false) }

    // streamId -> database id of its draft (insert runs async, updates wait for it)
    val draftIds = remember { mutableMapOf<String, Deferred<Long>>() }

    val bridge = remember {
        CaptureBridge { event ->
            when (event) {
                is CaptureEvent.Started -> {
                    draftIds[event.streamId] = app.appScope.async {
                        repo.drafts.insert(
                            Draft(bookId = bookId, service = sourceName, prompt = event.prompt.ifBlank { null }, content = "")
                        )
                    }
                    capture = CaptureUi.Streaming(0)
                }
                is CaptureEvent.Updated -> {
                    val id = draftIds[event.streamId] ?: return@CaptureBridge
                    app.appScope.launch { repo.drafts.updateContent(id.await(), event.text) }
                    if (capture !is CaptureUi.Done) capture = CaptureUi.Streaming(wordCount(event.text))
                }
                is CaptureEvent.Ended -> {
                    val id = draftIds.remove(event.streamId) ?: CompletableDeferred(-1L)
                    app.appScope.launch {
                        val draftId = id.await()
                        if (draftId < 0) return@launch
                        val d = repo.drafts.get(draftId) ?: return@launch
                        if (event.text.isBlank()) {
                            repo.drafts.setStatus(draftId, DraftStatus.DISCARDED)
                            withContext(Dispatchers.Main) { capture = CaptureUi.Idle }
                            return@launch
                        }
                        repo.drafts.update(d.copy(content = event.text, erased = event.erased, status = DraftStatus.PENDING, updatedAt = System.currentTimeMillis()))
                        withContext(Dispatchers.Main) { capture = CaptureUi.Done(draftId, event.text, event.erased) }
                    }
                }
                is CaptureEvent.Failed -> Unit
                else -> Unit
            }
        }
    }

    val startUrl = remember(book != null) {
        val saved = book?.deepseekChatUrl?.takeIf { book?.chatService == service.key || book?.chatService == service.service?.name }
        if (newSession || saved.isNullOrBlank()) service.homeUrl else saved
    }

    var webViewRef: WebView? = null
    val webView: WebView = remember {
        ChatWebView.create(
            context = context,
            allowedOrigin = service.injectOrigin,
            captureScript = service.script(context),
            bridge = bridge,
            onUrlChanged = { url ->
                if (service.isChatUrl(url)) {
                    app.appScope.launch { repo.books.saveChatUrl(bookId, url, service.key) }
                }
            },
            onProgress = { p ->
                progress = p
                // Sites other than DeepSeek: watch the page for replies (new, edited, regenerated).
                if (p >= 100 && service.service != AiService.DEEPSEEK) webViewRef?.evaluateJavascript("window.__sbWatch && window.__sbWatch()", null)
            },
        )
    }

    webViewRef = webView
    LaunchedEffect(book != null) {
        if (book != null && webView.url == null) {
            ChatWebView.loadWhenSized(webView, startUrl)
            if (startUrl == service.homeUrl) recapRequest++ else if (app.writeNote != null) noteRequest++
        }
    }

    // A note handed over by another screen (roundtable verdict) goes into the message box of an existing chat.
    LaunchedEffect(noteRequest) {
        if (noteRequest == 0) return@LaunchedEffect
        val note = app.writeNote?.also { app.writeNote = null } ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        repeat(40) {
            if (webView.progress >= 100) {
                webView.evaluateJavascript("window.__sbFill && window.__sbFill(${org.json.JSONObject.quote(note)})", null)
                snackbar.showSnackbar("Added the roundtable plan to the message box. Edit it, then send.")
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(500)
        }
    }

    // New chat for a book that already has chapters: pre-fill a recap (bible + last chapters) to edit and send.
    LaunchedEffect(recapRequest) {
        if (recapRequest == 0) return@LaunchedEffect
        val b = repo.books.get(bookId) ?: return@LaunchedEffect
        val all = repo.chapters.listForBook(bookId)
        val note = app.writeNote?.also { app.writeNote = null }
        if (all.isEmpty() && note == null) {
            // nothing to recap: still empty the box, the site may restore an old unsent draft
            repeat(40) {
                if (webView.progress >= 100 && webView.url?.startsWith(service.origin) == true) {
                    webView.evaluateJavascript("window.__sbClearBox && window.__sbClearBox()", null)
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(500)
            }
            return@LaunchedEffect
        }
        val recap = listOfNotNull(
            if (all.isNotEmpty()) Prompts.recap(b.title, repo.bible.listForBook(bookId), all) else null,
            note?.let { "Plan for the next chapter (from an AI roundtable):\n$it" },
        ).joinToString("\n\n")
        kotlinx.coroutines.delay(1500)
        repeat(40) {
            if (webView.progress >= 100 && webView.url?.startsWith(service.origin) == true) {
                webView.evaluateJavascript("window.__sbFill && window.__sbFill(${org.json.JSONObject.quote(recap)})", null)
                snackbar.showSnackbar("Adding a recap of the story to the message box. Edit it, add your chapter instructions, then send.")
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(500)
        }
    }

    LaunchedEffect(capture) {
        webView.keepScreenOn = app.settings.keepScreenOnWhileWriting && capture is CaptureUi.Streaming
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            // Replies still streaming when you leave stay reviewable under Captured replies.
            draftIds.values.forEach { d ->
                app.appScope.launch { repo.drafts.setStatus(d.await(), DraftStatus.PENDING) }
            }
            draftIds.clear()
            webView.stopLoading()
            webView.destroy()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Next: Chapter $nextNumber", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        TextButton(onClick = { serviceMenu = true }) { Text(service.label) }
                        DropdownMenu(expanded = serviceMenu, onDismissRequest = { serviceMenu = false }) {
                            Text("Write with", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            app.catalog.forText().forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    trailingIcon = { if (t.id == target.id) Icon(Icons.Filled.Check, null) },
                                    onClick = {
                                        serviceMenu = false
                                        if (t.id != target.id) { app.settings.setTargetFor(com.ajay.storyboard.data.AiAction.WRITE, t.id); onReopen() }
                                    },
                                )
                            }
                        }
                    }

                    IconButton(onClick = {
                        webView.loadUrl(service.homeUrl)
                        recapRequest++
                        if (chapters.isEmpty()) scope.launch { snackbar.showSnackbar("New ${service.label} chat. It will be linked to this book once you send a message.") }
                    }) { Icon(Icons.Filled.Add, "New chat") }
                    Box {
                        IconButton(onClick = { pageMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = pageMenu, onDismissRequest = { pageMenu = false }) {
                            DropdownMenuItem(text = { Text("Reload page") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { pageMenu = false; webView.reload() })
                            DropdownMenuItem(text = { Text("Web log (troubleshooting)") }, leadingIcon = { Icon(Icons.Outlined.BugReport, null) },
                                onClick = { pageMenu = false; showLog = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth().imePadding())
            when (val c = capture) {
                CaptureUi.Idle -> Unit
                is CaptureUi.Streaming -> Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Capturing reply… ${c.words} words saved", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is CaptureUi.Done -> Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reply captured, ${wordCount(c.text)} words", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        }
                        if (c.erased) {
                            Spacer(Modifier.height(4.dp))
                            RecoveredChip()
                        }
                        Text(
                            c.text.take(220),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        DraftActions(
                            nextChapterNumber = nextNumber,
                            onRead = { reading = true },
                            onDiscard = { discarding = true },
                            onRefactor = { onRefactor(c.draftId) },
                            onAdd = { adding = true },
                        )
                    }
                }
            }
        }
    }

    if (showLog) com.ajay.storyboard.ui.common.WebLogSheet(onClose = { showLog = false }, onResetDone = { webView.loadUrl(service.homeUrl) })
    val done = capture as? CaptureUi.Done
    if (reading && done != null) ReadReplyDialog(done.text, done.erased) { reading = false }
    if (discarding && done != null) {
        ConfirmDiscardDialog(
            onConfirm = {
                discarding = false
                scope.launch {
                    repo.drafts.setStatus(done.draftId, DraftStatus.DISCARDED)
                    capture = CaptureUi.Idle
                }
            },
            onDismiss = { discarding = false },
        )
    }
    if (adding && done != null) {
        AddToBookDialog(
            text = done.text,
            nextChapterNumber = nextNumber,
            onConfirm = { title, content ->
                adding = false
                scope.launch {
                    repo.addDraftToBook(done.draftId, title, content)
                    capture = CaptureUi.Idle
                    snackbar.showSnackbar("Added as Chapter $nextNumber")
                }
            },
            onDismiss = { adding = false },
        )
    }
}
