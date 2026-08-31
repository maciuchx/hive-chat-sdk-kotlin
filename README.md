# HiveChat for Android

Native live chat for Android apps, powered by [Hive](https://hivehd.app).
Connects your customers to the same inbox, bot and agents as the web chat
widget on your storefront — no WebView.

```kotlin
val chat = HiveChat(
    HiveChatConfiguration(
        widgetKey = "hv_a1b2c3d4e5f6a1b2c3d4e5f6",
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
    implementation("com.github.maciuchx.hive-chat-sdk-kotlin:hivechat:0.7.0")
    implementation("com.github.maciuchx.hive-chat-sdk-kotlin:hivechat-ui:0.7.0") // optional
}
```

## Getting your widget key

Hive dashboard → **Settings → Live Chat → your widget**, or the **Mobile app →
Native SDK** panel on that widget, which shows the key next to ready-made
snippets. Keys are `hv_` followed by 24 hex characters, e.g.
`hv_a1b2c3d4e5f6a1b2c3d4e5f6`.

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

### Push notifications

Two lines, and Hive sends them — no backend of yours involved.

```kotlin
// Wherever you already receive the FCM token, and again in onNewToken
chat.registerDeviceToken(fcmToken)

// On sign-out, so the next person on this phone is not notified
chat.unregisterDeviceToken(fcmToken)
```

Your team pastes the app's Firebase service account into **Settings → Live
Chat → Mobile Push** once, and Hive notifies the customer whenever a reply
lands while the app is closed. Google will not let anyone send to your app
without that key, so pasting it is the floor — but it is the whole of it: no
endpoint of yours to expose, no polling loop, and nothing to configure on your
domain.

Registration is keyed on the device's visitor token rather than an email, so
it works for a customer who has never signed in.

If you would rather Hive never held your signing key, the same events are
available as a webhook to your backend or as a feed you poll — both in the
same settings panel.

### Notifying the customer

Two halves, and only one of them needs a server.

**While your app is running**, the socket is connected and the SDK already has
the message — no server involved:

```kotlin
chat.onMessageReceived = { message ->
    if (!chatScreenIsVisible) {
        notificationManager.notify(id, buildLocalNotification(message.content.previewText))
    }
}
```

**While your app is closed**, nothing local can help. A suspended app runs no
code, so it cannot notice a message, and it cannot raise a notification about
one it never saw. Only a push sent by a server can wake it — which is what
Hive's push webhook exists for: it tells your backend a reply went undelivered,
and your backend sends the push with the FCM credentials it already has.

Having Firebase in your app is what lets it *receive* a push. Something still
has to *send* one.

### Giving agents the context they have on the web

On your website an agent sees which page a customer is on and what is in their
basket. An app has no URL, so tell Hive yourself — then the agent panel shows
the same thing for app customers.

```kotlin
// On navigation
chat.trackScreen("Product", title = "Slim Fit Suit", reference = "slim-fit-suit")
chat.trackScreen("Basket")
chat.trackScreen("Order", title = "Order TC-10432", reference = "TC-10432")

// Whenever the basket changes
chat.updateCart(
    items = basket.lines.map {
        CartItem(title = it.name, quantity = it.qty, price = it.price, variant = it.size)
    },
    total = basket.total,
    currency = "GBP",
)
```

Both are safe to call before a conversation exists — they are recorded against
the visitor, so an agent picking the chat up sees what the customer was doing
beforehand. Without these calls, the agent's browsing history shows only
"Android app", which is true but not useful.

### Voice messages

Off by default, because recording needs `RECORD_AUDIO` and an SDK should not
make every host app ask for the microphone. To enable it, declare the
permission in your manifest and turn it on:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

```kotlin
HiveChatScreen(chat = chat, voiceMessagesEnabled = true)
```

The mic appears when the message field is empty. Recordings go out as AAC in
MP4, which agents can play in the dashboard and which survives a hand-off to
WhatsApp. The SDK asks for the permission when the customer taps the mic, not
at launch.

### Keeping the customer inside your app

The bot and your agents can send **product cards** — the same ones the website
chat sends. By default tapping one opens its `buyUrl` in a browser, which walks
the customer out of the conversation. Handle it yourself instead:

```kotlin
HiveChatScreen(
    chat = chat,
    onProductClick = { product ->
        // The card carries title, imageUrl, price and buyUrl. Recover your own
        // product id or handle from buyUrl and navigate natively.
        val handle = product.buyUrl?.toUri()?.lastPathSegment ?: return@HiveChatScreen
        navController.navigate("product/$handle")
    },
    onOpenUrl = { url ->
        val route = deepLinks.routeFor(url) ?: return@HiveChatScreen false // false → open a browser
        navController.navigate(route)
        true
    },
)
```

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
