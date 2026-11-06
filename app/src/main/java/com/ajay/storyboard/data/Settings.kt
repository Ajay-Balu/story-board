package com.ajay.storyboard.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Chat services the app can drive with your own web logins.
 * [chatPath] matches a URL that belongs to one specific conversation (used to link a book to its chat).
 * [network] = replies are read from the site's network stream (DeepSeek); the others are read from the page.
 */
enum class AiService(val label: String, val homeUrl: String, val chatPath: Regex) {
    DEEPSEEK("DeepSeek", "https://chat.deepseek.com/", Regex("/a/chat/s/[\\w-]+")),
    GROK("Grok", "https://grok.com/", Regex("/c/[\\w-]+")),
    CHATGPT("ChatGPT", "https://chatgpt.com/", Regex("/c/[\\w-]+")),
    CLAUDE("Claude", "https://claude.ai/new", Regex("/chat/[\\w-]+")),
    GEMINI("Gemini", "https://gemini.google.com/app", Regex("/app/[0-9a-f]{8,}")),
    ;

    /** https://host — the site's origin. */
    val origin: String get() = Regex("^https://[^/]+").find(homeUrl)!!.value

    fun isChatUrl(url: String): Boolean = url.startsWith(origin) && chatPath.containsMatchIn(url.removePrefix(origin))
}

/**
 * Things the app asks an AI to do. Each has a default AI, stored as a target id:
 * "web:DEEPSEEK" (a website with your login), "api:3" (an API key entry) or "site:2" (a taught site).
 */
enum class AiAction(val label: String, val description: String, val defaultTarget: String, val needsImages: Boolean = false) {
    WRITE("Write", "Writes the next chapter from your prompt", "web:DEEPSEEK"),
    BIBLE("Story bible", "Extracts characters, outfits, rooms and traits", "web:GROK"),
    CHECK("Consistency check", "Compares a chapter with the story bible", "web:GROK"),
    REWRITE("Rewrite", "Applies your instructions and the approved fixes", "web:DEEPSEEK"),
    IMAGE("Images", "Generates pictures for your story", "web:CHATGPT", needsImages = true),
}

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

/** Small settings store backed by SharedPreferences, exposed as Compose state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val defaults = AiAction.entries.associateWith { action ->
        mutableStateOf(
            prefs.getString("ai_${action.name}", null)
                // older versions stored just the web service name ("DEEPSEEK")
                ?.let { if (it.contains(':')) it else "web:$it" }
                ?: action.defaultTarget
        )
    }

    /** Target id ("web:…", "api:…", "site:…") chosen for [action]. Resolve it with AiCatalog. */
    fun targetFor(action: AiAction): String = defaults.getValue(action).value

    fun setTargetFor(action: AiAction, targetId: String) {
        defaults.getValue(action).value = targetId
        prefs.edit().putString("ai_${action.name}", targetId).apply()
    }

    var keepScreenOnWhileWriting by mutableStateOf(prefs.getBoolean("keep_screen_on", true))
        private set

    fun updateKeepScreenOn(value: Boolean) {
        keepScreenOnWhileWriting = value
        prefs.edit().putBoolean("keep_screen_on", value).apply()
    }

    var themeMode by mutableStateOf(
        prefs.getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    )
        private set

    fun updateTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    /** Android 12+: take the app colors from the wallpaper instead of Story-Board's own palette. */
    var wallpaperColors by mutableStateOf(prefs.getBoolean("wallpaper_colors", false))
        private set

    fun updateWallpaperColors(value: Boolean) {
        wallpaperColors = value
        prefs.edit().putBoolean("wallpaper_colors", value).apply()
    }

    /** Library layout: LIST, GRID or SHELF. */
    var libraryView by mutableStateOf(prefs.getString("library_view", null) ?: "SHELF")
        private set

    fun updateLibraryView(value: String) {
        libraryView = value
        prefs.edit().putString("library_view", value).apply()
    }
}
