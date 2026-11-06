package com.ajay.storyboard.ai

import android.util.Base64
import com.ajay.storyboard.capture.WebLog
import com.ajay.storyboard.data.ApiProfile
import com.ajay.storyboard.data.ApiProvider
import com.ajay.storyboard.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ChatMsg(val role: String, val content: String)   // role: "user" | "assistant"
data class ImageData(val bytes: ByteArray, val mime: String)

/**
 * Minimal clients for the OpenAI, Gemini and Claude APIs (no extra libraries).
 * Text is streamed; [onDelta] receives the full text so far.
 */
object ApiClient {

    suspend fun chat(
        profile: ApiProfile,
        system: String?,
        messages: List<ChatMsg>,
        onDelta: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val key = KeyVault.decrypt(profile.keyEncrypted)
        when (profile.providerEnum) {
            ApiProvider.OPENAI -> openAiChat(profile, key, system, messages, onDelta)
            ApiProvider.CLAUDE -> claudeChat(profile, key, system, messages, onDelta)
            ApiProvider.GEMINI -> geminiChat(profile, key, system, messages, onDelta)
        }
    }

    suspend fun images(profile: ApiProfile, prompt: String): List<ImageData> = withContext(Dispatchers.IO) {
        val key = KeyVault.decrypt(profile.keyEncrypted)
        when (profile.providerEnum) {
            ApiProvider.OPENAI -> openAiImages(profile, key, prompt)
            ApiProvider.GEMINI -> geminiImages(profile, key, prompt)
            ApiProvider.CLAUDE -> throw AiException("NO_IMAGES", "Claude can't create images")
        }
    }

