package com.hivehd.chat.internal

import com.hivehd.chat.HiveChatException
import com.hivehd.chat.models.KnowledgeBaseArticle
import com.hivehd.chat.models.WidgetSettings
import com.hivehd.chat.models.mapObjects
import com.hivehd.chat.models.orEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * The handful of REST calls the chat needs alongside the socket.
 *
 * Everything here is unauthenticated by design — the widget key is public (it
 * ships in every storefront's HTML) and these are the same endpoints the web
 * widget calls. Nothing tenant-private is reachable with it.
 */
internal class HiveApiClient(
    private val host: String,
    private val widgetKey: String,
    private val client: OkHttpClient,
) {
    /* Both /livechat and /api/livechat are mounted server-side; the web
       widget uses the bare one. We take /api because that prefix is the one
       nginx is guaranteed to proxy (it is why Socket.IO was moved under it),
       so a merchant fronting Hive with their own edge config is far less
       likely to have a hole where the SDK's calls land. */
    private val base = "${host.trimEnd('/')}/api/livechat"

    suspend fun widgetSettings(): WidgetSettings = withContext(Dispatchers.IO) {
        WidgetSettings.from(getJson("$base/widget-config/$widgetKey"))
    }

    suspend fun searchArticles(query: String): List<KnowledgeBaseArticle> = withContext(Dispatchers.IO) {
        val url = "$base/widget-kb-search".toHttpUrl().newBuilder()
            .addQueryParameter("widget_key", widgetKey)
            .addQueryParameter("q", query)
            .build()
        getJson(url.toString()).optJSONArray("results").orEmpty()
            .mapObjects { KnowledgeBaseArticle.from(it) }
    }

    suspend fun article(id: String): KnowledgeBaseArticle = withContext(Dispatchers.IO) {
        KnowledgeBaseArticle.from(getJson("$base/widget-article/$id"))
    }

    suspend fun emailTranscript(sessionId: String, visitorToken: String, email: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("visitor_token", visitorToken)
                put("email", email)
            }
            val request = Request.Builder()
                .url("$base/widget-transcript/$sessionId/email")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(request)
            Unit
        }

    /**
     * Uploads a file and returns what the caller should then send as a
     * `__VISITOR_FILE__` message body.
     */
    suspend fun upload(bytes: ByteArray, filename: String, contentType: String): UploadedFile =
        withContext(Dispatchers.IO) {
            /* The server caps uploads at 5MB and answers 413 above it, but a
               phone photo is routinely 8-12MB, so checking here turns a
               wasted multi-megabyte upload over cellular into an instant
               error. */
            if (bytes.size > MAX_UPLOAD_BYTES) throw HiveChatException.FileTooLarge(MAX_UPLOAD_BYTES)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("widgetKey", widgetKey)
                .addFormDataPart(
                    "file", filename,
                    bytes.toRequestBody(contentType.toMediaTypeOrNull()),
                )
                .build()

            val json = execute(Request.Builder().url("$base/widget-upload").post(body).build())
            val path = json.optString("url").ifEmpty { throw HiveChatException.InvalidResponse }
            UploadedFile(
                url = if (path.startsWith("http")) path else host.trimEnd('/') + path,
                name = json.optString("name").ifEmpty { filename },
                contentType = json.optString("contentType").ifEmpty { contentType },
            )
        }

    data class UploadedFile(val url: String, val name: String, val contentType: String)

    private fun getJson(url: String): JSONObject = execute(Request.Builder().url(url).build())

    private fun execute(request: Request): JSONObject {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw HiveChatException.Network(e)
        }

        response.use {
            val raw = it.body.string()
            val json = runCatching { JSONObject(raw) }.getOrNull()

            if (!it.isSuccessful) {
                if (it.code == 413) throw HiveChatException.FileTooLarge(MAX_UPLOAD_BYTES)
                throw HiveChatException.Server(it.code, json?.optString("error")?.ifEmpty { null })
            }
            return json ?: throw HiveChatException.InvalidResponse
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
