package com.ajay.storyboard.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.ui.gallery.GalleryThumb

/** A picture inside a chapter, [widthPct] of the text width, with its caption. */
@Composable
fun StoryPicture(repo: Repository, imageId: Long, caption: String, widthPct: Int, modifier: Modifier = Modifier) {
    val state by remember(imageId) { repo.images.observe(imageId).map { true to it } }.collectAsState(initial = false to null)
    val (loaded, img) = state
    Column(modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val i = img
        if (!loaded) {
            Box(Modifier.fillMaxWidth(widthPct / 100f).aspectRatio(1.5f).background(MaterialTheme.colorScheme.surfaceVariant))
        } else if (i == null) {
            Box(Modifier.fillMaxWidth(widthPct / 100f).height(80.dp), contentAlignment = Alignment.Center) {
                Text("Picture not found (deleted from the Gallery?)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        } else {
            val ratio = if (i.width > 0 && i.height > 0) i.width.toFloat() / i.height else 1f
            GalleryThumb(i, 1400, Modifier.fillMaxWidth(widthPct / 100f).aspectRatio(ratio), contentScale = ContentScale.Fit)
        }
        if (caption.isNotBlank()) Text(
            caption, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
        )
    }
}
