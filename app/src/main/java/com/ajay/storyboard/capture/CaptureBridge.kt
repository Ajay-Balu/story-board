package com.ajay.storyboard.capture

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** What the capture script reports while a reply streams. */
sealed interface CaptureEvent {
    val streamId: String
    data class Started(override val streamId: String, val prompt: String) : CaptureEvent
    data class Updated(override val streamId: String, val text: String) : CaptureEvent
    data class Ended(override val streamId: String, val text: String, val erased: Boolean, val meta: String) : CaptureEvent
    data class Failed(override val streamId: String, val code: String, val message: String) : CaptureEvent
    /** Pictures the page produced: http(s) URLs (downloaded by the app) or data: URLs. */
    data class Images(override val streamId: String, val urls: List<String>) : CaptureEvent
    /** Teach mode: the user tapped an element; [json] has the selector, frame host and a preview. */
    data class Taught(override val streamId: String, val json: String) : CaptureEvent
}

/**
 * Exposed to the page as `window.StoryBoardBridge`. Methods are called on a WebView binder
 * thread, so events are re-posted to the main thread.
 */
class CaptureBridge(private val onEvent: (CaptureEvent) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onStart(id: String, prompt: String) = post(CaptureEvent.Started(id, prompt))

    @JavascriptInterface
    fun onUpdate(id: String, text: String) = post(CaptureEvent.Updated(id, text))

    @JavascriptInterface
    fun onEnd(id: String, text: String, metaJson: String) {
        val erased = runCatching { JSONObject(metaJson).optBoolean("erased") }.getOrDefault(false)
        post(CaptureEvent.Ended(id, text, erased, metaJson))
    }

    @JavascriptInterface
    fun onError(id: String, code: String, message: String) = post(CaptureEvent.Failed(id, code, message))

    @JavascriptInterface
    fun onImages(id: String, urlsJson: String) {
        val urls = runCatching {
            val a = org.json.JSONArray(urlsJson)
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
        post(CaptureEvent.Images(id, urls))
    }

    @JavascriptInterface
    fun onTeach(step: String, json: String) = post(CaptureEvent.Taught(step, json))

    private fun post(e: CaptureEvent) {
        main.post { onEvent(e) }
    }

    companion object {
        const val JS_NAME = "StoryBoardBridge"
    }
}
