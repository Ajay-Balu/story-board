package com.ajay.storyboard.ai

import com.ajay.storyboard.data.BibleEntry
import com.ajay.storyboard.data.Chapter
import com.ajay.storyboard.data.ChapterBlocks
import org.json.JSONArray
import org.json.JSONObject

val BIBLE_CATEGORIES = listOf("CHARACTER", "RELATIONSHIP", "OUTFIT", "PLACE", "OBJECT", "RULE", "TIMELINE")

data class Issue(val type: String, val quote: String, val problem: String, val fix: String)

/** Prompt texts for the AI actions, and parsing of their replies. */
object Prompts {

    private fun bibleLines(entries: List<BibleEntry>): String =
        if (entries.isEmpty()) "(empty)"
        else entries.joinToString("\n") { "- [${it.category}] ${it.name}: ${it.details}${if (it.locked) "  (FIXED BY AUTHOR)" else ""}" }

    fun bibleUpdate(bookTitle: String, entries: List<BibleEntry>, number: Int, chapter: Chapter): String {
        val current = JSONArray().apply {
            entries.forEach { put(JSONObject().put("category", it.category).put("name", it.name).put("details", it.details).put("locked", it.locked)) }
        }
        return """
You maintain the STORY BIBLE for a novel titled "$bookTitle". The story bible records facts that must stay consistent between chapters: characters (appearance, age, core personality, way of speaking, goals), relationships, outfits / clothing, places and room details, important objects, world rules, and the timeline.

CURRENT STORY BIBLE (JSON):
$current

Entries with "locked": true were fixed by the author. Never change or contradict them and do not include them in your reply.

NEW CHAPTER $number: "${chapter.title}"
<<<CHAPTER
${ChapterBlocks.textOnly(chapter.content)}
CHAPTER>>>

TASK: Return only the bible entries that are NEW or must be UPDATED because of this chapter.
- For an updated entry give the complete new details (merge old and new facts), concise, at most about 60 words.
- Categories: ${BIBLE_CATEGORIES.joinToString(", ")}.
- OUTFIT names look like "<Character> - <occasion or chapter>". TIMELINE name is "Chapter $number" with the key events in 1-3 sentences.
- Use the exact existing name when updating an existing entry.
- Write names and details in the same language as the chapter.

Reply with ONLY this JSON and nothing else (no markdown, no commentary):
{"entries":[{"category":"CHARACTER","name":"...","details":"..."}]}
""".trim()
    }

    fun consistencyCheck(bookTitle: String, entries: List<BibleEntry>, previous: Chapter?, chapterTitle: String, text: String): String = """
You are a continuity editor for the novel "$bookTitle". Compare the NEW CHAPTER with the STORY BIBLE and the end of the previous chapter, and list continuity errors only:
wrong or changed names, changed appearance, outfits that change without a reason, room or setting details that contradict earlier ones, timeline mistakes, a character acting against their core personality, or facts that contradict earlier events.
Do NOT report style, grammar, pacing or plot choices. Entries marked FIXED BY AUTHOR are always true.

STORY BIBLE:
${bibleLines(entries)}

END OF PREVIOUS CHAPTER${previous?.let { " (\"${it.title}\")" } ?: ""}:
<<<
${previous?.content?.let { ChapterBlocks.textOnly(it) }?.takeLast(3000) ?: "(this is the first chapter)"}
>>>

NEW CHAPTER: "$chapterTitle"
<<<
${ChapterBlocks.textOnly(text)}
>>>

Reply with ONLY this JSON and nothing else:
{"issues":[{"type":"CHARACTER|OUTFIT|PLACE|TIMELINE|PERSONALITY|OTHER","quote":"exact short quote from the new chapter","problem":"what is inconsistent","fix":"how to fix it"}]}
If there are no continuity errors reply {"issues":[]}
Write "problem" and "fix" in the same language as the chapter; keep the JSON keys in English.
""".trim()

