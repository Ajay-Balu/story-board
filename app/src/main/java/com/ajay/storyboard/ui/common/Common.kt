package com.ajay.storyboard.ui.common

import android.text.format.DateUtils

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

/**
 * Suggests a chapter title from the AI text: uses the first line when it looks like a heading
 * ("# ...", "**Chapter 3: ...**", "Chapter 3 - ..."), and returns the body without that line.
 */
fun splitTitle(text: String, fallback: String): Pair<String, String> {
    val trimmed = text.trim()
    val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
    val cleaned = firstLine.trim('#', '*', '_', ' ').trim()
    val looksLikeHeading = firstLine.startsWith("#") ||
        (firstLine.startsWith("**") && firstLine.endsWith("**") && cleaned.length < 90) ||
        Regex("^(chapter|part)\\s+\\w+", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) && cleaned.length < 90
    return if (looksLikeHeading && cleaned.isNotEmpty()) {
        cleaned to dropRepeatedTitle(trimmed.substringAfter(firstLine).trim(), cleaned)
    } else {
        fallback to trimmed
    }
}

/** Removes leading lines that only repeat the title (some sites show the title twice, e.g. a card header + heading). */
fun dropRepeatedTitle(body: String, title: String): String {
    fun norm(x: String) = x.trim().trim('#', '*', '_', ' ').trim().lowercase()
    var rest = body.trim()
    while (true) {
        val first = rest.lineSequence().firstOrNull() ?: return rest
        if (first.isNotBlank() && norm(first) == norm(title)) rest = rest.substringAfter(first).trim() else return rest
    }
}
