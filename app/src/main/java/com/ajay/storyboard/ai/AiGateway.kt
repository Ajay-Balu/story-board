package com.ajay.storyboard.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.ajay.storyboard.capture.WebLog
import com.ajay.storyboard.data.GalleryImage
import com.ajay.storyboard.data.ImageRequest
import com.ajay.storyboard.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One way to ask any AI: built-in web chats and taught sites go through [AiRunner] (hidden pages),
 * API keys through [ApiClient]. Progress for all of them is shown in [AiRunner.status].
 */
class AiGateway(
    private val appContext: Context,
    private val repo: Repository,
    val runner: AiRunner,
    val catalog: AiCatalog,
) {
    /** Sends [prompt] to [target] in a new conversation and returns the full reply. */
    suspend fun ask(target: AiTarget, prompt: String, label: String): AiReply {
        target.page?.let { return runner.ask(it, prompt, label) }
        val profile = (target as AiTarget.Api).profile
        return runner.lock(target.id).let { lock ->
            lock.lock()
            try {
                apiStatus(target, label, AiRunner.Phase.WAITING)
                val text = ApiClient.chat(profile, null, listOf(ChatMsg("user", prompt))) { soFar ->
                    apiStatus(target, label, AiRunner.Phase.WRITING, chars = soFar.length)
                }
                if (text.isBlank()) throw AiException("EMPTY", "${target.label} returned an empty reply")
                apiStatus(target, label, AiRunner.Phase.DONE, chars = text.length)
                AiReply(text, false)
            } catch (e: AiException) {
                apiStatus(target, label, AiRunner.Phase.FAILED, error = e.message)
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                runner.status.remove(target.id)
                throw e
            } catch (e: Exception) {
                val ex = AiException("NETWORK", "${target.label}: ${e.message ?: e.javaClass.simpleName}")
                apiStatus(target, label, AiRunner.Phase.FAILED, error = ex.message)
                throw ex
            } finally {
                lock.unlock()
            }
        }
    }

    suspend fun ask(targetId: String, prompt: String, label: String): AiReply =
        ask(catalog.resolve(targetId) ?: throw AiException("NO_TARGET", "That AI no longer exists"), prompt, label)

    private fun apiStatus(t: AiTarget, label: String, phase: AiRunner.Phase, chars: Int = 0, error: String? = null, detail: String? = null) {
        val s = AiRunner.Status(t.id, t.label, label, phase, chars, error, needsLogin = false, hasPage = false, detail = detail)
        android.os.Handler(android.os.Looper.getMainLooper()).post { runner.status[t.id] = s }
    }

    // ------------------------------------------------------------------ pictures

    /**
     * Asks [target] for pictures for [prompt]. The request is kept as prompt history; every picture
     * is saved to the private gallery (app storage only). Returns the saved pictures.
     */
    suspend fun generateImages(
        target: AiTarget, prompt: String, bookId: Long?, chapterId: Long?, anchor: String? = null,
        label: String = "Picture",
    ): List<GalleryImage> {
        val requestId = repo.images.insertRequest(
            ImageRequest(bookId = bookId, chapterId = chapterId, anchor = anchor, prompt = prompt, target = target.id, targetLabel = target.label)
        )
        try {
            val saved = mutableListOf<GalleryImage>()
            val page = target.page
            if (page != null) {
                val urls = runner.images(page, prompt, label)
                saved += saveUrls(urls, page.homeUrl, requestId, bookId, chapterId, prompt, target)
            } else {
                val profile = (target as AiTarget.Api).profile
                val lock = runner.lock(target.id)
                lock.lock()
                try {
                    apiStatus(target, label, AiRunner.Phase.WAITING, detail = "Creating the picture…")
                    ApiClient.images(profile, prompt).forEach { saved += save(it, requestId, bookId, chapterId, prompt, target) }
                    apiStatus(target, label, AiRunner.Phase.DONE, detail = "${saved.size} pictures")
                } catch (e: AiException) {
                    apiStatus(target, label, AiRunner.Phase.FAILED, error = e.message); throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val ex = AiException("NETWORK", "${target.label}: ${e.message ?: e.javaClass.simpleName}")
                    apiStatus(target, label, AiRunner.Phase.FAILED, error = ex.message); throw ex
                } finally { lock.unlock() }
            }
            if (saved.isEmpty()) throw AiException("NO_IMAGE", "No picture could be saved from ${target.label}")
            repo.images.request(requestId)?.let { repo.images.updateRequest(it.copy(status = "DONE", imageCount = saved.size, error = null)) }
            return saved
        } catch (e: Exception) {
            repo.images.request(requestId)?.let { repo.images.updateRequest(it.copy(status = "FAILED", error = e.message)) }
            throw e
        }
    }

    /** Saves pictures a page reported (data: or web addresses) to the gallery. */
    suspend fun saveUrls(
        urls: List<String>, referer: String, requestId: Long?, bookId: Long?, chapterId: Long?, prompt: String, target: AiTarget?,
    ): List<GalleryImage> = urls.mapNotNull { u ->
        val data = runCatching { fetch(u, referer) }.onFailure { WebLog.add("image", "download failed: ${it.message} $u".take(300)) }.getOrNull()
        data?.let { save(it, requestId, bookId, chapterId, prompt, target) }
    }

    /** Turns a picture address from a page into bytes (data: decoded, web addresses downloaded with the site's cookies). */
    private suspend fun fetch(url: String, referer: String): ImageData = withContext(Dispatchers.IO) {
        if (url.startsWith("data:")) {
            val mime = url.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
            ImageData(Base64.decode(url.substringAfter(","), Base64.DEFAULT), mime)
        } else {
            ApiClient.download(
                url,
                cookie = CookieManager.getInstance().getCookie(url),
                referer = referer,
                userAgent = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull(),
            )
        }
    }

    /** Copies picture bytes into the private gallery folder (never visible to the phone's gallery). */
    suspend fun save(
        data: ImageData, requestId: Long?, bookId: Long?, chapterId: Long?, prompt: String, target: AiTarget?,
        sourceOverride: String? = null, sourceLabelOverride: String? = null,
    ): GalleryImage = withContext(Dispatchers.IO) {
        val ext = when {
            data.mime.contains("jpeg") || data.mime.contains("jpg") -> "jpg"
            data.mime.contains("webp") -> "webp"
            data.mime.contains("gif") -> "gif"
            else -> "png"
        }
        val dir = galleryDir(appContext)
        val file = File(dir, "img_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
        file.writeBytes(data.bytes)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        val img = GalleryImage(
            requestId = requestId, bookId = bookId, chapterId = chapterId, fileName = file.name,
            mime = data.mime, width = opts.outWidth.coerceAtLeast(0), height = opts.outHeight.coerceAtLeast(0),
            bytes = file.length(), prompt = prompt,
            source = sourceOverride ?: target?.id.orEmpty(), sourceLabel = sourceLabelOverride ?: target?.label.orEmpty(),
        )
        img.copy(id = repo.images.insert(img))
    }

    companion object {
        /** filesDir/gallery: private app storage, with .nomedia for good measure. */
        fun galleryDir(ctx: Context): File = File(ctx.filesDir, "gallery").apply {
            mkdirs()
            File(this, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        }

        fun fileOf(ctx: Context, img: GalleryImage): File = File(galleryDir(ctx), img.fileName)
    }
}
