package com.ajay.storyboard.ui.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import com.ajay.storyboard.ui.common.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.ajay.storyboard.ui.common.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.ajay.storyboard.ui.common.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ajay.storyboard.StoryBoardApp
import com.ajay.storyboard.ai.AiGateway
import com.ajay.storyboard.data.GalleryImage
import com.ajay.storyboard.ui.common.BackTopBar
import com.ajay.storyboard.ui.common.ConfirmDialog
import com.ajay.storyboard.ui.common.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Small in-memory cache of decoded thumbnails. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(file: File, maxSide: Int): Bitmap? {
        val key = "${file.path}@$maxSide"
        cache.get(key)?.let { return it }
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bmp)
        return bmp
    }
}

@Composable
fun GalleryThumb(img: GalleryImage, maxSide: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, img.id, maxSide) {
        value = withContext(Dispatchers.IO) { Thumbs.load(AiGateway.fileOf(ctx, img), maxSide) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), img.prompt.take(80), Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    b >= 1_000 -> "%.0f KB".format(b / 1e3)
    else -> "$b B"
}

/** Copies a gallery picture to the phone's Pictures/Story-Board folder (only when you ask). */
suspend fun saveToPhone(ctx: Context, img: GalleryImage): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, img.fileName)
        put(MediaStore.Images.Media.MIME_TYPE, img.mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Story-Board")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
    ctx.contentResolver.openOutputStream(uri)?.use { out -> AiGateway.fileOf(ctx, img).inputStream().use { it.copyTo(out) } } != null
}

private sealed interface GalleryFilter {
    data object All : GalleryFilter
    data object Unused : GalleryFilter
    data class Book(val id: Long, val title: String) : GalleryFilter
    data class Source(val label: String) : GalleryFilter
}

/**
 * Private picture gallery: everything any AI made is kept here (app storage, invisible to the phone's
 * gallery) until you delete it. Long-press to select several; "Save to phone" copies one out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(app: StoryBoardApp, onBack: () -> Unit, onGenerate: (prompt: String?, bookId: Long?) -> Unit) {
    val repo = app.repo
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val images by remember { repo.images.observeAll() }.collectAsState(initial = emptyList())
    val total by remember { repo.images.observeTotalBytes() }.collectAsState(initial = 0L)
    val books by remember { repo.books.observeLibrary() }.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf<GalleryFilter>(GalleryFilter.All) }
    val selected = remember { mutableStateListOf<Long>() }
    var viewing by remember { mutableStateOf<GalleryImage?>(null) }
    var confirmDelete by remember { mutableStateOf<List<GalleryImage>?>(null) }

    val shown = images.filter {
        when (val f = filter) {
            GalleryFilter.All -> true
            GalleryFilter.Unused -> !it.used
            is GalleryFilter.Book -> it.bookId == f.id
            is GalleryFilter.Source -> it.sourceLabel == f.label
        }
    }
    val filters = buildList {
        add(GalleryFilter.All); add(GalleryFilter.Unused)
        images.mapNotNull { it.bookId }.distinct().forEach { id -> books.firstOrNull { it.id == id }?.let { add(GalleryFilter.Book(id, it.title)) } }
        images.map { it.sourceLabel }.filter { it.isNotBlank() }.distinct().forEach { add(GalleryFilter.Source(it)) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isEmpty()) BackTopBar(title = "Gallery", subtitle = "${images.size} pictures, ${formatBytes(total)}", onBack = onBack)
            else BackTopBar(
                title = "${selected.size} selected", onBack = { selected.clear() },
                actions = {
                    TextButton(onClick = { selected.clear(); selected.addAll(shown.map { it.id }) }) { Text("All") }
                    IconButton(onClick = { confirmDelete = images.filter { it.id in selected } }) { Icon(Icons.Outlined.Delete, "Delete") }
                },
            )
        },
        floatingActionButton = {
            if (selected.isEmpty()) ExtendedFloatingActionButton(
                onClick = { onGenerate(null, (filter as? GalleryFilter.Book)?.id) },
                icon = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                text = { Text("New picture") },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { f ->
                    FilterChip(
                        selected = f == filter, onClick = { filter = f },
                        label = {
                            Text(when (f) {
                                GalleryFilter.All -> "All"
                                GalleryFilter.Unused -> "Not in a chapter"
                                is GalleryFilter.Book -> f.title
                                is GalleryFilter.Source -> f.label
                            }, maxLines = 1)
                        },
                    )
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (images.isEmpty()) "No pictures yet. Tap New picture to ask an AI for one. Pictures stay private to this app until you save one to your phone."
                    else "No pictures match this filter.",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(shown, key = { it.id }) { img ->
                    val isSel = img.id in selected
                    Box(
                        Modifier.animateItem().aspectRatio(1f)
                            .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
                            .combinedClickable(
                                onClick = { if (selected.isNotEmpty()) { if (isSel) selected.remove(img.id) else selected.add(img.id) } else viewing = img },
                                onLongClick = { if (isSel) selected.remove(img.id) else selected.add(img.id) },
                            ),
                    ) {
                        GalleryThumb(img, 320, Modifier.fillMaxSize())
                        if (isSel) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
            }
        }
    }

    viewing?.let { img ->
        ImageViewer(
            img = img,
            onClose = { viewing = null },
            onSave = {
                scope.launch {
                    val ok = saveToPhone(ctx, img)
                    if (ok) repo.images.update(img.copy(savedToPhone = true))
                    snackbar.showSnackbar(if (ok) "Saved to Pictures/Story-Board" else "Saving to the phone needs Android 10 or newer")
                }
            },
            onAgain = { viewing = null; onGenerate(img.prompt, img.bookId) },
            onDelete = { confirmDelete = listOf(img) },
        )
    }
    confirmDelete?.let { list ->
        ConfirmDialog(
            title = if (list.size == 1) "Delete this picture?" else "Delete ${list.size} pictures?",
            text = "They are removed from the app for good." + if (list.any { it.used }) " Some are used in chapters." else "",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    withContext(Dispatchers.IO) { list.forEach { AiGateway.fileOf(ctx, it).delete() } }
                    repo.images.delete(list)
                    selected.clear(); viewing = null
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun ImageViewer(img: GalleryImage, onClose: () -> Unit, onSave: () -> Unit, onAgain: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                Text("${img.sourceLabel}, ${relativeTime(img.createdAt)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            GalleryThumb(img, 2048, Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${img.width}×${img.height}, ${formatBytes(img.bytes)}" + if (img.savedToPhone) ", saved to phone" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(img.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAgain) { Text("Use prompt again") }
                    OutlinedButton(onClick = onSave) { Text("Save to phone") }
                    IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        }
    }
}

@Composable
fun ThumbRow(images: List<GalleryImage>, onClick: (GalleryImage) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(images, key = { it.id }) { img ->
            GalleryThumb(img, 300, Modifier.size(96.dp).combinedClickableCompat { onClick(img) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)
