package com.hivehd.chat.internal

import com.hivehd.chat.ConnectionState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

/**
 * A minimal Socket.IO v4 / Engine.IO v4 client over OkHttp's WebSocket.
 *
 * Deliberately not `socket.io-client-java`: that pulls in an engine.io stack
 * and its own JSON handling for a protocol whose useful subset is one file —
 * OPEN → namespace CONNECT with auth → EVENT frames both ways, plus ping/pong
 * and reconnection.
 *
 * Not implemented, because the Hive visitor namespace never uses them: binary
 * attachments (files go over HTTP), namespace multiplexing, and compression.
 */
internal class SocketIOConnection(
    host: String,
    private val namespace: String,
    private val client: OkHttpClient,
    /**
     * Rebuilt on every reconnect, so a token adopted mid-session (device
     * handoff) or a name captured by a pre-chat form rides the next handshake
     * without the caller reconnecting by hand.
     */
    private val authProvider: () -> JSONObject,
) {
    private val endpoint: String = host
        .trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/api/socket.io/?EIO=4&transport=websocket"

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hive-chat-socket").apply { isDaemon = true }
    }
    private val isRunning = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private var socket: WebSocket? = null
    private var pendingReconnect: ScheduledFuture<*>? = null

    /* Socket.IO acks are a request/response pair keyed by an integer the
       client picks. `chat:handoff:create` is the only visitor event using one
       today, but a shipped SDK cannot add the plumbing later without a
       release, so it goes in now. */
    private val ackCounter = AtomicInteger(0)
    private val pendingAcks = ConcurrentHashMap<Int, (JSONArray) -> Unit>()

    var onEvent: ((String, JSONArray) -> Unit)? = null
    var onStateChange: ((ConnectionState) -> Unit)? = null

    fun connect() {
        if (!isRunning.compareAndSet(false, true)) return
        reconnectAttempt.set(0)
        open()
    }

    fun disconnect() {
        isRunning.set(false)
        pendingReconnect?.cancel(false)
        /* Send the Socket.IO DISCONNECT frame before tearing down the socket.
           Without it the server only learns we are gone when the transport
           times out, and the visitor sits "online" in the agent panel for a
           minute after the app quits. */
        socket?.send("41$namespace")
        socket?.close(1000, null)
        socket = null
        onStateChange?.invoke(ConnectionState.Disconnected)
    }

    /**
     * Drops the current socket and reconnects immediately, skipping backoff.
     * Call when the app returns to the foreground: Android suspends sockets
     * in the background and the client often does not learn one is dead until
     * it writes — which, on a screen that only reads, is never.
     */
    fun reconnectNow() {
        if (!isRunning.get()) return
        pendingReconnect?.cancel(false)
        reconnectAttempt.set(0)
        socket?.cancel()
        socket = null
        open()
    }

    private fun open() {
        onStateChange?.invoke(
            if (reconnectAttempt.get() == 0) ConnectionState.Connecting else ConnectionState.Reconnecting
        )
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = handleClose()

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleClose()
    }

    private fun handleFrame(text: String) {
        when (text.firstOrNull()) {
            '0' -> sendNamespaceConnect()          // Engine.IO OPEN
            '2' -> socket?.send("3")               // Engine.IO PING → PONG
            '4' -> handlePacket(text.drop(1))      // Engine.IO MESSAGE
        }
    }

    private fun sendNamespaceConnect() {
        socket?.send("40$namespace,${authProvider()}")
    }

    private fun handlePacket(packet: String) {
        val type = packet.firstOrNull() ?: return
        val rest = packet.drop(1)

        when (type) {
            '0' -> {  // CONNECT acknowledged
                reconnectAttempt.set(0)
                onStateChange?.invoke(ConnectionState.Connected)
            }

            '2' -> {  // EVENT
                val (ackId, payload) = split(rest)
                val array = runCatching { JSONArray(payload) }.getOrNull() ?: return
                val name = array.optString(0).ifEmpty { return }
                val args = JSONArray().apply {
                    for (i in 1 until array.length()) put(array.get(i))
                }
                onEvent?.invoke(name, args)
                /* The visitor namespace never asks US to ack today. If it
                   starts to, an unanswered ack leaks a callback server-side,
                   so answer emptily rather than ignoring it. */
                ackId?.let { socket?.send("43$namespace,$it[]") }
            }

            '3' -> {  // ACK — a reply to something we sent
                val (ackId, payload) = split(rest)
                val handler = ackId?.let { pendingAcks.remove(it) } ?: return
                handler(runCatching { JSONArray(payload) }.getOrDefault(JSONArray()))
            }

            '4' -> {  // CONNECT_ERROR — auth rejected, bad namespace
                val (_, payload) = split(rest)
                val message = runCatching { JSONObject(payload).optString("message") }
                    .getOrNull()?.ifEmpty { null }
                onStateChange?.invoke(
                    ConnectionState.Failed(message ?: "The chat server refused the connection.")
                )
                /* The server disconnects us after this. Retrying would hammer
                   it with a handshake it has already refused — an invalid
                   widget key does not become valid by asking again. */
                isRunning.set(false)
            }
        }
    }

    /** Strips the optional `/namespace,` prefix and leading ack id. */
    private fun split(body: String): Pair<Int?, String> {
        var rest = body
        if (rest.startsWith("/")) {
            val comma = rest.indexOf(',')
            if (comma >= 0) rest = rest.substring(comma + 1)
        }
        val digits = rest.takeWhile { it.isDigit() }
        return if (digits.isNotEmpty()) {
            digits.toIntOrNull() to rest.drop(digits.length)
        } else {
            null to rest
        }
    }

    fun emit(event: String, payload: JSONObject = JSONObject()) {
        val frame = JSONArray().put(event).put(payload)
        socket?.send("42$namespace,$frame")
    }

    fun emitWithAck(event: String, payload: JSONObject = JSONObject(), ack: (JSONArray) -> Unit) {
        val id = ackCounter.incrementAndGet()
        pendingAcks[id] = ack
        val frame = JSONArray().put(event).put(payload)
        socket?.send("42$namespace,$id$frame")

        /* An ack the server never sends would pin the callback forever. Five
           seconds is generous for a round trip that only mints a code. */
        scheduler.schedule({ pendingAcks.remove(id)?.invoke(JSONArray()) }, 5, TimeUnit.SECONDS)
    }

    private fun handleClose() {
        socket = null
        pendingAcks.keys.toList().forEach { key -> pendingAcks.remove(key)?.invoke(JSONArray()) }

        if (!isRunning.get()) {
            onStateChange?.invoke(ConnectionState.Disconnected)
            return
        }

        /* Exponential backoff with jitter, capped at 30s. The jitter is not
           decoration: a Cloudflare edge drop or a server restart knocks every
           client offline at the same instant, and a fixed delay marches them
           all back in lockstep. */
        val attempt = reconnectAttempt.incrementAndGet()
        val base = min(1L shl (attempt - 1).coerceAtMost(5), 30L)
        val delayMs = (base * 1000) + Random.nextLong(0, 1000)
        onStateChange?.invoke(ConnectionState.Reconnecting)

        pendingReconnect = scheduler.schedule({
            if (isRunning.get()) open()
        }, delayMs, TimeUnit.MILLISECONDS)
    }
}
