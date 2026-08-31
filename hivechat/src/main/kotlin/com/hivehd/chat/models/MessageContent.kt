package com.hivehd.chat.models

import org.json.JSONObject

/**
 * The rich content a chat message can carry.
 *
 * Hive transports rich content as a *sentinel-prefixed* message body — the
 * body is a plain string beginning with a marker such as `__PRODUCT_CARD__`
 * followed by JSON, rather than a structured field. That predates this SDK
 * and is load-bearing across the web widget, the agent dashboard, email
 * rendering and the WhatsApp/Meta bridges, so the SDK parses the format
 * rather than asking the server to change it.
 *
 * Anything unrecognised becomes [Unsupported] with the body intact, so an app
 * shipped today survives a sentinel invented tomorrow instead of crashing or
 * dropping the message.
 */
sealed interface MessageContent {
    data class Text(val body: String) : MessageContent
    data class Product(val card: ProductCard) : MessageContent
    data class Article(val card: ArticleCard) : MessageContent
    data class Form(val form: ChatForm) : MessageContent
    data class SubmittedForm(val response: FormResponse) : MessageContent
    data class File(val attachment: Attachment) : MessageContent
    data class Unsupported(val raw: String) : MessageContent

    /** A one-liner for a notification or thread preview. */
    val previewText: String
        get() = when (this) {
            is Text -> body
            is Product -> card.title
            is Article -> card.title
            is Form -> form.name
            is SubmittedForm -> response.formName
            is File -> attachment.previewText
            is Unsupported -> raw
        }

    companion object {
        const val PRODUCT_CARD = "__PRODUCT_CARD__"
        const val ARTICLE_CARD = "__ARTICLE_CARD__"
        const val CHAT_FORM = "__CHAT_FORM__"
        const val FORM_RESPONSE = "__FORM_RESPONSE__"
        const val VISITOR_FILE = "__VISITOR_FILE__"
        const val META_ATTACHMENT = "__META_ATTACHMENT__"
        const val TERMINATOR = "__END__"

        /* Server bookkeeping that rides the same field as real content.
           OFFLINE_EMAIL_SENT:: is written when the offline form mails a
           transcript and the customer must never see it; the other two
           prefix a message they SHOULD see. Mirrors widget.js. */
        private val SUPPRESSED = listOf("OFFLINE_EMAIL_SENT::")
        private val STRIPPED = listOf("OFFLINE_HANDOFF::", "BOT_FALLBACK::")

        private val KNOWN_SENTINELS = listOf(
            PRODUCT_CARD, ARTICLE_CARD, CHAT_FORM, FORM_RESPONSE, VISITOR_FILE, META_ATTACHMENT,
        )

        /**
         * Parses a raw message body.
         *
         * Returns null for bodies the customer is never meant to see, so the
         * caller can drop the message rather than render a blank row.
         */
        fun parse(body: String): MessageContent? {
            if (SUPPRESSED.any { body.startsWith(it) }) return null

            var raw = body
            STRIPPED.firstOrNull { raw.startsWith(it) }?.let { raw = raw.removePrefix(it) }

            /* Malformed JSON deliberately falls through to plain text: a
               customer seeing a slightly odd message beats a customer seeing
               nothing, which is what throwing here would produce. */
            payloadAfter(raw, PRODUCT_CARD)?.let { json ->
                ProductCard.from(json)?.let { return Product(it) }
                return Text(raw)
            }
            payloadAfter(raw, ARTICLE_CARD)?.let { json ->
                ArticleCard.from(json)?.let { return Article(it) }
                return Text(raw)
            }
            payloadAfter(raw, CHAT_FORM)?.let { json ->
                ChatForm.from(json)?.let { return Form(it) }
                return Text(raw)
            }
            payloadAfter(raw, FORM_RESPONSE)?.let { json ->
                FormResponse.from(json)?.let { return SubmittedForm(it) }
                return Text(raw)
            }
            payloadAfter(raw, VISITOR_FILE)?.let { json ->
                Attachment.fromVisitorFile(json)?.let { return File(it) }
                return Text(raw)
            }
            payloadAfter(raw, META_ATTACHMENT)?.let { json ->
                Attachment.fromMetaAttachment(json)?.let { return File(it) }
                return Text(raw)
            }

            if (looksLikeUnknownSentinel(raw)) return Unsupported(raw)
            return Text(raw)
        }

        /** Strips the sentinel and any trailing `__END__`, leaving the JSON. */
        private fun payloadAfter(raw: String, sentinel: String): String? {
            if (!raw.startsWith(sentinel)) return null
            return raw.removePrefix(sentinel).removeSuffix(TERMINATOR).trim()
        }

        private fun looksLikeUnknownSentinel(raw: String): Boolean {
            if (!raw.startsWith("__")) return false
            if (KNOWN_SENTINELS.any { raw.startsWith(it) }) return false
            /* `__WHATEVER__…` is the shape every Hive sentinel takes.
               Anything else starting with two underscores is far more likely
               to be a customer typing them. */
            val rest = raw.drop(2)
            val end = rest.indexOf("__")
            if (end <= 0) return false
            return rest.take(end).all { it.isUpperCase() || it == '_' }
        }

        internal fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}
