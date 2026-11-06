package com.ajay.storyboard

import com.ajay.storyboard.ai.Prompts
import com.ajay.storyboard.backup.StoryExport
import com.ajay.storyboard.data.ChapterBlocks
import com.ajay.storyboard.ui.common.dropRepeatedTitle
import com.ajay.storyboard.ui.common.splitTitle
import com.ajay.storyboard.ui.common.wordCount
import com.ajay.storyboard.ui.reader.EditSelection
import com.ajay.storyboard.ui.reader.longPress
import com.ajay.storyboard.ui.reader.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterBlocksTest {
    @Test fun linesSkipBlankAndTrim() {
        assertEquals(listOf("a", "b c", "d"), ChapterBlocks.lines("  a \n\n b c\n\n\n d  "))
    }

    @Test fun pictureRoundTrip() {
        val line = ChapterBlocks.pictureLine(42, "Milo in the rain", 60)
        assertEquals("![Milo in the rain](sbimg:42?w=60)", line)
        val p = ChapterBlocks.picture(line)!!
        assertEquals(42L, p.imageId); assertEquals("Milo in the rain", p.caption); assertEquals(60, p.widthPct)
    }

    @Test fun fullWidthPictureHasNoParameter() {
        assertEquals("![](sbimg:7)", ChapterBlocks.pictureLine(7))
        assertEquals(100, ChapterBlocks.picture("![](sbimg:7)")!!.widthPct)
    }

    @Test fun captionCannotBreakTheSyntax() {
        val line = ChapterBlocks.pictureLine(3, "a ] tricky\ncaption")
        assertNotNull(ChapterBlocks.picture(line))
    }

    @Test fun ordinaryMarkdownImageIsNotAPicture() {
        assertNull(ChapterBlocks.picture("![x](https://example.com/a.png)"))
        assertNull(ChapterBlocks.picture("Just text"))
    }

    @Test fun remapAndTextOnly() {
        val content = "Hello\n\n![c](sbimg:1)\n\nWorld\n\n![d](sbimg:2?w=50)"
        val mapped = ChapterBlocks.remapImages(content, mapOf(1L to 11L, 2L to 22L))
        assertEquals(listOf(11L, 22L), ChapterBlocks.imageIds(mapped))
        assertEquals(50, ChapterBlocks.picture(ChapterBlocks.lines(mapped)[3])!!.widthPct)
        assertEquals("Hello\n\nWorld", ChapterBlocks.textOnly(content))
    }
}

class EditSelectionTest {
    @Test fun tapSelectsAndTapAgainClears() {
        val s = (null as EditSelection?).tap(1, 3)
        assertEquals(EditSelection(1, 3, 3, 3, false), s)
        assertNull(s.tap(1, 3))
    }

    @Test fun longPressThenTapMakesRangeInEitherDirection() {
        assertEquals(EditSelection(1, 2, 5, 2, false), longPress(1, 2).tap(1, 5))
        assertEquals(EditSelection(1, 1, 4, 4, false), longPress(1, 4).tap(1, 1))
    }

    @Test fun rangeDoesNotCrossChapters() {
        assertEquals(EditSelection(2, 0, 0, 0, false), longPress(1, 3).tap(2, 0))
    }

    @Test fun tapOtherParagraphMovesSelection() {
        assertEquals(EditSelection(1, 6, 6, 6, false), (null as EditSelection?).tap(1, 2).tap(1, 6))
    }
}

class PromptsTest {
    @Test fun parsesBibleWithFencesAndCurlyQuotes() {
        val reply = "Sure!\n```json\n{\u201Centries\u201D:[{\u201Ccategory\u201D:\u201Ccharacter\u201D,\u201Cname\u201D:\u201CMilo\u201D,\u201Cdetails\u201D:\u201Cgrey cat\u201D}]}\n```"
        val e = Prompts.parseBible(reply)!!
        assertEquals(1, e.size)
        assertEquals(Triple("CHARACTER", "Milo", "grey cat"), e[0])
    }

    @Test fun unknownCategoryBecomesOther() {
        val e = Prompts.parseBible("{\"entries\":[{\"category\":\"weather\",\"name\":\"Rain\",\"details\":\"always\"}]}")!!
        assertEquals("OTHER", e[0].first)
    }

