package com.ajay.storyboard.ai

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ajay.storyboard.capture.CaptureBridge
import com.ajay.storyboard.capture.CaptureEvent
import com.ajay.storyboard.capture.ChatWebView
import com.ajay.storyboard.capture.WebLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class AiException(val code: String, message: String) : Exception(message) {
    val needsLogin get() = code == "LOGIN_REQUIRED"
}

data class AiReply(val text: String, val erased: Boolean)

/**
 * Runs AI actions in hidden, app-wide WebViews (one per chat page: built-in sites and taught sites)
 * using your own logins. Each job opens a fresh chat, types the prompt, sends it and waits for the
 * full reply (or the pictures). A page can be brought to the front (to log in or solve a check) with [show].
 * API keys don't use pages; [AiGateway] reports their progress through the same [status] map.
 */
class AiRunner {
    enum class Phase { OPENING, SENDING, WAITING, WRITING, DONE, FAILED }

    data class Status(
        /** Target id ("web:CHATGPT", "api:3", "site:2"). */
        val key: String,
        val title: String,
        val label: String,
        val phase: Phase,
        val chars: Int = 0,
        val error: String? = null,
        val needsLogin: Boolean = false,
        val hasPage: Boolean = true,
        val detail: String? = null,
    )

    /** Latest job status per target id (for progress UI). */
    val status = mutableStateMapOf<String, Status>()

    /** Target whose page is currently shown on top of the app (null = all hidden). */
    var shown by mutableStateOf<String?>(null)
        private set

    /** WebViews created so far, by target id; [AiHost] puts them in the window. */
    val views = mutableStateMapOf<String, WebView>()
    /** Page details for each WebView (title for the "Show page" bar). */
    val pages = mutableStateMapOf<String, PageSpec>()

    private var context: Context? = null
    private val locks = mutableMapOf<String, Mutex>()
    private val results = mutableMapOf<String, CompletableDeferred<AiReply>>()
    private val imageResults = mutableMapOf<String, CompletableDeferred<List<String>>>()

    /** Optional listener for teach mode taps (step, json). */
    var onTaught: ((String, String, String) -> Unit)? = null

    fun attach(activityContext: Context) { context = activityContext }

    fun detach() {
        views.values.forEach { runCatching { it.destroy() } }
        views.clear()
        context = null
    }

    fun show(key: String?) {
        shown = key
        views.forEach { (k, v) -> setTouchable(v, k == key) }
    }

    fun lock(key: String): Mutex = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
    fun isBusy(key: String) = lock(key).isLocked

    /** Sends [prompt] in a new chat on [page] and returns the full reply. */
    suspend fun ask(page: PageSpec, prompt: String, label: String, timeoutMs: Long = 8 * 60_000L): AiReply {
        val result = CompletableDeferred<AiReply>()
        return job(page, label, prompt, "__sbRun", result, results, timeoutMs) { status(page, label, Phase.DONE, chars = it.text.length) }
    }

    /** Asks [page] for a picture; returns the picture addresses (http(s) or data: URLs). */
    suspend fun images(page: PageSpec, prompt: String, label: String, timeoutMs: Long = 6 * 60_000L): List<String> {
        val result = CompletableDeferred<List<String>>()
        return job(page, label, prompt, "__sbImage", result, imageResults, timeoutMs) { status(page, label, Phase.DONE, detail = "${it.size} pictures") }
    }

    private fun status(page: PageSpec, label: String, phase: Phase, chars: Int = 0, error: String? = null, needsLogin: Boolean = false, detail: String? = null) =
        Status(page.key, page.label, label, phase, chars, error, needsLogin, true, detail)

    private suspend fun <T> job(
        page: PageSpec, label: String, prompt: String, fn: String,
        result: CompletableDeferred<T>, waiting: MutableMap<String, CompletableDeferred<T>>,
        timeoutMs: Long, done: (T) -> Status,
    ): T = lock(page.key).withLock {
        withContext(Dispatchers.Main) {
            val key = page.key
            val web = viewFor(page)
            status[key] = status(page, label, Phase.OPENING)
            WebLog.add("runner", "${page.label}: $label (${prompt.length} chars)")
            openFresh(web, page, label)

            collected.remove(key); pending.remove(key)?.let { main.removeCallbacks(it) }
            waiting[key] = result
            status[key] = status(page, label, Phase.SENDING)
            val id = "job-${System.currentTimeMillis()}"
            val js = "(window.$fn ? (window.$fn(${JSONObject.quote(id)}, ${JSONObject.quote(prompt)}), 'ok') : 'missing')"
            web.evaluateJavascript(js) { r ->
                if (r.contains("missing")) result.completeExceptionally(AiException("NO_SCRIPT", "Runner script not loaded on ${page.label}"))
                else if (status[key]?.phase == Phase.SENDING) status[key] = status(page, label, Phase.WAITING)
            }
            try {
                val value = withTimeout(timeoutMs) { result.await() }
                status[key] = done(value)
                value
            } catch (e: AiException) {
                throw fail(page, label, e)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw fail(page, label, AiException("TIMEOUT", "${page.label} took too long to reply"))
            } finally {
                waiting.remove(key)
            }
        }
    }

