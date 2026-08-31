package com.hivehd.chat

/** Everything the SDK can fail with. */
sealed class HiveChatException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The transport failed — no connectivity, TLS, DNS. */
    class Network(cause: Throwable) :
        HiveChatException("We couldn't reach the chat service. Check your connection and try again.", cause)

    /** The server answered, unhappily. */
    class Server(val status: Int, val serverMessage: String?) :
        HiveChatException(serverMessage ?: "Something went wrong at our end. Please try again.")

    /** The server answered with something we could not read. */
    data object InvalidResponse :
        HiveChatException("Something went wrong at our end. Please try again.") {
        private fun readResolve(): Any = InvalidResponse
    }

    /** The file exceeds the merchant's upload limit. */
    class FileTooLarge(val limitBytes: Int) :
        HiveChatException("That file is too large — the limit is ${limitBytes / 1024 / 1024}MB.")

    /**
     * The action needs a live conversation and there isn't one yet — Hive
     * opens a session on the customer's first message, so they have to send
     * one before this can work.
     */
    data object NoActiveSession :
        HiveChatException("Send a message first to start the conversation.") {
        private fun readResolve(): Any = NoActiveSession
    }
}
