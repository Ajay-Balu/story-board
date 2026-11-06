package com.ajay.storyboard.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** API providers with their own request formats. */
enum class ApiProvider(val label: String, val defaultBaseUrl: String, val canMakeImages: Boolean) {
    OPENAI("OpenAI", "https://api.openai.com", true),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com", true),
    CLAUDE("Claude", "https://api.anthropic.com", false),
}

/** One API key entry ("My OpenAI", model, key). The key is stored encrypted (see KeyVault). */
@Entity(tableName = "api_profiles")
data class ApiProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val name: String,
    val model: String,
    /** Model used for images (OpenAI / Gemini); empty = not used for images. */
    val imageModel: String = "",
    val baseUrl: String = "",
    val keyEncrypted: String,
    val temperature: Double = 0.9,
    val maxTokens: Int = 4096,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val providerEnum: ApiProvider get() = runCatching { ApiProvider.valueOf(provider) }.getOrDefault(ApiProvider.OPENAI)
    val effectiveBaseUrl: String get() = baseUrl.ifBlank { providerEnum.defaultBaseUrl }.trimEnd('/')
}

/** A website you taught the app to use (text chat, image generator, or both). */
@Entity(tableName = "custom_sites")
data class CustomSite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val forText: Boolean,
    val forImages: Boolean,
    /** JSON with the taught selectors: box, send, reply, image (+ the frame host each lives in). */
    val config: String = "{}",
    val taughtAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One "generate image" request: the prompt, which AI it went to, and how it went. Kept as prompt history. */
@Entity(
    tableName = "image_requests",
    indices = [Index("bookId"), Index("chapterId")],
)
data class ImageRequest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long?,
    val chapterId: Long?,
    /** Reference to the selected paragraph(s), filled in edit mode (step 3). */
    val anchor: String? = null,
    val prompt: String,
    val target: String,
    val targetLabel: String,
    val status: String = "PENDING",
    val error: String? = null,
    val imageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** An image in the private gallery (file in app storage, never visible to the phone gallery). */
@Entity(
    tableName = "gallery_images",
    foreignKeys = [ForeignKey(
        entity = ImageRequest::class, parentColumns = ["id"], childColumns = ["requestId"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("requestId"), Index("bookId")],
)
data class GalleryImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestId: Long?,
    val bookId: Long?,
    val chapterId: Long?,
    val fileName: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val prompt: String,
    val source: String,
    val sourceLabel: String,
    /** True once placed in a chapter (step 3). */
    val used: Boolean = false,
    val savedToPhone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Messages of the in-app chat used when writing with an API (one conversation per book + session). */
@Entity(
    tableName = "api_chat_messages",
    foreignKeys = [ForeignKey(
        entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId")],
)
data class ApiChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Starts at 1; "New chat" increases it. */
    val session: Int,
    val role: String,
    val content: String,
    val target: String,
    val createdAt: Long = System.currentTimeMillis(),
)