    /** Loads the start page (a new chat) and waits until it is ready. */
    private suspend fun openFresh(web: WebView, page: PageSpec, label: String) {
        if (web.url == null) ChatWebView.loadWhenSized(web, page.homeUrl) else web.loadUrl(page.homeUrl)
        delay(1200)
        try {
            withTimeout(60_000) {
                while (web.url?.startsWith(page.origin) != true || web.progress < 100) {
                    val u = web.url.orEmpty()
                    if (LOGIN_HINTS.any { u.contains(it) }) {
                        throw AiException("LOGIN_REQUIRED", "Please log in to ${page.label}")
                    }
                    delay(300)
                }
            }
        } catch (e: AiException) {
            throw fail(page, label, e)
        } catch (e: Exception) {
            throw fail(page, label, AiException("PAGE_TIMEOUT", "${page.label} page did not load"))
        }
        delay(if (page.service == null) 3000 else 1800)
    }

    /** Opens [page] in its background WebView without sending anything (teach mode, logging in). */
    fun open(page: PageSpec): WebView {
        val web = viewFor(page)
        if (web.url == null) ChatWebView.loadWhenSized(web, page.homeUrl)
        return web
    }

    private fun fail(page: PageSpec, label: String, e: AiException): AiException {
        status[page.key] = status(page, label, Phase.FAILED, error = e.message, needsLogin = e.needsLogin)
        WebLog.add("runner", "${page.label} failed: ${e.code} ${e.message}")
        return e
    }

    private val configs = mutableMapOf<String, String?>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val collected = mutableMapOf<String, MutableList<String>>()
    private val pending = mutableMapOf<String, Runnable>()

    private fun viewFor(page: PageSpec): WebView {
        val key = page.key
        views[key]?.let { existing ->
            // A re-taught site needs its new selectors injected: rebuild its page.
            if (configs[key] == page.config && pages[key]?.homeUrl == page.homeUrl) return existing
            runCatching { existing.destroy() }
            views.remove(key)
        }
        val ctx = context ?: throw AiException("NO_WINDOW", "App window not ready")
        val bridge = CaptureBridge { e -> onEvent(key, e) }
        val web = ChatWebView.create(
            context = ctx,
            allowedOrigin = page.injectOrigin,
            captureScript = page.script(ctx),
            bridge = bridge,
            onUrlChanged = {},
            onProgress = {},
        )
        setTouchable(web, shown == key)
        configs[key] = page.config
        pages[key] = page
        views[key] = web
        return web
    }

    /** Forgets a page (site deleted). */
    fun drop(key: String) {
        views.remove(key)?.let { runCatching { it.destroy() } }
        pages.remove(key); status.remove(key); configs.remove(key)
        if (shown == key) show(null)
    }

    private fun onEvent(key: String, e: CaptureEvent) {
        if (e is CaptureEvent.Taught) { onTaught?.invoke(key, e.streamId, e.json); return }
        val current = status[key] ?: return
        val reply = results[key]
        val pics = imageResults[key]
        when (e) {
            is CaptureEvent.Started -> status[key] = current.copy(phase = Phase.WRITING)
            is CaptureEvent.Updated -> status[key] =
                if (pics != null) current.copy(phase = Phase.WRITING, detail = e.text.take(60))
                else current.copy(phase = Phase.WRITING, chars = e.text.length)
            is CaptureEvent.Ended -> {
                if (reply == null) return
                if (e.text.isBlank()) reply.completeExceptionally(AiException("EMPTY", "${current.title} returned an empty reply"))
                else reply.complete(AiReply(e.text, e.erased))
            }
            is CaptureEvent.Images -> {
                if (pics == null || pics.isCompleted) return
                // Pictures can arrive from several frames (one per picture): collect for a few seconds.
                val list = collected.getOrPut(key) { mutableListOf() }
                e.urls.forEach { if (it !in list) list += it }
                status[key] = current.copy(phase = Phase.WRITING, detail = "${list.size} picture${if (list.size == 1) "" else "s"} so far…")
                pending[key]?.let { main.removeCallbacks(it) }
                val finish = Runnable {
                    pending.remove(key)
                    val all = collected.remove(key).orEmpty()
                    views[key]?.evaluateJavascript("window.__sbImageDone && window.__sbImageDone(${JSONObject.quote(e.streamId)})", null)
                    if (all.isEmpty()) pics.completeExceptionally(AiException("NO_IMAGE", "${current.title} showed no picture"))
                    else pics.complete(all)
                }
                pending[key] = finish
                // taught sites may draw pictures one after another (Perchance): wait longer for the next one
                main.postDelayed(finish, if (pages[key]?.service == null) 25_000L else 5_000L)
            }
            is CaptureEvent.Failed -> {
                val ex = AiException(e.code, if (e.code == "NO_IMAGE") "${current.title} replied without a picture: ${e.message}" else e.message)
                reply?.completeExceptionally(ex)
                pics?.completeExceptionally(ex)
            }
            is CaptureEvent.Taught -> Unit
        }
    }

    /** Runs a script in the page of [key] (teach mode). */
    fun eval(key: String, js: String, then: ((String) -> Unit)? = null) {
        views[key]?.evaluateJavascript(js) { then?.invoke(it) }
    }

    companion object {
        val LOGIN_HINTS = listOf("accounts.x.ai", "sign_in", "/login", "accounts.google.com", "auth.openai.com", "auth0.openai.com")
    }

    private fun setTouchable(web: WebView, touchable: Boolean) {
        if (touchable) web.setOnTouchListener(null)
        else web.setOnTouchListener { _, ev -> ev.action != MotionEvent.ACTION_CANCEL }
    }
}
