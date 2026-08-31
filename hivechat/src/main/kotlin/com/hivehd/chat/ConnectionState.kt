package com.hivehd.chat

/** Where the SDK's connection to Hive currently stands. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Reconnecting : ConnectionState

    /**
     * The server refused us and retrying will not help — a bad or disabled
     * widget key, most likely. [reason] is safe to log, not to show.
     */
    data class Failed(val reason: String) : ConnectionState
}
