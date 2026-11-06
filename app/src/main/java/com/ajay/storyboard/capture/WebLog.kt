package com.ajay.storyboard.capture

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app log of what the chat pages do (console messages, load errors, popups) for troubleshooting. */
object WebLog {
    val lines = mutableStateListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] ${msg.take(600)}"
        android.util.Log.d("StoryBoardWeb", line)
        lines.add(line)
        if (lines.size > 400) lines.removeRange(0, lines.size - 400)
    }

    fun text(): String = lines.joinToString("\n")
    fun clear() = lines.clear()
}
