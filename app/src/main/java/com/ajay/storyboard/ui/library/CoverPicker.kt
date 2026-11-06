package com.ajay.storyboard.ui.library

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.Book
import com.ajay.storyboard.data.CoverMode
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.common.MetaText
import com.ajay.storyboard.ui.gallery.GalleryThumb
import kotlinx.coroutines.launch

/** Choose the book's cover: automatic (its first picture), one of its pictures, or no picture (a cloth cover). */
@Composable
fun CoverPickerDialog(repo: Repository, bookId: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val book by remember(bookId) { repo.books.observe(bookId) }.collectAsState(initial = null)
    val b: Book = book ?: return
    val pictures by remember(b.id, b.forkOfBookId) {
        repo.images.observeForBooks(listOfNotNull(b.id, b.forkOfBookId))
    }.collectAsState(initial = emptyList())
    fun set(mode: Int, pic: Long? = null) = scope.launch { repo.setCover(b.id, mode, pic); onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cover") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { set(CoverMode.AUTO) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(b.coverMode == CoverMode.AUTO, { set(CoverMode.AUTO) })
                    Column { Text("Automatic"); MetaText("The book's first picture") }
                }
                Row(Modifier.fillMaxWidth().clickable { set(CoverMode.NONE) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(b.coverMode == CoverMode.NONE, { set(CoverMode.NONE) })
                    Column { Text("No picture"); MetaText("A plain cloth cover with the title") }
                }
                if (pictures.isEmpty()) MetaText("This book has no pictures yet. Generate one from a chapter to use it here.", Modifier.padding(top = 8.dp))
                else {
                    Text("Or pick a picture", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 340.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(pictures, key = { it.id }) { img ->
                            val on = b.coverMode == CoverMode.CHOSEN && b.coverPictureId == img.id
                            GalleryThumb(img, 300, Modifier.aspectRatio(2f / 3f)
                                .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
                                .clickable { set(CoverMode.CHOSEN, img.id) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
