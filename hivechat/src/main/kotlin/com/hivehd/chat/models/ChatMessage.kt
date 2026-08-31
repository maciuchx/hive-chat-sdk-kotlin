package com.hivehd.chat.models

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One message in a conversation. */
data class ChatMessage(
    /** Server id, or a locally-minted id while the message is in flight. */
    val id: String,
    val sender: Sender,
    /** An agent's name, the bot's name, or null. */
    val senderName: String?,
    val content: MessageContent,
    val createdAt: Date,
    /** Whether the other side has read this. Only meaningful on outgoing messages. */
    val isRead: Boolean = false,
    val reactions: List<Reaction> = emptyList(),
    val delivery: Delivery = Delivery.SENT,
) {
    enum class Sender {
        /** The person using your app. */
        VISITOR,

        /**
         * A human agent, or the bot. The server deliberately presents the bot
         * to customers as an agent and this SDK does not second-guess it — a
         * thread that distinguished them would leak which replies were
         * automated.
         */
        AGENT,

        /** Server narration: "Chat ended", "Connecting you with the team". */
        SYSTEM;

        companion object {
            fun from(wire: String?): Sender = when (wire) {
                "visitor" -> VISITOR
                "agent", "bot" -> AGENT
                else -> SYSTEM
            }
        }
    }

    /** Local delivery state. Server messages are always [SENT]. */
    enum class Delivery { SENDING, SENT, FAILED }

    companion object {
        fun from(wire: JSONObject, host: String): ChatMessage? {
            val body = wire.optString("body")
            val content = MessageContent.parse(body) ?: return null

            return ChatMessage(
                id = wire.optString("id").ifEmpty { "srv_${System.nanoTime()}" },
                sender = Sender.from(wire.optString("sender_type")),
                senderName = wire.optString("sender_name").ifEmpty { null },
                content = content.resolveRelativeUrls(host),
                createdAt = parseDate(wire.optString("created_at")),
                isRead = wire.optBoolean("read", false),
                reactions = Reaction.list(wire.optJSONArray("reactions")),
            )
        }

        /*
         * Dates cross the wire as ISO-8601 WITH fractional seconds from the
         * socket path (`new Date().toISOString()`) and WITHOUT them from
         * MySQL on the restore path. One format cannot read both, and the
         * failure is silent — every restored message stamped 1970 and the
         * thread sorted inside out — so try both and fall back to now.
         */
        private val FORMATS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
        )

        fun parseDate(raw: String?): Date {
            if (raw.isNullOrEmpty()) return Date()
            for (pattern in FORMATS) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(raw)
                }.getOrNull()
                if (parsed != null) return parsed
            }
            return Date()
        }
    }
}

/** An emoji reaction on a message. */
data class Reaction(
    val emoji: String,
    val count: Int,
    /** Whether this device's customer is one of the reactors. */
    val isMine: Boolean,
) {
    companion object {
        fun list(array: org.json.JSONArray?): List<Reaction> =
            array.orEmpty().mapObjects { o ->
                Reaction(
                    emoji = o.optString("emoji"),
                    count = o.optInt("count", 1),
                    isMine = o.optBoolean("mine", false),
                )
            }.filter { it.emoji.isNotEmpty() }
    }
}

/**
 * Rewrites host-relative attachment URLs (`/uploads/livechat/…`) into absolute
 * ones. The server emits them relative because the web widget is same-origin;
 * a native app has no origin to be relative to.
 */
internal fun MessageContent.resolveRelativeUrls(host: String): MessageContent {
    if (this !is MessageContent.File) return this
    val url = attachment.url ?: return this
    if (url.startsWith("http://") || url.startsWith("https://")) return this
    val base = host.trimEnd('/')
    val path = if (url.startsWith("/")) url else "/$url"
    return MessageContent.File(attachment.copy(url = base + path))
}
