# HiveChat for Android

Native live chat for Android apps, powered by [Hive](https://hivehd.app).
Connects your customers to the same inbox, bot and agents as the web chat
widget on your storefront — no WebView.

```kotlin
val chat = HiveChat(
    HiveChatConfiguration(
        widgetKey = "wk_live_…",
        tokenStore = VisitorTokenStore.sharedPreferences(context),
    )
)
chat.identify(name = "Alex Doe", email = "alex@example.com")

// In your navigation graph:
composable("support") { HiveChatScreen(chat) }
```

- **Native, not a WebView.** Jetpack Compose throughout.
- **Bring your own UI, or don't.** `HiveChatScreen` drops straight in; or take
  the `hivechat` module alone and collect its `StateFlow`s into your own UI.
- **One dependency.** OkHttp, for the WebSocket. The Socket.IO protocol,
  uploads and message parsing are all implemented here.
- **Rich content included.** Product cards, help articles, agent-pushed forms,
  photos, reactions, read receipts, typing indicators, CSAT.

## Requirements

| | |
|---|---|
| minSdk | 24 |
| compileSdk | 37 |
| Kotlin | 2.0+ |
| Java | 17 |
| Compose | required for `hivechat-ui` only |

## Installation

### JitPack

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.maciuchx.hive-chat-sdk-kotlin:hivechat:0.1.0")
    implementation("com.github.maciuchx.hive-chat-sdk-kotlin:hivechat-ui:0.1.0") // optional
}
```

## Getting your widget key

Hive dashboard → **Settings → Live Chat → your widget**. It looks like
`wk_live_…`.

It is safe to ship in your APK. The same key is already public in the HTML of
every storefront running the web widget, and it grants exactly one capability:
starting a conversation.

## Usage

### Hold one instance

```kotlin
class SupportViewModel(application: Application) : AndroidViewModel(application) {
    /* One chat for the app's lifetime. One per screen would drop the socket,
       lose the unread count, and look to agents like a stream of new
       visitors. */
    val chat = HiveChat(
        HiveChatConfiguration(
            widgetKey = BuildConfig.HIVE_WIDGET_KEY,
            tokenStore = VisitorTokenStore.sharedPreferences(application),
        )
    )

    override fun onCleared() = chat.close()
}
```

### Reconnect on foreground

Android suspends sockets in the background, and a dead one can look alive
until you try to write:

```kotlin
class MainActivity : ComponentActivity(), DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        viewModel.chat.onAppForegrounded()
    }
}
```

### Identify the customer

Call before `start()` when you know who they are.

```kotlin
chat.identify(name = customer.name, email = customer.email)
```

> **Identity is not verified.** Hive currently takes whatever your app sends at
> face value. Treat it as a display convenience, not proof of who the customer
> is. Signed identity is on the roadmap — see [PROTOCOL.md](PROTOCOL.md#identity).

### Badge your entry point

```kotlin
val unread by chat.unreadCount.collectAsStateWithLifecycle()

BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
    Icon(Icons.Default.SupportAgent, contentDescription = "Support")
}
```

`unreadCount` clears on `chat.markRead()`, which `HiveChatScreen` calls when
it appears.

### Headless

Everything `HiveChatScreen` uses is public:

```kotlin
chat.messages            // StateFlow<List<ChatMessage>>, oldest first
chat.connectionState     // Connecting / Connected / Reconnecting / Failed
chat.widgetSettings      // branding, welcome text, whether the team is online
chat.isAgentTyping       // + typingAgentName
chat.isTeamOnline
chat.queuePosition
chat.unreadCount
chat.offlinePrompt
chat.satisfactionRequest
chat.hasEnded
chat.linkPreviews
chat.sessionId
```

```kotlin
chat.send("Where is my order?")
chat.send(fileBytes = jpeg, filename = "photo.jpg", contentType = "image/jpeg")
chat.setTyping(true)
chat.markRead()
chat.requestHuman()
chat.toggleReaction("👍", messageId)
chat.submit(form, mapOf("order" to "TC-10432"))
chat.submitCsat(rating = 5)
chat.provideEmail("alex@example.com")
chat.endChat()

val articles = chat.searchArticles("returns")
val article  = chat.article(articles.first().id)
chat.emailTranscript("alex@example.com")
val handoff  = chat.createDeviceHandoff()
```

### Message content

`ChatMessage.content` is a sealed interface, so rich content is a `when`:

```kotlin
when (val content = message.content) {
    is MessageContent.Text -> Text(content.body)
    is MessageContent.Product -> ProductRow(content.card)
    is MessageContent.Article -> ArticleRow(content.card)
    is MessageContent.Form -> FormCard(content.form)
    is MessageContent.SubmittedForm -> SubmittedForm(content.response)
    is MessageContent.File -> AttachmentView(content.attachment)
    is MessageContent.Unsupported -> Unit  // sentinel from a newer server
}
```

Always handle `Unsupported` by rendering nothing. It exists so an app shipped
today survives a content type Hive adds tomorrow — your users may be several
releases behind.

### Theming

Colours come from the merchant's widget settings by default:

```kotlin
HiveChatScreen(
    chat = chat,
    theme = HiveChatTheme(
        brandColor = MaterialTheme.colorScheme.primary,
        cornerRadius = 12.dp,
    ),
)
```

## What this SDK does not do yet

- **No push notifications.** Hive has no FCM infrastructure today, so a
  backgrounded app is a disconnected app. Until that lands, the practical
  workaround is a Hive webhook into your own backend, which already has FCM
  credentials for your app.
- **Identity is unverified** — see above.
- **No voice or video.**
- **No message editing or deletion.**

## Protocol

The wire format is in [PROTOCOL.md](PROTOCOL.md) — the same contract the Swift
SDK implements.

## Licence

MIT. See [LICENSE](LICENSE).
