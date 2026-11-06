package com.ajay.storyboard.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import com.ajay.storyboard.ui.common.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

/**
 * Puts the background AI pages in the window. Hidden pages sit behind an opaque blocker
 * (below the app screens); a shown page is drawn above everything with a Hide bar.
 * Must be placed in the same Box as the NavHost.
 */
@Composable
fun AiHost(runner: AiRunner) {
    val shown = runner.shown
    runner.views.forEach { (pageKey, web) ->
        key(pageKey) {
            Column(Modifier.fillMaxSize().zIndex(if (shown == pageKey) 20f else -20f)) {
                if (shown == pageKey) {
                    Surface(tonalElevation = 6.dp) {
                        Row(
                            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${runner.pages[pageKey]?.label ?: pageKey} (background page)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Used for AI actions. Log in or solve a check here if asked.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = { runner.show(null) }) { Text("Hide") }
                        }
                    }
                }
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            }
        }
    }
    if (runner.views.isNotEmpty()) {
        // Opaque layer that keeps hidden pages invisible and untouchable during screen transitions.
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(-10f)
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        )
    }
}

/** Progress / error line for an AI action, with a button to show the page. */
@Composable
fun AiStatusCard(runner: AiRunner, targetId: String, onRetry: (() -> Unit)? = null) {
    val st = runner.status[targetId] ?: return
    val failed = st.phase == AiRunner.Phase.FAILED
    if (st.phase == AiRunner.Phase.DONE) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!failed) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f).padding(start = if (failed) 0.dp else 12.dp)) {
                Text("${st.title}: ${st.label}", style = MaterialTheme.typography.titleSmall)
                val detail = when (st.phase) {
                    AiRunner.Phase.OPENING -> "Opening a new chat…"
                    AiRunner.Phase.SENDING -> "Sending…"
                    AiRunner.Phase.WAITING -> st.detail ?: "Waiting for the reply…"
                    AiRunner.Phase.WRITING -> st.detail ?: "Writing… ${st.chars} characters"
                    AiRunner.Phase.FAILED -> if (st.needsLogin) "Please log in to ${st.title}." else (st.error ?: "Failed")
                    AiRunner.Phase.DONE -> ""
                }
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (st.hasPage) TextButton(onClick = { runner.show(targetId) }) { Text(if (st.needsLogin) "Log in" else "Show page") }
            if (failed && onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/**
 * Shown when an AI action fails (error, rate limit, login, bad format): try the same AI again,
 * switch to another AI for this action, or go back.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiErrorPanel(
    runner: AiRunner,
    catalog: AiCatalog,
    targetId: String,
    message: String,
    onRetryWith: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back to the prompt",
    forImages: Boolean = false,
) {
    val label = catalog.label(targetId)
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        AiStatusCard(runner, targetId)
        if (runner.status[targetId]?.phase != AiRunner.Phase.FAILED) Text(message)
        Button(onClick = { onRetryWith(targetId) }, modifier = Modifier.fillMaxWidth()) { Text("Try $label again") }
        Text("Or retry with another AI", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            (if (forImages) catalog.forImages() else catalog.forText()).filter { it.id != targetId }.forEach { t ->
                com.ajay.storyboard.ui.common.OutlinedButton(onClick = { onRetryWith(t.id) }) { Text(t.label) }
            }
        }
        if (onBack != null) TextButton(onClick = onBack) { Text(backLabel) }
    }
}