    @Test fun parsesIssuesAndDropsEmptyOnes() {
        val i = Prompts.parseIssues("{\"issues\":[{\"type\":\"outfit\",\"quote\":\"q\",\"problem\":\"p\",\"fix\":\"f\"},{\"problem\":\"\"}]}")!!
        assertEquals(1, i.size); assertEquals("OUTFIT", i[0].type)
        assertTrue(Prompts.parseIssues("{\"issues\":[]}")!!.isEmpty())
        assertNull(Prompts.parseIssues("no json here"))
    }

    @Test fun cleanChapterRemovesIntroAndFences() {
        assertEquals("Chapter 4\n\nText.", Prompts.cleanChapter("Here is the rewritten chapter:\n```\nChapter 4\n\nText.\n```"))
        assertEquals("Plain", Prompts.cleanChapter("  Plain  "))
    }

    @Test fun guidanceNamesEveryAiAndTheNextRound() {
        val p = Prompts.roundtableGuide("", "Where next?", listOf("ChatGPT" to "a stream", "Inception" to "a pond"), 2)
        assertTrue(p.contains("ANSWER FROM ChatGPT") && p.contains("ANSWER FROM Inception"))
        assertTrue(p.contains("round 2"))
        assertTrue(p.contains("language of the question"))
    }

    @Test fun rebuttalCarriesTheEditedGuidance() {
        val p = Prompts.roundtableChallenge("", "Where next?", "mine", listOf("Inception" to "theirs"), "Author note: include Mittens", "ChatGPT")
        assertTrue(p.contains("Author note: include Mittens"))
        assertTrue(p.contains("you are ChatGPT"))
        assertTrue(p.contains("Revised answer:"))
        // without guidance it falls back to a plain challenge
        assertTrue(Prompts.roundtableChallenge("", "Q", "m", emptyList()).contains("Challenge the other answers"))
    }

    @Test fun storyContextCountsChaptersRight() {
        val one = Prompts.roundtableContext("Pip", emptyList(), listOf(com.ajay.storyboard.data.Chapter(bookId = 1, position = 0, title = "One", content = "x")))
        assertTrue(one.contains("(1 chapter so far)"))
    }

    @Test fun editPromptKeepsLanguageRule() {
        val p = Prompts.editParagraphs("B", emptyList(), "Ch", "", "selected", "", "make it sad")
        assertTrue(p.contains("same language"))
        assertTrue(p.contains("selected"))
    }
}

class TextHelpersTest {
    @Test fun splitTitleFromHeading() {
        assertEquals("Chapter 3: Rain" to "Body", splitTitle("## Chapter 3: Rain\nBody", "Chapter 9"))
        assertEquals("Chapter 9" to "No heading here.", splitTitle("No heading here.", "Chapter 9"))
    }

    @Test fun dropsRepeatedTitleLines() {
        assertEquals("Body", dropRepeatedTitle("**Rain**\n# Rain\nBody", "Rain"))
    }

    @Test fun wordCountWorks() {
        assertEquals(3, wordCount("  one two\nthree "))
        assertEquals(0, wordCount("   "))
    }

    @Test fun exportFileNameIsSafe() {
        assertEquals("a b c.epub", StoryExport.fileName("a/b:c", "epub"))
        assertEquals("story.pdf", StoryExport.fileName("  ", "pdf"))
    }
}

class KeepPicturesTest {
    @Test fun picturesGoBackAtTheSameRelativePlace() {
        val old = "a\n\n![x](sbimg:1)\n\nb\n\nc\n\n![y](sbimg:2)"
        val new = "A\n\nB\n\nC\n\nD"
        val lines = ChapterBlocks.lines(ChapterBlocks.keepPictures(old, new))
        assertEquals(6, lines.size)
        assertEquals(listOf(1L, 2L), ChapterBlocks.imageIds(lines.joinToString("\n")))
        assertEquals("A", lines[0])
        assertEquals(listOf("A", "![x](sbimg:1)", "B", "C", "![y](sbimg:2)", "D"), lines)
    }

    @Test fun nothingToDoWithoutPictures() {
        assertEquals("x", ChapterBlocks.keepPictures("a\n\nb", "x"))
    }
}