    fun rewrite(bookTitle: String, entries: List<BibleEntry>, issues: List<Issue>, instructions: String, chapterTitle: String, text: String): String {
        val problems = if (issues.isEmpty()) "(none)" else issues.mapIndexed { i, it ->
            "${i + 1}. \"${it.quote}\" — ${it.problem} → ${it.fix}"
        }.joinToString("\n")
        val hasInstructions = instructions.isNotBlank()
        return buildString {
            appendLine("You are rewriting a chapter of the novel \"$bookTitle\" for its author.")
            appendLine()
            if (hasInstructions) {
                appendLine("=== 1. AUTHOR'S INSTRUCTIONS — TOP PRIORITY ===")
                appendLine(instructions.trim())
                appendLine()
                appendLine("Carry out these instructions fully and visibly, even when that means large changes to events, dialogue,")
                appendLine("setting, details or length, and even when they go beyond or contradict the story bible.")
                appendLine("The author's instructions always win. A reader comparing the old and new chapter must clearly see every requested change.")
                appendLine()
            }
            appendLine("=== ${if (hasInstructions) "2" else "1"}. CONTINUITY FIXES ===")
            appendLine(problems)
            appendLine()
            appendLine("=== REFERENCE ONLY: STORY BIBLE ===")
            appendLine("Use this only to keep names, appearances and facts consistent where the instructions don't say otherwise.")
            appendLine("Never use it as a reason to skip or water down a requested change.")
            appendLine(bibleLines(entries))
            appendLine()
            appendLine("=== CHAPTER TO REWRITE: \"$chapterTitle\" ===")
            appendLine("<<<")
            appendLine(ChapterBlocks.textOnly(text))
            appendLine(">>>")
            appendLine()
            appendLine("Rules:")
            appendLine("- Apply the author's instructions first, then the continuity fixes.")
            appendLine("- Keep the author's writing style and point of view" + (if (hasInstructions) " unless the instructions say otherwise." else "."))
            appendLine("- Write in the same language as the chapter (unless the instructions ask for another language).")
            appendLine("- Everything not affected by the instructions or fixes can stay as it is.")
            append("- Reply with ONLY the complete rewritten chapter text. No introduction, no notes, no code fences.")
        }
    }

    /** Recap pasted into a new DeepSeek chat so it can continue the story (you edit it, then send). */
    fun recap(bookTitle: String, entries: List<BibleEntry>, chapters: List<Chapter>): String {
        val last = chapters.takeLast(2)
        val earlier = entries.filter { it.category == "TIMELINE" }.joinToString("\n") { "- ${it.name}: ${it.details}" }
        return buildString {
            appendLine("We are continuing my story \"$bookTitle\". Here is everything you need to stay consistent.")
            appendLine()
            appendLine("STORY BIBLE:")
            appendLine(bibleLines(entries.filter { it.category != "TIMELINE" }))
            if (earlier.isNotBlank()) {
                appendLine()
                appendLine("WHAT HAPPENED SO FAR:")
                appendLine(earlier)
            }
            last.forEach { ch ->
                appendLine()
                appendLine("CHAPTER ${ch.position + 1}: ${ch.title}")
                appendLine(ChapterBlocks.textOnly(ch.content))
            }
            appendLine()
            appendLine("Keep the same style, characters and details, and write in the same language as these chapters. Next is Chapter ${chapters.size + 1}. Don't write yet; wait for my instructions for that chapter.")
        }.trim()
    }

    /**
     * System message for writing with an API key ("smart context"): the story bible and the last two
     * chapters are sent with every message, so the chat itself only needs the recent messages.
     */
    fun writingSystem(bookTitle: String, entries: List<BibleEntry>, chapters: List<Chapter>): String = buildString {
        appendLine("You are co-writing the story \"$bookTitle\" with its author, chapter by chapter.")
        appendLine("Follow the author's instructions first. Use the story bible and earlier chapters as reference to stay consistent (names, looks, outfits, places, timeline).")
        appendLine("Write in the same language as the story's chapters (or the author's message if there are no chapters yet).")
        appendLine("When asked for a chapter, reply with the chapter only: its title on the first line, then the text, no notes before or after.")
        if (entries.isNotEmpty()) {
            appendLine()
            appendLine("STORY BIBLE:")
            appendLine(bibleLines(entries))
        }
        chapters.takeLast(2).forEach { ch ->
            appendLine()
            appendLine("CHAPTER ${ch.position + 1}: ${ch.title}")
            appendLine(ChapterBlocks.textOnly(ch.content))
        }
        appendLine()
        appendLine(if (chapters.isEmpty()) "No chapters yet. The next one is Chapter 1." else "The story has ${chapters.size} chapters. The next one is Chapter ${chapters.size + 1}.")
    }.trim()

