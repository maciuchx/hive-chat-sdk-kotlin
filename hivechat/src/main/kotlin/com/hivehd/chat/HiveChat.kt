package com.hivehd.chat

import android.util.Log
import com.hivehd.chat.internal.HiveApiClient
import com.hivehd.chat.internal.SocketIOConnection
import com.hivehd.chat.models.Attachment
import com.hivehd.chat.models.ChatForm
import com.hivehd.chat.models.ChatMessage
import com.hivehd.chat.models.DeviceHandoff
import com.hivehd.chat.models.FormResponse
import com.hivehd.chat.models.KnowledgeBaseArticle
import com.hivehd.chat.models.LinkPreview
import com.hivehd.chat.models.MessageContent
import com.hivehd.chat.models.Reaction
import com.hivehd.chat.models.SatisfactionRequest
import com.hivehd.chat.models.WidgetSettings
import com.hivehd.chat.models.mapObjects
import com.hivehd.chat.models.orEmpty
import com.hivehd.chat.models.resolveRelativeUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A live conversation between the person using your app and the merchant's
 * support team.
 *
 * ```kotlin
 * val chat = HiveChat(
 *     HiveChatConfiguration(
 *         widgetKey = "wk_live_…",
 *         tokenStore = VisitorTokenStore.sharedPreferences(context),
 *     )
 * )
 * chat.identify(name = customer.name, email = customer.email)
 * chat.start()
 * chat.send("Where is my order?")
 * ```
 *
 * Collect the [StateFlow]s from Compose, or drop in `HiveChatScreen` from the
 * `hivechat-ui` module.
 *
 * Hold one instance for the app's lifetime (a `ViewModel` scoped to the
 * activity, or a singleton). One recreated per screen drops the socket, loses
 * the unread count and looks to agents like a stream of new visitors.
 */
