package com.hivehd.chat

import android.content.Context

/** How the SDK should talk to Hive. */
data class HiveChatConfiguration(
    /**
     * The merchant's public widget key, from Settings → Live Chat in the Hive
     * dashboard. Safe to ship in your app: it is already public in every
     * storefront's HTML, and grants nothing beyond starting a chat.
     */
    val widgetKey: String,

    /** Where Hive lives. Only change this if you are on a dedicated host. */
    val host: String = "https://hivehd.app",

    /**
     * Where the visitor token — the id tying this device to its conversation
     * history — is kept between launches.
     */
    val tokenStore: VisitorTokenStore,

    /**
     * Whether to mark incoming messages read as they arrive. Leave this off
     * (the default) and call [HiveChat.markRead] when the chat is genuinely
     * on screen; otherwise read receipts tell the agent the customer has seen
     * messages that arrived while the phone was in their pocket.
     */
    val marksMessagesReadAutomatically: Boolean = false,

    /** Logs protocol traffic. Never enable in release: bodies are customer data. */
    val isDebugLoggingEnabled: Boolean = false,
)

/**
 * Persistence for the visitor token.
 *
 * The token is what lets a customer close the app, come back tomorrow and
 * find the conversation still there — the server keys history off it. Lose it
 * and they silently start a fresh thread while the agent still sees the old
 * one.
 */
interface VisitorTokenStore {
    fun load(): String?
    fun save(token: String)

    companion object {
        /**
         * Backed by `SharedPreferences`, so the token dies with the app on
         * uninstall and clear-data. That is the honest default: a token that
         * survived would hand a conversation to whoever uses the device next.
         */
        fun sharedPreferences(
            context: Context,
            name: String = "com.hivehd.chat",
            key: String = "visitorToken",
        ): VisitorTokenStore {
            val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            return object : VisitorTokenStore {
                override fun load(): String? = prefs.getString(key, null)
                override fun save(token: String) = prefs.edit().putString(key, token).apply()
            }
        }

        /**
         * Keeps the token only for the lifetime of the process — every launch
         * starts a fresh conversation. Useful in tests and kiosk apps where
         * one device is used by many people.
         */
        fun ephemeral(): VisitorTokenStore = object : VisitorTokenStore {
            private var stored: String? = null
            override fun load(): String? = stored
            override fun save(token: String) { stored = token }
        }
    }
}