    /** "Edit with AI" on selected paragraphs: only the selection comes back. */
    fun editParagraphs(
        bookTitle: String, entries: List<BibleEntry>, chapterTitle: String,
        before: String, selected: String, after: String, instruction: String,
    ): String = buildString {
        appendLine("You are editing a passage of the novel \"$bookTitle\" (chapter \"$chapterTitle\") for its author.")
        appendLine()
        appendLine("=== AUTHOR'S INSTRUCTION — TOP PRIORITY ===")
        appendLine(instruction.trim())
        appendLine()
        appendLine("=== PASSAGE TO REWRITE ===")
        appendLine("<<<")
        appendLine(selected)
        appendLine(">>>")
        if (before.isNotBlank()) { appendLine(); appendLine("Text just before (context only, do not rewrite):"); appendLine(before) }
        if (after.isNotBlank()) { appendLine(); appendLine("Text just after (context only, do not rewrite):"); appendLine(after) }
        if (entries.isNotEmpty()) {
            appendLine()
            appendLine("=== REFERENCE ONLY: STORY BIBLE ===")
            appendLine(bibleLines(entries.filter { it.category != "TIMELINE" }))
        }
        appendLine()
        appendLine("Rules:")
        appendLine("- Follow the instruction; keep the author's style and point of view.")
        appendLine("- It must still fit between the text before and after.")
        appendLine("- Write in the same language as the passage (unless the instruction asks for another language).")
        append("- Reply with ONLY the rewritten passage, paragraphs separated by a blank line. No introduction, notes or code fences.")
    }

    /** Story context for a plot-decision roundtable. */
    fun roundtableContext(bookTitle: String, entries: List<BibleEntry>, chapters: List<Chapter>): String = buildString {
        appendLine("STORY: \"$bookTitle\" (${chapters.size} chapter${if (chapters.size == 1) "" else "s"} so far)")
        if (entries.isNotEmpty()) { appendLine(); appendLine("STORY BIBLE:"); appendLine(bibleLines(entries)) }
        chapters.takeLast(2).forEach { ch ->
            appendLine(); appendLine("CHAPTER ${ch.position + 1}: ${ch.title}"); appendLine(ChapterBlocks.textOnly(ch.content).take(12000))
        }
    }.trim()

    fun roundtableAnswer(context: String, question: String, earlier: String): String = buildString {
        appendLine("You are one of several AIs on a roundtable. Answer the question below on your own.")
        if (context.isNotBlank()) { appendLine(); appendLine(context) }
        if (earlier.isNotBlank()) { appendLine(); appendLine("EARLIER IN THIS DISCUSSION:"); appendLine(earlier) }
        appendLine(); appendLine("QUESTION:"); appendLine(question.trim())
        appendLine()
        append("Give a clear answer with your reasoning, at most about 300 words. Reply in the language of the question.")
    }

    /** The moderator reads the latest answers and guides the next round. */
    fun roundtableGuide(context: String, question: String, answers: List<Pair<String, String>>, nextRound: Int): String = buildString {
        appendLine("You are the moderator of a roundtable of AIs. Question:")
        appendLine(question.trim())
        if (context.isNotBlank()) { appendLine(); appendLine(context) }
        answers.forEach { (name, text) -> appendLine(); appendLine("ANSWER FROM $name:"); appendLine(text) }
        appendLine()
        appendLine("Prepare round $nextRound of the debate. Write:")
        appendLine("1. Agreement: what all of them already agree on (short).")
        appendLine("2. Contradictions: a numbered list; for each, who holds which position.")
        appendLine("3. Guidance for round $nextRound: one short paragraph per AI, by name, saying what it must defend, reconsider or answer, including its weakest claim.")
        append("Do not give your own final answer yet. At most about 300 words. Reply in the language of the question.")
    }