class HiveChat(
    private val configuration: HiveChatConfiguration,
    httpClient: OkHttpClient = defaultHttpClient(),
) {
    // ── Observable state ────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** The conversation, oldest first, including unsent local echoes. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _widgetSettings = MutableStateFlow<WidgetSettings?>(null)

    /** Branding, welcome text, whether the team is online. Null until [start]. */
    val widgetSettings: StateFlow<WidgetSettings?> = _widgetSettings.asStateFlow()

    private val _isAgentTyping = MutableStateFlow(false)
    val isAgentTyping: StateFlow<Boolean> = _isAgentTyping.asStateFlow()

    private val _typingAgentName = MutableStateFlow<String?>(null)
    val typingAgentName: StateFlow<String?> = _typingAgentName.asStateFlow()

    private val _isTeamOnline = MutableStateFlow(false)

    /** Whether any human is available. The bot answers regardless. */
    val isTeamOnline: StateFlow<Boolean> = _isTeamOnline.asStateFlow()

    private val _queuePosition = MutableStateFlow<Int?>(null)

    /** 1-based place in the queue while waiting for a human; null when not queued. */
    val queuePosition: StateFlow<Int?> = _queuePosition.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)

    /** Drive your badge off this. Cleared by [markRead]. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _offlinePrompt = MutableStateFlow<String?>(null)

    /** Set when the team is away and the customer should be asked for an email. */
    val offlinePrompt: StateFlow<String?> = _offlinePrompt.asStateFlow()

    private val _satisfactionRequest = MutableStateFlow<SatisfactionRequest?>(null)

    /** Set when the server asks for a rating. Answer with [submitCsat]. */
    val satisfactionRequest: StateFlow<SatisfactionRequest?> = _satisfactionRequest.asStateFlow()

    private val _hasEnded = MutableStateFlow(false)

    /** True once closed by either side. The customer's next message reopens it. */
    val hasEnded: StateFlow<Boolean> = _hasEnded.asStateFlow()

    private val _linkPreviews = MutableStateFlow<Map<String, List<LinkPreview>>>(emptyMap())
    val linkPreviews: StateFlow<Map<String, List<LinkPreview>>> = _linkPreviews.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)

    /**
     * The conversation's id, once one exists — null until the customer sends
     * their first message. Hive creates sessions lazily so that merely opening
     * a chat screen does not spawn an empty ticket for agents.
     */
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    /** Called when an agent proactively invites the customer into a chat. */
    var onProactiveInvitation: ((String) -> Unit)? = null

    /** Called when a device-handoff code turned out to be spent or expired. */
    var onHandoffFailed: (() -> Unit)? = null

    // ── Internals ───────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = HiveApiClient(configuration.host, configuration.widgetKey, httpClient)
    private val httpClient = httpClient
    private var connection: SocketIOConnection? = null

    private var visitorToken: String
    private var visitorName: String? = null
    private var visitorEmail: String? = null
    private var consentText: String? = null
    private var handoffCode: String? = null

    /**
     * Messages typed while the socket was down, flushed in order on reconnect.
     * A chat that silently eats what you typed on the Underground is worse
     * than one that admits it is offline.
     */
    private val outbox = mutableListOf<Pair<String, String>>()

    private var typingResetJob: kotlinx.coroutines.Job? = null

    init {
        visitorToken = configuration.tokenStore.load() ?: run {
            /* Same shape the web widget mints, so a customer who used the
               website and then the app is not obviously two different people
               in the agent's visitor list. */
            val random = UUID.randomUUID().toString().replace("-", "").take(8)
            val stamp = java.lang.Long.toString(System.currentTimeMillis() / 1000, 36)
            "v_$random$stamp".also { configuration.tokenStore.save(it) }
        }
    }

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * Tells Hive who the customer is, so the agent sees a name rather than
     * "Visitor" and the conversation threads onto their customer record.
     *
     * Hive does not currently verify this — anything your app sends is taken
     * at face value. Treat it as a display convenience, not as proof of who
     * the customer is.
     */
    fun identify(name: String? = null, email: String? = null) {
        name?.takeIf { it.isNotBlank() }?.let { visitorName = it }
        email?.takeIf { it.isNotBlank() }?.let { visitorEmail = it }

        if (_connectionState.value != ConnectionState.Connected) return
        connection?.emit("visitor:info", JSONObject().apply {
            put("name", visitorName.orEmpty())
            put("email", visitorEmail.orEmpty())
        })
    }

    /**
     * Records that the customer accepted the merchant's consent wording. Pass
     * the exact text they saw — it is stored as the audit trail. Set before
     * [connect].
     */
    fun recordConsent(text: String) { consentText = text }

    /**
     * Adopts a conversation started elsewhere, from a Hive "continue this chat
     * here" link (`?hive_chat=CODE`). Set before connecting; single use.
     */
    fun redeemHandoffCode(code: String) { handoffCode = code.uppercase() }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Loads the merchant's settings and opens the socket. */
    suspend fun start() {
        loadSettings()
        connect()
    }

    /**
     * Fetches the widget settings without connecting — useful to decide
     * whether to show a chat entry point at all. Returns null on failure.
     */
    suspend fun loadSettings(): WidgetSettings? = runCatching { api.widgetSettings() }
        .onSuccess {
            _widgetSettings.value = it
            _isTeamOnline.value = it.isOnline
        }
        .onFailure { log("settings load failed: ${it.message}") }
        .getOrNull()

    /** Opens the socket. Safe to call repeatedly. */
    fun connect() {
        connection?.let { it.reconnectNow(); return }

        /* The merchant switched the widget off. Connecting would be refused
           anyway; failing here says why. */
        _widgetSettings.value?.takeIf { !it.isEnabled }?.let {
            _connectionState.value = ConnectionState.Failed("This chat widget is disabled.")
            return
        }

        val socket = SocketIOConnection(
            host = configuration.host,
            namespace = NAMESPACE,
            client = httpClient,
            authProvider = ::buildAuth,
        )
        socket.onStateChange = { state -> scope.launch { handleStateChange(state) } }
        socket.onEvent = { event, args -> scope.launch { handleEvent(event, args) } }
        connection = socket
        socket.connect()
    }

    /**
     * Closes the socket. The conversation stays open — this is "stop
     * listening", not "end chat".
     */
    fun disconnect() {
        connection?.disconnect()
        connection = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Drops and reopens the socket. Call when your app returns to the
     * foreground: Android suspends sockets in the background and a dead one
     * can look alive until you try to write.
     */
    fun onAppForegrounded() {
        connection?.reconnectNow()
    }

    /** Releases the coroutine scope. Call from your ViewModel's `onCleared`. */
    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun buildAuth(): JSONObject = JSONObject().apply {
        put("widgetKey", configuration.widgetKey)
        put("visitorToken", visitorToken)
        put("name", visitorName.orEmpty())
        put("email", visitorEmail.orEmpty())
        handoffCode?.let { put("handoffCode", it) }
        consentText?.let { put("consentText", it) }
        /* pageUrl/pageTitle are web concepts the agent panel shows as "what
           they were looking at". Sending the package name is the honest
           native equivalent — it tells the agent which app this is without
           pretending to be a URL they can open. */
        put("pageUrl", "app://android")
        put("pageTitle", "Android app")
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /**
     * Sends a message. Appears in [messages] immediately as
     * [ChatMessage.Delivery.SENDING] and is queued if the socket is down.
     */
    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        enqueue(body, MessageContent.Text(body))
    }

    /**
     * Uploads a file and sends it as a message. The customer sees it straight
     * away; a failed upload marks the echo [ChatMessage.Delivery.FAILED] and
     * rethrows.
     */
    suspend fun send(fileBytes: ByteArray, filename: String, contentType: String) {
        val localId = "local_${UUID.randomUUID()}"
        appendLocalEcho(
            localId,
            MessageContent.File(
                Attachment(
                    kind = Attachment.kindFor(contentType),
                    url = null,
                    name = filename,
                    contentType = contentType,
                    isUploading = true,
                )
            ),
        )

        try {
            val uploaded = api.upload(fileBytes, filename, contentType)
            val payload = JSONObject().apply {
                put("url", uploaded.url)
                put("contentType", uploaded.contentType)
                put("name", uploaded.name)
            }
            /* Replace the optimistic echo rather than adding a second row —
               the server's own copy arrives with a real id and we drop ours
               when it does. */
            removeLocalEcho(localId)
            enqueue(
                MessageContent.VISITOR_FILE + payload,
                MessageContent.File(
                    Attachment(
                        kind = Attachment.kindFor(uploaded.contentType),
                        url = uploaded.url,
                        name = uploaded.name,
                        contentType = uploaded.contentType,
                    )
                ),
            )
        } catch (e: Throwable) {
            markLocalEcho(localId, ChatMessage.Delivery.FAILED)
            throw e
        }
    }

    /** Submits a form an agent pushed into the chat. */
    fun submit(form: ChatForm, values: Map<String, String>) {
        val response = FormResponse(
            formId = form.formId,
            formName = form.name,
            entries = form.fields.map {
                FormResponse.Entry(it.key, it.label, values[it.key].orEmpty())
            },
        )
        enqueue(
            MessageContent.FORM_RESPONSE + response.toJson() + MessageContent.TERMINATOR,
            MessageContent.SubmittedForm(response),
        )
    }

    /**
     * Tells the agent the customer is typing. Call on every keystroke; the SDK
     * throttles and clears the indicator.
     *
     * [previewText] shows the agent what is being typed, live. It is a real
     * feature — agents start looking things up early — and also a surprising
     * one, so it is only sent when you pass it.
     */
    fun setTyping(isTyping: Boolean, previewText: String? = null) {
        connection?.emit("chat:typing", JSONObject().apply {
            put("typing", isTyping)
            put("text", previewText.orEmpty())
        })

        typingResetJob?.cancel()
        if (!isTyping) return
        typingResetJob = scope.launch {
            delay(3_000)
            connection?.emit("chat:typing", JSONObject().apply {
                put("typing", false)
                put("text", "")
            })
        }
    }

    /** Marks everything the team sent as read. Call when the chat is visible. */
    fun markRead() {
        _unreadCount.value = 0
        _messages.update { list ->
            list.map { if (it.sender != ChatMessage.Sender.VISITOR) it.copy(isRead = true) else it }
        }
        connection?.emit("chat:read")
    }

    /** Asks to be put through to a human. */
    fun requestHuman() = connection?.emit("chat:request-human").let { }

    /** Ends the conversation. The customer's next message reopens it. */
    fun endChat() = connection?.emit("chat:end").let { }

    /** Adds or removes an emoji reaction. */
    fun toggleReaction(emoji: String, messageId: String) {
        connection?.emit("chat:reaction:toggle", JSONObject().apply {
            put("messageId", messageId)
            put("emoji", emoji)
        })
    }

    /** Answers a [satisfactionRequest]. */
    fun submitCsat(rating: Int, comment: String? = null) {
        connection?.emit("csat:submit", JSONObject().apply {
            put("rating", rating.coerceIn(1, 5))
            put("comment", comment.orEmpty())
        })
        _satisfactionRequest.value = null
    }

    /** Gives the customer's email to the team — what the offline form collects. */
    fun provideEmail(email: String) {
        visitorEmail = email
        connection?.emit("visitor:email", JSONObject().put("email", email))
        _offlinePrompt.value = null
    }

    /** Mints a one-time link that moves this conversation to another device. */
    suspend fun createDeviceHandoff(): DeviceHandoff {
        val socket = connection ?: throw HiveChatException.NoActiveSession
        if (_sessionId.value == null) throw HiveChatException.NoActiveSession

        return suspendCancellableCoroutine { continuation ->
            socket.emitWithAck("chat:handoff:create") { response ->
                val payload = response.optJSONObject(0)
                val url = payload?.optString("url").orEmpty()
                if (payload?.optBoolean("ok") == true && url.isNotEmpty()) {
                    continuation.resume(
                        DeviceHandoff(
                            url = url,
                            code = payload.optString("code"),
                            qrCodeSvg = payload.optString("qrSvg").ifEmpty { null },
                            expiresInMinutes = payload.optInt("expiresInMinutes", 5),
                        )
                    )
                } else {
                    continuation.resumeWithException(
                        HiveChatException.Server(0, payload?.optString("error")?.ifEmpty { null })
                    )
                }
            }
        }
    }

    // ── Help centre ─────────────────────────────────────────────────────────

    suspend fun searchArticles(query: String): List<KnowledgeBaseArticle> = api.searchArticles(query)

    suspend fun article(id: String): KnowledgeBaseArticle = api.article(id)

    suspend fun emailTranscript(email: String) {
        val session = _sessionId.value ?: throw HiveChatException.NoActiveSession
        api.emailTranscript(session, visitorToken, email)
    }

    // ── Outbound plumbing ───────────────────────────────────────────────────

    private fun enqueue(body: String, echo: MessageContent) {
        val localId = "local_${UUID.randomUUID()}"
        appendLocalEcho(localId, echo)

        val socket = connection
        if (_connectionState.value != ConnectionState.Connected || socket == null) {
            outbox += localId to body
            return
        }
        socket.emit("chat:message", JSONObject().put("body", body))
        markLocalEcho(localId, ChatMessage.Delivery.SENT)
    }

    private fun flushOutbox() {
        val socket = connection ?: return
        if (outbox.isEmpty()) return
        val queued = outbox.toList()
        outbox.clear()
        queued.forEach { (localId, body) ->
            socket.emit("chat:message", JSONObject().put("body", body))
            markLocalEcho(localId, ChatMessage.Delivery.SENT)
        }
    }

    private fun appendLocalEcho(id: String, content: MessageContent) {
        _messages.update {
            it + ChatMessage(
                id = id,
                sender = ChatMessage.Sender.VISITOR,
                senderName = visitorName,
                content = content,
                createdAt = Date(),
                delivery = ChatMessage.Delivery.SENDING,
            )
        }
    }

    private fun markLocalEcho(id: String, state: ChatMessage.Delivery) {
        _messages.update { list -> list.map { if (it.id == id) it.copy(delivery = state) else it } }
    }

    private fun removeLocalEcho(id: String) {
        _messages.update { list -> list.filterNot { it.id == id } }
    }

    // ── Inbound events ──────────────────────────────────────────────────────

    private fun handleStateChange(state: ConnectionState) {
        _connectionState.value = state
        if (state != ConnectionState.Connected) {
            if (state is ConnectionState.Disconnected || state is ConnectionState.Reconnecting) {
                _isAgentTyping.value = false
            }
            return
        }
        /* Re-assert identity on every connect. The handshake carries it too,
           but a name captured after the socket opened would otherwise not
           reach the agent panel until the next reconnect. */
        if (visitorName != null || visitorEmail != null) identify()
        flushOutbox()
    }

    private fun handleEvent(event: String, args: JSONArray) {
        val payload = args.optJSONObject(0) ?: JSONObject()
        log("← $event")

        when (event) {
            "chat:session" -> _sessionId.value = payload.optString("sessionId").ifEmpty { null }

            "chat:restore" -> handleRestore(payload)

            "chat:message" -> handleIncomingMessage(payload)

            "chat:typing" -> {
                _isAgentTyping.value = payload.optBoolean("typing", false)
                _typingAgentName.value = payload.optString("name").ifEmpty { null }
            }

            "chat:read" -> _messages.update { list ->
                list.map { if (it.sender == ChatMessage.Sender.VISITOR) it.copy(isRead = true) else it }
            }

            "chat:transfer" -> payload.optString("message").ifEmpty { null }
                ?.let { appendSystemMessage(it) }

            "chat:offline" -> _offlinePrompt.value = payload.optString("message").ifEmpty { null }

            "chat:queue-position" -> _queuePosition.value =
                payload.optInt("position").takeIf { it > 0 }

            "chat:ended" -> {
                _hasEnded.value = true
                _queuePosition.value = null
            }

            "agents:status" -> _isTeamOnline.value = payload.optBoolean("online", false)

            "visitor:name" -> payload.optString("name").ifEmpty { null }?.let { visitorName = it }

            /* An agent reached out first. The invitation text arrives as a
               normal chat:message straight after, so nothing to append here —
               but the host app may want to open the chat. */
            "visitor:invite" -> onProactiveInvitation?.invoke(payload.optString("message"))

            "csat:request" -> _satisfactionRequest.value = SatisfactionRequest(
                payload.optString("prompt").ifEmpty { "How would you rate this conversation?" }
            )

            "csat:received" -> _satisfactionRequest.value = null

            "chat:reactions" -> handleReactions(payload)

            "chat:message:enriched" -> handleEnrichment(payload)

            "chat:handoff:adopted" -> {
                payload.optString("visitorToken").ifEmpty { null }?.let {
                    visitorToken = it
                    configuration.tokenStore.save(it)
                }
                payload.optString("sessionId").ifEmpty { null }?.let { _sessionId.value = it }
                handoffCode = null
            }

            "chat:handoff:invalid" -> {
                handoffCode = null
                onHandoffFailed?.invoke()
            }

            else -> log("unhandled event $event")
        }
    }

    private fun handleRestore(payload: JSONObject) {
        _sessionId.value = payload.optString("sessionId").ifEmpty { null }
        _hasEnded.value = payload.optString("status") == "ended"

        val restored = payload.optJSONArray("messages").orEmpty()
            .mapObjects { ChatMessage.from(it, configuration.host) }
            .filterNotNull()

        /* Rebuild rather than merge. The server's copy is authoritative and
           this fires on every reconnect, so appending would duplicate the
           whole thread each time the socket blinked. Anything unsent is
           re-appended: it is not on the server yet, and dropping it would
           make the customer's own words vanish. */
        val unsent = _messages.value.filter {
            it.delivery != ChatMessage.Delivery.SENT && it.sender == ChatMessage.Sender.VISITOR
        }
        _messages.value = restored + unsent.filterNot { pending ->
            restored.any { it.content == pending.content }
        }

        if (configuration.marksMessagesReadAutomatically) {
            markRead()
        } else {
            _unreadCount.value = restored.count { it.sender != ChatMessage.Sender.VISITOR && !it.isRead }
        }
    }

    private fun handleIncomingMessage(payload: JSONObject) {
        val message = ChatMessage.from(payload, configuration.host) ?: return

        /* The server echoes the customer's own messages back. Drop the
           optimistic copy in favour of the server's, matching on content
           because the ids differ by construction. */
        if (message.sender == ChatMessage.Sender.VISITOR) {
            val index = _messages.value.indexOfLast {
                it.sender == ChatMessage.Sender.VISITOR &&
                    it.content == message.content &&
                    it.id.startsWith("local_")
            }
            if (index >= 0) {
                _messages.update { list -> list.toMutableList().also { it[index] = message } }
                return
            }
        }
        if (_messages.value.any { it.id == message.id }) return

        _messages.update { it + message }
        _isAgentTyping.value = false
        _queuePosition.value = null

        if (message.sender != ChatMessage.Sender.VISITOR) {
            if (configuration.marksMessagesReadAutomatically) markRead()
            else _unreadCount.update { it + 1 }
        }
    }

    private fun handleReactions(payload: JSONObject) {
        val messageId = payload.optString("message_id").ifEmpty { return }
        val reactions = Reaction.list(payload.optJSONArray("reactions"))
        _messages.update { list ->
            list.map { if (it.id == messageId) it.copy(reactions = reactions) else it }
        }
    }

    private fun handleEnrichment(payload: JSONObject) {
        val messageId = payload.optString("messageId").ifEmpty { return }
        val previews = payload.optJSONObject("metadata")?.optJSONArray("url_previews")
            .orEmpty().mapObjects { LinkPreview.from(it) }.filterNotNull()
        if (previews.isEmpty()) return
        _linkPreviews.update { it + (messageId to previews) }
    }

    private fun appendSystemMessage(text: String) {
        _messages.update {
            it + ChatMessage(
                id = "system_${UUID.randomUUID()}",
                sender = ChatMessage.Sender.SYSTEM,
                senderName = null,
                content = MessageContent.Text(text),
                createdAt = Date(),
            )
        }
    }

    private fun log(message: String) {
        if (configuration.isDebugLoggingEnabled) Log.d("HiveChat", message)
    }

    companion object {
        private const val NAMESPACE = "/livechat/visitor"

        /**
         * Pass your own client to share a connection pool and interceptors.
         * Ping interval is left to the server, which sends Engine.IO pings of
         * its own; OkHttp's would be redundant traffic on a metered radio.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)  // a WebSocket is meant to idle
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
