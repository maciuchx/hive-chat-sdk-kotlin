package com.hivehd.chat.models

import com.hivehd.chat.models.MessageContent.Companion.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** A product the bot or an agent dropped into the conversation. */
data class ProductCard(
    val title: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val buyUrl: String? = null,
    /** Lowest in-stock variant price, decided server-side where possible. */
    val price: BigDecimal? = null,
    val variants: List<Variant> = emptyList(),
    val message: String? = null,
    val agentName: String? = null,
) {
    data class Variant(
        val title: String,
        val price: BigDecimal? = null,
        val sku: String? = null,
        val available: Boolean? = null,
        val inventoryQuantity: Int? = null,
    )

    companion object {
        fun from(json: String): ProductCard? = runCatching {
            val o = JSONObject(json)
            val variants = o.optJSONArray("variants").orEmpty().mapObjects { v ->
                Variant(
                    title = v.optString("title"),
                    price = v.decimalOrNull("price"),
                    sku = v.optStringOrNull("sku"),
                    available = if (v.has("available")) v.optBoolean("available") else null,
                    inventoryQuantity = if (v.has("inventory_quantity")) v.optInt("inventory_quantity") else null,
                )
            }
            ProductCard(
                title = o.optString("title"),
                description = o.optStringOrNull("description"),
                imageUrl = o.optStringOrNull("image_url"),
                buyUrl = o.optStringOrNull("buy_url"),
                /* No top-level price means an older card that expects the
                   client to take the cheapest variant — the same fallback
                   widget.js applies. */
                price = o.decimalOrNull("price")
                    ?: variants.mapNotNull { it.price }.filter { it > BigDecimal.ZERO }.minOrNull(),
                variants = variants,
                message = o.optStringOrNull("message"),
                agentName = o.optStringOrNull("agent_name"),
            )
        }.getOrNull()
    }
}

/** A knowledge-base article surfaced in the thread. */
data class ArticleCard(
    val id: String,
    val title: String,
    val excerpt: String? = null,
    val slug: String? = null,
    val agentName: String? = null,
) {
    companion object {
        fun from(json: String): ArticleCard? = runCatching {
            val o = JSONObject(json)
            ArticleCard(
                /* Ids are strings server-side but a hand-written card can
                   carry a number; optString handles both. */
                id = o.optString("id"),
                title = o.optString("title"),
                excerpt = o.optStringOrNull("excerpt"),
                slug = o.optStringOrNull("slug"),
                agentName = o.optStringOrNull("agent_name"),
            )
        }.getOrNull()
    }
}

/** A form an agent pushed into the chat. */
data class ChatForm(
    val formId: String?,
    val name: String,
    val description: String?,
    val fields: List<Field>,
) {
    data class Field(
        val key: String,
        val label: String,
        val type: FieldType,
        val placeholder: String? = null,
        val required: Boolean = false,
        val options: List<String> = emptyList(),
    )

    /**
     * Field kinds the dashboard's form builder can produce. An unknown kind
     * becomes [TEXT] so a type invented later renders as a plain input rather
     * than breaking a card on an app that cannot be updated today.
     */
    enum class FieldType {
        TEXT, EMAIL, NUMBER, TEXTAREA, SELECT, CHECKBOX;

        companion object {
            fun from(raw: String?): FieldType =
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TEXT
        }
    }

    companion object {
        fun from(json: String): ChatForm? = runCatching {
            val o = JSONObject(json)
            ChatForm(
                formId = o.optStringOrNull("form_id"),
                name = o.optString("name").ifEmpty { "Quick form" },
                description = o.optStringOrNull("description"),
                fields = o.optJSONArray("fields").orEmpty().mapObjects { f ->
                    Field(
                        key = f.optString("key"),
                        label = f.optString("label"),
                        type = FieldType.from(f.optStringOrNull("type")),
                        placeholder = f.optStringOrNull("placeholder"),
                        required = f.optBoolean("required", false),
                        options = f.optJSONArray("options").orEmpty().mapStrings(),
                    )
                },
            )
        }.getOrNull()
    }
}

/** A submitted form, as it appears in the thread afterwards. */
data class FormResponse(
    val formId: String?,
    val formName: String,
    val entries: List<Entry>,
) {
    data class Entry(val key: String, val label: String, val value: String)

    fun toJson(): String = JSONObject().apply {
        put("form_id", formId ?: JSONObject.NULL)
        put("form_name", formName)
        put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("key", entry.key)
                    put("label", entry.label)
                    put("value", entry.value)
                })
            }
        })
    }.toString()

    companion object {
        fun from(json: String): FormResponse? = runCatching {
            val o = JSONObject(json)
            FormResponse(
                formId = o.optStringOrNull("form_id"),
                formName = o.optString("form_name").ifEmpty { "Form" },
                entries = o.optJSONArray("entries").orEmpty().mapObjects { e ->
                    Entry(
                        key = e.optString("key"),
                        label = e.optString("label"),
                        value = e.optString("value"),
                    )
                },
            )
        }.getOrNull()
    }
}

/** A file in the conversation, sent by either side. */
data class Attachment(
    val kind: Kind,
    /**
     * Absolute URL. Server payloads are host-relative (`/uploads/livechat/…`)
     * and are resolved against the configured host before you see them.
     */
    val url: String?,
    val name: String,
    val contentType: String? = null,
    /** True while a local file is still uploading. Never set on a server message. */
    val isUploading: Boolean = false,
) {
    enum class Kind { IMAGE, VIDEO, AUDIO, FILE }

    val previewText: String
        get() = when (kind) {
            Kind.IMAGE -> "📷 Photo"
            Kind.VIDEO -> "🎥 Video"
            Kind.AUDIO -> "🎤 Audio"
            Kind.FILE -> "📄 $name"
        }

    companion object {
        fun kindFor(contentType: String?): Kind = when {
            contentType == null -> Kind.FILE
            contentType.startsWith("image/", true) -> Kind.IMAGE
            contentType.startsWith("video/", true) -> Kind.VIDEO
            contentType.startsWith("audio/", true) -> Kind.AUDIO
            else -> Kind.FILE
        }

        fun fromVisitorFile(json: String): Attachment? = runCatching {
            val o = JSONObject(json)
            val contentType = o.optStringOrNull("contentType")
            Attachment(
                kind = kindFor(contentType),
                url = o.optStringOrNull("url"),
                name = o.optStringOrNull("name") ?: "file",
                contentType = contentType,
                isUploading = o.optBoolean("uploading", false),
            )
        }.getOrNull()

        fun fromMetaAttachment(json: String): Attachment? = runCatching {
            val o = JSONObject(json)
            Attachment(
                kind = runCatching { Kind.valueOf(o.optString("type").uppercase()) }.getOrDefault(Kind.FILE),
                url = o.optStringOrNull("url"),
                name = o.optStringOrNull("name") ?: "file",
            )
        }.getOrNull()
    }
}

// ── JSON helpers ────────────────────────────────────────────────────────────

internal fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

internal fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it) }.map(transform)

internal fun JSONArray.mapStrings(): List<String> =
    (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }

/**
 * Prices arrive as a JSON number from the bot and as a string from the agent
 * product picker ("29.99"). Reading one shape only meant whichever half you
 * did not test dropped the price silently.
 */
internal fun JSONObject.decimalOrNull(key: String): BigDecimal? {
    if (isNull(key)) return null
    val raw = optString(key).takeIf { it.isNotEmpty() } ?: return null
    return runCatching { BigDecimal(raw.filter { it.isDigit() || it == '.' || it == '-' }) }.getOrNull()
}