    fun roundtableChallenge(context: String, question: String, mine: String, others: List<Pair<String, String>>, guidance: String = "", me: String = ""): String = buildString {
        appendLine("You are on a roundtable of AIs discussing this question:")
        appendLine(question.trim())
        if (context.isNotBlank()) { appendLine(); appendLine(context) }
        appendLine(); appendLine("YOUR PREVIOUS ANSWER:"); appendLine(mine.ifBlank { "(none)" })
        others.forEach { (name, text) -> appendLine(); appendLine("ANSWER FROM $name:"); appendLine(text) }
        if (guidance.isNotBlank()) {
            appendLine(); appendLine("THE MODERATOR'S GUIDANCE FOR THIS ROUND" + (if (me.isNotBlank()) " (you are $me)" else "") + ":"); appendLine(guidance.trim())
        }
        appendLine()
        appendLine(if (guidance.isNotBlank()) "Follow the moderator's guidance. Defend your position where you are right and concede where another answer is better; say who changed your mind."
            else "Challenge the other answers: point out mistakes, weak points or better ideas, and say where you agree.")
        append("Then give your REVISED ANSWER under the heading \"Revised answer:\". At most about 350 words. Reply in the language of the question.")
    }

    fun roundtableVerdict(context: String, question: String, answers: List<Pair<String, String>>): String = buildString {
        appendLine("You are the moderator of a roundtable of AIs. Question:")
        appendLine(question.trim())
        if (context.isNotBlank()) { appendLine(); appendLine(context) }
        answers.forEach { (name, text) -> appendLine(); appendLine("FINAL ANSWER FROM $name:"); appendLine(text) }
        appendLine()
        appendLine("Write the verdict:")
        appendLine("1. Consensus: where they agree.")
        appendLine("2. Still disputed: each open point, who holds which side, and who has the stronger argument.")
        appendLine("3. Who changed their mind during the debate, if anyone.")
        appendLine("4. Your final recommendation, concrete and actionable, and how confident you are (low, medium or high).")
        append("At most about 400 words. Reply in the language of the question.")
    }

    // ------------------------------------------------------------------ parsing

    /** Finds the JSON object in an AI reply (tolerates ```json fences and extra text). */
    fun jsonObject(reply: String): JSONObject? {
        // Some chat sites show (and so we read) typographic quotes; JSON needs plain ones.
        val clean = reply.replace("```json", "```").replace("```", "")
            .replace('\u201C', '"').replace('\u201D', '"').replace('\u201E', '"').replace('\u00AB', '"').replace('\u00BB', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull()
    }

    fun parseBible(reply: String): List<Triple<String, String, String>>? {
        val arr = jsonObject(reply)?.optJSONArray("entries") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cat = o.optString("category").uppercase().trim().let { c -> BIBLE_CATEGORIES.firstOrNull { c.startsWith(it) } ?: "OTHER" }
            Triple(cat, o.optString("name").trim(), o.optString("details").trim())
        }
    }

    fun parseIssues(reply: String): List<Issue>? {
        val arr = jsonObject(reply)?.optJSONArray("issues") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Issue(o.optString("type", "OTHER").uppercase(), o.optString("quote"), o.optString("problem"), o.optString("fix"))
                .takeIf { it.problem.isNotBlank() }
        }
    }

    /** Strips wrappers an AI sometimes adds around a rewritten chapter. */
    fun cleanChapter(reply: String): String {
        var t = reply.trim().replace("```markdown", "```").replace("```", "").trim()
        t = t.removePrefix("<<<").removeSuffix(">>>").trim()
        val first = t.lineSequence().firstOrNull().orEmpty()
        if (Regex("^(here is|here's|sure|below is)", RegexOption.IGNORE_CASE).containsMatchIn(first) && first.length < 160) {
            t = t.substringAfter(first).trim()
        }
        return t
    }
}
