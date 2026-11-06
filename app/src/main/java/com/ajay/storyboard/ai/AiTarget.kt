package com.ajay.storyboard.ai

import com.ajay.storyboard.data.AiAction
import com.ajay.storyboard.data.AiService
import com.ajay.storyboard.data.ApiProfile
import com.ajay.storyboard.data.CustomSite
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Something the app can ask: a website with your login, an API key entry, or a taught site. */
sealed interface AiTarget {
    val id: String
    val label: String
    val forText: Boolean
    val forImages: Boolean
    /** True when the work happens in a web page (that can be shown to log in / solve a check). */
    val hasPage: Boolean

    data class Web(val service: AiService) : AiTarget {
        override val id get() = "web:${service.name}"
        override val label get() = service.label
        override val forText get() = true
        override val forImages get() = service in IMAGE_SERVICES
        override val hasPage get() = true
    }

    data class Api(val profile: ApiProfile) : AiTarget {
        override val id get() = "api:${profile.id}"
        override val label get() = "${profile.name} (API)"
        override val forText get() = true
        override val forImages get() = profile.providerEnum.canMakeImages && profile.imageModel.isNotBlank()
        override val hasPage get() = false
    }

    data class Site(val site: CustomSite) : AiTarget {
        override val id get() = "site:${site.id}"
        override val label get() = site.name
        override val forText get() = site.forText
        override val forImages get() = site.forImages
        override val hasPage get() = true
    }

    /** The web page this target works in (null for API keys). */
    val page: PageSpec?
        get() = when (this) {
            is Web -> PageSpec(id, label, service.homeUrl, service.origin, null, service)
            is Site -> PageSpec(id, label, site.url, "*", site.config, null)
            is Api -> null
        }

    companion object {
        /** Web chats that can draw pictures. */
        val IMAGE_SERVICES = setOf(AiService.CHATGPT, AiService.GEMINI, AiService.GROK)
    }
}

/**
 * A chat page the app drives: [injectOrigin] is where the scripts run ("*" = every frame, used for
 * taught sites whose box may sit in an iframe); [config] is the taught selectors JSON.
 */
data class PageSpec(
    val key: String,
    val label: String,
    val homeUrl: String,
    val injectOrigin: String,
    val config: String?,
    val service: AiService?,
) {
    /** https://host of the start page. */
    val origin: String get() = Regex("^https?://[^/]+").find(homeUrl)?.value ?: homeUrl

    /** True for an address of one particular conversation (to reopen the book's chat later). */
    fun isChatUrl(url: String): Boolean =
        service?.isChatUrl(url) ?: (url.startsWith(origin) && url.trimEnd('/') != homeUrl.trimEnd('/') && url.length > origin.length + 2)

    fun script(ctx: android.content.Context): String {
        val runner = com.ajay.storyboard.capture.ChatWebView.loadScript(ctx, "ai_runner.js")
        return when {
            service == AiService.DEEPSEEK -> com.ajay.storyboard.capture.ChatWebView.loadScript(ctx, "deepseek_capture.js") + "\n" + runner
            config != null -> "window.__sbSiteConfig = ${config.ifBlank { "{}" }};\n$runner"
            else -> runner
        }
    }
}

/** All AIs the app knows about right now (built-in sites + your API keys + taught sites). */
class AiCatalog(private val repo: Repository, private val settings: Settings, scope: CoroutineScope) {
    val profiles: StateFlow<List<ApiProfile>> =
        repo.aiConfig.observeProfiles().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val sites: StateFlow<List<CustomSite>> =
        repo.aiConfig.observeSites().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun all(): List<AiTarget> =
        AiService.entries.map { AiTarget.Web(it) } + profiles.value.map { AiTarget.Api(it) } + sites.value.map { AiTarget.Site(it) }

    fun forText(): List<AiTarget> = all().filter { it.forText }
    fun forImages(): List<AiTarget> = all().filter { it.forImages }
    fun forAction(action: AiAction): List<AiTarget> = if (action.needsImages) forImages() else forText()

    fun resolve(id: String): AiTarget? = when {
        id.startsWith("web:") -> runCatching { AiTarget.Web(AiService.valueOf(id.removePrefix("web:"))) }.getOrNull()
        id.startsWith("api:") -> profiles.value.firstOrNull { it.id == id.removePrefix("api:").toLongOrNull() }?.let { AiTarget.Api(it) }
        id.startsWith("site:") -> sites.value.firstOrNull { it.id == id.removePrefix("site:").toLongOrNull() }?.let { AiTarget.Site(it) }
        else -> null
    }

    /** The AI chosen for [action]; falls back to the built-in default if the chosen one was deleted. */
    fun targetFor(action: AiAction): AiTarget =
        resolve(settings.targetFor(action)) ?: resolve(action.defaultTarget)!!

    fun label(id: String): String = resolve(id)?.label ?: id
}