    /** Model names offered by the provider for this key (used by "Load models" and "Test"). */
    suspend fun listModels(provider: ApiProvider, baseUrl: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val base = baseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')
        when (provider) {
            ApiProvider.OPENAI -> {
                val j = JSONObject(get("$base/v1/models", mapOf("Authorization" to "Bearer $key")))
                j.optJSONArray("data").strings { it.optString("id") }.sorted()
            }
            ApiProvider.CLAUDE -> {
                val j = JSONObject(get("$base/v1/models?limit=100", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")))
                j.optJSONArray("data").strings { it.optString("id") }
            }
            ApiProvider.GEMINI -> {
                val j = JSONObject(get("$base/v1beta/models?pageSize=200&key=$key", emptyMap()))
                j.optJSONArray("models").strings { it.optString("name").removePrefix("models/") }
            }
        }
    }

    // ------------------------------------------------------------------ OpenAI

    private suspend fun openAiChat(p: ApiProfile, key: String, system: String?, msgs: List<ChatMsg>, onDelta: (String) -> Unit): String {
        val arr = JSONArray()
        if (!system.isNullOrBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", p.model).put("messages", arr).put("stream", true)
            .put("temperature", p.temperature).put("max_completion_tokens", p.maxTokens)
        val sb = StringBuilder()
        stream("${p.effectiveBaseUrl}/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body) { data ->
            val d = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
            val t = d?.optString("content").orEmpty()
            if (t.isNotEmpty() && t != "null") { sb.append(t); onDelta(sb.toString()) }
        }
        return sb.toString()
    }

    private fun openAiImages(p: ApiProfile, key: String, prompt: String): List<ImageData> {
        val body = JSONObject().put("model", p.imageModel).put("prompt", prompt).put("n", 1)
        val j = JSONObject(post("${p.effectiveBaseUrl}/v1/images/generations", mapOf("Authorization" to "Bearer $key"), body))
        val out = mutableListOf<ImageData>()
        val data = j.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val b64 = o.optString("b64_json")
            if (b64.isNotBlank()) out += ImageData(Base64.decode(b64, Base64.DEFAULT), "image/png")
            else o.optString("url").takeIf { it.startsWith("http") }?.let { out += download(it) }
        }
        if (out.isEmpty()) throw AiException("NO_IMAGES", "OpenAI returned no image")
        return out
    }

    // ------------------------------------------------------------------ Claude

    private suspend fun claudeChat(p: ApiProfile, key: String, system: String?, msgs: List<ChatMsg>, onDelta: (String) -> Unit): String {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", p.model).put("messages", arr).put("stream", true)
            .put("max_tokens", p.maxTokens).put("temperature", p.temperature.coerceIn(0.0, 1.0))
        if (!system.isNullOrBlank()) body.put("system", system)
        val sb = StringBuilder()
        stream("${p.effectiveBaseUrl}/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body) { data ->
            val j = JSONObject(data)
            when (j.optString("type")) {
                "content_block_delta" -> {
                    val t = j.optJSONObject("delta")?.optString("text").orEmpty()
                    if (t.isNotEmpty()) { sb.append(t); onDelta(sb.toString()) }
                }
                "error" -> throw AiException("AI_ERROR", "Claude: " + j.optJSONObject("error")?.optString("message"))
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Gemini

    private fun geminiContents(msgs: List<ChatMsg>) = JSONArray().apply {
        msgs.forEach {
            put(JSONObject().put("role", if (it.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", it.content))))
        }
    }

    private suspend fun geminiChat(p: ApiProfile, key: String, system: String?, msgs: List<ChatMsg>, onDelta: (String) -> Unit): String {
        val body = JSONObject().put("contents", geminiContents(msgs))
            .put("generationConfig", JSONObject().put("temperature", p.temperature).put("maxOutputTokens", p.maxTokens))
        if (!system.isNullOrBlank()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val sb = StringBuilder()
        stream("${p.effectiveBaseUrl}/v1beta/models/${p.model}:streamGenerateContent?alt=sse&key=$key", emptyMap(), body) { data ->
            val j = JSONObject(data)
            val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought")) continue
                val t = part.optString("text")
                if (t.isNotEmpty()) { sb.append(t); onDelta(sb.toString()) }
            }
            j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
                throw AiException("BLOCKED", "Gemini blocked the request ($it)")
            }
        }
        return sb.toString()
    }

    private fun geminiImages(p: ApiProfile, key: String, prompt: String): List<ImageData> {
        val body = JSONObject()
            .put("contents", geminiContents(listOf(ChatMsg("user", prompt))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val j = JSONObject(post("${p.effectiveBaseUrl}/v1beta/models/${p.imageModel}:generateContent?key=$key", emptyMap(), body))
        val out = mutableListOf<ImageData>()
        val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: parts.getJSONObject(i).optJSONObject("inline_data") ?: continue
            out += ImageData(Base64.decode(inline.optString("data"), Base64.DEFAULT), inline.optString("mimeType", "image/png"))
        }
        if (out.isEmpty()) throw AiException("NO_IMAGES", "Gemini returned no image")
        return out
    }

    // ------------------------------------------------------------------ HTTP helpers

    private fun open(url: String, headers: Map<String, String>, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun errorText(c: HttpURLConnection): Nothing {
        val code = c.responseCode
        val body = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val msg = runCatching {
            val j = JSONObject(body)
            j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: j.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(300)
        val kind = when (code) { 401, 403 -> "BAD_KEY"; 429 -> "RATE_LIMIT"; else -> "HTTP_$code" }
        WebLog.add("api", "$code ${c.url.host}: $msg")
        throw AiException(kind, "($code) $msg")
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val c = open(url, headers, "GET")
        try {
            if (c.responseCode !in 200..299) errorText(c)
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val c = open(url, headers, "POST").apply { doOutput = true }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) errorText(c)
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** POSTs and feeds each server-sent-event `data:` payload to [onData]; stops when the coroutine is cancelled. */
    private suspend fun stream(url: String, headers: Map<String, String>, body: JSONObject, onData: (String) -> Unit) {
        val c = open(url, headers, "POST").apply { doOutput = true; setRequestProperty("Accept", "text/event-stream") }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) errorText(c)
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    onData(data)
                }
            }
        } finally { c.disconnect() }
    }

    fun download(url: String, cookie: String? = null, referer: String? = null, userAgent: String? = null): ImageData {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 60_000
            cookie?.let { setRequestProperty("Cookie", it) }
            referer?.let { setRequestProperty("Referer", it) }
            userAgent?.let { setRequestProperty("User-Agent", it) }
        }
        try {
            if (c.responseCode !in 200..299) throw AiException("DOWNLOAD", "Image download failed (${c.responseCode})")
            val mime = c.contentType?.substringBefore(';') ?: "image/png"
            return ImageData(c.inputStream.readBytes(), mime)
        } finally { c.disconnect() }
    }

    private fun JSONArray?.strings(f: (JSONObject) -> String): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.let(f)?.takeIf { s -> s.isNotBlank() } }
    }
}
