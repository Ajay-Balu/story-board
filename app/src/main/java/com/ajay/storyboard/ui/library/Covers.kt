package com.ajay.storyboard.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajay.storyboard.data.BookWithStats
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.gallery.GalleryThumb
import kotlin.math.abs

/** Cloth-binding colors for generated covers and spines (picked from the title, so they stay the same). */
private val Bindings = listOf(
    Color(0xFF3E2C6E) to Color(0xFF6E58B8),   // indigo
    Color(0xFF7A2E2E) to Color(0xFFB4544A),   // oxblood
    Color(0xFF1F4F4A) to Color(0xFF3F877C),   // teal
    Color(0xFF6B4A12) to Color(0xFFB0812E),   // ochre
    Color(0xFF2B3F66) to Color(0xFF4F6FA6),   // navy
    Color(0xFF4F2A4F) to Color(0xFF8C4E86),   // plum
    Color(0xFF2F4A28) to Color(0xFF5C8550),   // moss
    Color(0xFF5A3A2A) to Color(0xFF946048),   // leather
)

private fun hashOf(s: String) = abs(s.hashCode())
fun bindingOf(title: String): Pair<Color, Color> = Bindings[hashOf(title) % Bindings.size]

/** Vertical text (rotated 90°, reading bottom to top like a book spine). */
fun Modifier.vertical(): Modifier = this
    .layout { measurable, c ->
        val p = measurable.measure(Constraints(minWidth = c.minHeight, maxWidth = c.maxHeight, minHeight = c.minWidth, maxHeight = c.maxWidth))
        layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
    }
    .graphicsLayer { rotationZ = -90f }

/** A book cover: the book's first picture with the title over it, or a generated cloth cover. */
@Composable
fun BookCover(repo: Repository, book: BookWithStats, modifier: Modifier = Modifier, titleSize: Int = 15) {
    val (dark, light) = bindingOf(book.title)
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(light, dark), start = Offset(0f, 0f), end = Offset(400f, 700f))),
    ) {
        val img by remember(book.coverImageId) {
            book.coverImageId?.let { repo.images.observe(it) } ?: kotlinx.coroutines.flow.flowOf(null)
        }.collectAsState(initial = null)
        img?.let {
            GalleryThumb(it, 500, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)), startY = 150f)))
        }
        // binding edge + gold rules
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Color(0x33000000), size = size.copy(width = 6.dp.toPx()))
            drawRect(Color(0x22FFFFFF), topLeft = Offset(6.dp.toPx(), 0f), size = size.copy(width = 1.dp.toPx()))
            if (img == null) {
                val gold = Color(0xCCE8C77A)
                drawRect(gold, topLeft = Offset(14.dp.toPx(), 14.dp.toPx()), size = size.copy(width = size.width - 22.dp.toPx(), height = 1.dp.toPx()))
                drawRect(gold, topLeft = Offset(14.dp.toPx(), size.height - 15.dp.toPx()), size = size.copy(width = size.width - 22.dp.toPx(), height = 1.dp.toPx()))
            }
        })
        Column(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 22.dp, bottom = 20.dp),
            verticalArrangement = if (img == null) androidx.compose.foundation.layout.Arrangement.Center else androidx.compose.foundation.layout.Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (img == null || titleSize >= 10) Text(
                book.title, color = Color(0xFFFFF8E8), fontFamily = com.ajay.storyboard.ui.theme.AppFonts.grotesk, fontWeight = FontWeight.SemiBold,
                fontSize = titleSize.sp, lineHeight = (titleSize * 1.2f).sp, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A book standing on a shelf: spine with the title written upwards. Thicker books have more chapters. */
@Composable
fun BookSpine(book: BookWithStats, modifier: Modifier = Modifier) {
    val (dark, light) = bindingOf(book.title)
    val h = hashOf(book.title)
    Box(
        modifier
            .width(spineWidth(book))
            .height(spineHeight(book))
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.horizontalGradient(listOf(dark, light, light, dark)))
            .drawBehind {
                val gold = Color(0xCCE8C77A)
                val band = 2.dp.toPx()
                listOf(14.dp.toPx(), 19.dp.toPx(), size.height - 21.dp.toPx(), size.height - 16.dp.toPx()).forEach { y ->
                    drawRect(gold, topLeft = Offset(0f, y), size = size.copy(height = band / 2))
                }
                if (h % 3 == 0) drawRect(Color(0x33000000), topLeft = Offset(0f, size.height * 0.62f), size = size.copy(height = 10.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            book.title, color = Color(0xFFFFF4DC), fontFamily = com.ajay.storyboard.ui.theme.AppFonts.grotesk, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.vertical().padding(horizontal = 24.dp),
        )
    }
}

fun spineWidth(book: BookWithStats): Dp = (30 + (book.chapterCount.coerceAtMost(30) * 1.2f)).dp
fun spineHeight(book: BookWithStats): Dp = (168 + hashOf(book.title) % 5 * 8).dp

/** The wooden shelf board under a row of books. */
@Composable
fun ShelfBoard(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(12.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFFB98552), Color(0xFF8C5F34))))
        )
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF6E4524), Color(0xFF4A2E17))))
        )
        Box(Modifier.fillMaxWidth().height(10.dp).background(Brush.verticalGradient(listOf(Color(0x55000000), Color.Transparent))))
    }
}

/** Packs books into shelf rows that fit the width. */
@Composable
fun ShelfRows(books: List<BookWithStats>, gap: Dp, content: @Composable (row: List<BookWithStats>) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avail = maxWidth - 32.dp
        val rows = remember(books, avail) {
            val out = mutableListOf<MutableList<BookWithStats>>()
            var w = 0.dp
            books.forEach { b ->
                val bw = spineWidth(b) + gap
                if (out.isEmpty() || w + bw > avail) { out.add(mutableListOf()); w = 0.dp }
                out.last().add(b); w += bw
            }
            out
        }
        Column { rows.forEach { content(it) } }
    }
}
