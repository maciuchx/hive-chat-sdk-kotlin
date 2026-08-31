package com.hivehd.chat.models

import com.hivehd.chat.models.MessageContent.Companion.optStringOrNull
import org.json.JSONObject

/**
 * The merchant's widget settings, from
 * `GET /livechat/widget-config/:widgetKey`.
 *
 * The same document drives the web widget, so it carries browser-specific
 * styling (launcher offsets, URL visibility rules) that means nothing on a
 * phone. Only the useful fields are surfaced; the rest are ignored rather
 * than mapped into properties nobody can act on.
 */
data class WidgetSettings(
    /** False when the merchant switched the widget off. Hide your entry point. */
    val isEnabled: Boolean,
    val welcomeMessage: String,
    /** Shown when the team is away and the customer is asked for an email. */
    val offlineMessage: String,
    val placeholderText: String,
    val storeName: String,
    /** Brand colour as a hex string, e.g. `#6C3CE1`. */
    val brandColorHex: String,
    val gradientEndHex: String?,
    val agentName: String,
    val agentRole: String,
    val botName: String,
    val logoEmoji: String,
    /** Whether any agent is online, in-hours and reachable right now. */
    val isOnline: Boolean,
    val isPrechatRequired: Boolean,
    val isConsentRequired: Boolean,
    val consentText: String?,
    val featuredArticles: List<KnowledgeBaseArticle>,
) {
    companion object {
        fun from(json: JSONObject): WidgetSettings {
            /* A disabled widget answers `{ widget_enabled: false }` and
               nothing else, so every other field needs a default or the one
               response that most needs understanding fails to parse. */
            return WidgetSettings(
                isEnabled = json.optBoolean("widget_enabled", true),
                welcomeMessage = json.optStringOrNull("welcome_message") ?: "Hi! How can we help?",
                offlineMessage = json.optStringOrNull("offline_message")
                    ?: "Leave a message and we'll reply by email.",
                placeholderText = json.optStringOrNull("placeholder_text") ?: "Type your message...",
                storeName = json.optStringOrNull("store_name") ?: "",
                brandColorHex = json.optStringOrNull("widget_color") ?: "#6C3CE1",
                gradientEndHex = json.optStringOrNull("gradient_end"),
                agentName = json.optStringOrNull("agent_name") ?: "Support",
                agentRole = json.optStringOrNull("agent_role") ?: "",
                botName = json.optStringOrNull("bot_name") ?: "Assistant",
                logoEmoji = json.optStringOrNull("logo_emoji") ?: "💬",
                isOnline = json.optBoolean("is_online", false),
                isPrechatRequired = json.optBoolean("prechat_enabled", false),
                isConsentRequired = json.optBoolean("consent_required", false),
                consentText = json.optStringOrNull("consent_text"),
                featuredArticles = json.optJSONArray("featured_kb").orEmpty()
                    .mapObjects { KnowledgeBaseArticle.from(it) },
            )
        }
    }
}

/** A help-centre article. */
data class KnowledgeBaseArticle(
    val id: String,
    val title: String,
    val excerpt: String?,
    val slug: String?,
    /**
     * Rendered HTML body. Only populated by `HiveChat.article(id)` — list and
     * search endpoints return excerpts only.
     */
    val bodyHtml: String?,
) {
    companion object {
        fun from(json: JSONObject) = KnowledgeBaseArticle(
            id = json.optString("id"),
            title = json.optString("title"),
            excerpt = json.optStringOrNull("excerpt"),
            slug = json.optStringOrNull("slug"),
            /* `html` is what GET /widget-article/:id calls it; absent on list rows. */
            bodyHtml = json.optStringOrNull("html"),
        )
    }
}

/** A prompt to rate the conversation. */
data class SatisfactionRequest(val prompt: String)

/** A one-time link that moves the conversation to another device. */
data class DeviceHandoff(
    val url: String,
    val code: String,
    /** A ready-made QR of [url], as SVG markup. */
    val qrCodeSvg: String?,
    val expiresInMinutes: Int,
)

/** A preview of a link someone posted in the chat. */
data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
) {
    companion object {
        fun from(json: JSONObject): LinkPreview? {
            val url = json.optStringOrNull("url") ?: return null
            return LinkPreview(
                url = url,
                title = json.optStringOrNull("title"),
                description = json.optStringOrNull("description"),
                imageUrl = json.optStringOrNull("image_url"),
                siteName = json.optStringOrNull("site_name"),
            )
        }
    }
}
