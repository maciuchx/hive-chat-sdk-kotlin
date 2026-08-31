package com.hivehd.chat.ui

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivehd.chat.ConnectionState
import com.hivehd.chat.HiveChat
import com.hivehd.chat.models.ChatMessage
import com.hivehd.chat.models.MessageContent
import com.hivehd.chat.models.ProductCard

/**
 * A complete chat screen, ready to drop into a route.
 *
 * ```kotlin
 * composable("support") { HiveChatScreen(chat) }
 * ```
 *
 * Reads the merchant's branding from their widget settings, so it matches
 * their storefront out of the box. Pass [theme] to override.
 */
@Composable
fun HiveChatScreen(
    chat: HiveChat,
    modifier: Modifier = Modifier,
    theme: HiveChatTheme? = null,
    onOpenArticle: ((String) -> Unit)? = null,
    /**
     * Called when the customer taps a product the bot or an agent sent.
     *
     * Without this the card opens its `buyUrl` in a browser, which walks the
     * customer out of your app mid-conversation. Handle it here to push your
     * own product screen instead — the card carries the title, image, price
     * and URL, and you can recover a product id or handle from [ProductCard.buyUrl].
     */
    onProductClick: ((ProductCard) -> Unit)? = null,
    /**
     * Called before any link is opened externally. Return `true` if you
     * handled it (a deep link into your own app, say); return `false` to let
     * the SDK open it in a browser.
     */
    onOpenUrl: ((String) -> Boolean)? = null,
) {
    val settings by chat.widgetSettings.collectAsStateWithLifecycle()
    val messages by chat.messages.collectAsStateWithLifecycle()
    val connectionState by chat.connectionState.collectAsStateWithLifecycle()
    val isAgentTyping by chat.isAgentTyping.collectAsStateWithLifecycle()
    val isTeamOnline by chat.isTeamOnline.collectAsStateWithLifecycle()
    val queuePosition by chat.queuePosition.collectAsStateWithLifecycle()
    val offlinePrompt by chat.offlinePrompt.collectAsStateWithLifecycle()
    val satisfactionRequest by chat.satisfactionRequest.collectAsStateWithLifecycle()
    val linkPreviews by chat.linkPreviews.collectAsStateWithLifecycle()

    val resolvedTheme = theme ?: settings?.let(HiveChatTheme::from) ?: HiveChatTheme()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (settings == null) chat.start() else chat.connect()
        chat.markRead()
    }

    /* Follow the conversation as it grows. Anchoring on the count rather than
       the list keeps this from firing on every read-receipt patch. */
    LaunchedEffect(messages.size, isAgentTyping) {
        val target = messages.lastIndex + if (isAgentTyping) 1 else 0
        if (target >= 0) listState.animateScrollToItem(target)
    }

    val openUrl: (String) -> Unit = { url ->
        /* The host app gets first refusal on every link. Only if it declines
           does the customer leave for a browser. */
        if (onOpenUrl?.invoke(url) != true) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ChatHeader(
                storeName = settings?.storeName.orEmpty(),
                emoji = settings?.logoEmoji ?: "💬",
                status = statusText(connectionState, queuePosition, isTeamOnline),
                isOnline = isTeamOnline,
                theme = resolvedTheme,
            )
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    settings?.welcomeMessage?.let { welcome ->
                        item("welcome") {
                            MessageRow(
                                message = ChatMessage(
                                    id = "welcome",
                                    sender = ChatMessage.Sender.AGENT,
                                    senderName = settings?.botName,
                                    content = MessageContent.Text(welcome),
                                    createdAt = java.util.Date(),
                                ),
                                previews = emptyList(),
                                theme = resolvedTheme,
                                onReact = {},
                                onOpenArticle = {},
                                onSubmitForm = {},
                                onOpenUrl = openUrl,
                                onProductClick = onProductClick,
                            )
                        }
                    }
                }

                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        previews = linkPreviews[message.id].orEmpty(),
                        theme = resolvedTheme,
                        onReact = { chat.toggleReaction(it, message.id) },
                        onOpenArticle = { id -> onOpenArticle?.invoke(id) },
                        onProductClick = onProductClick,
                        onSubmitForm = { values ->
                            (message.content as? MessageContent.Form)?.let {
                                chat.submit(it.form, values)
                            }
                        },
                        onOpenUrl = openUrl,
                    )
                }

                if (isAgentTyping) {
                    item("typing") { TypingIndicator(resolvedTheme) }
                }
            }

            satisfactionRequest?.let { request ->
                SatisfactionPrompt(request.prompt, resolvedTheme) { chat.submitCsat(it) }
                HorizontalDivider()
            }

            offlinePrompt?.let { prompt ->
                OfflineEmailPrompt(prompt) { chat.provideEmail(it) }
                HorizontalDivider()
            }

            Composer(
                draft = draft,
                placeholder = settings?.placeholderText ?: "Type your message…",
                theme = resolvedTheme,
                onDraftChange = {
                    draft = it
                    chat.setTyping(it.isNotEmpty())
                },
                onSend = {
                    chat.send(draft)
                    draft = ""
                    chat.setTyping(false)
                },
            )
        }
    }
}

private fun statusText(
    state: ConnectionState,
    queuePosition: Int?,
    isTeamOnline: Boolean,
): String = when (state) {
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Reconnecting -> "Reconnecting…"
    is ConnectionState.Failed -> "Unavailable"
    else -> when {
        queuePosition == 1 -> "You're next in line"
        queuePosition != null -> "You're #$queuePosition in the queue"
        isTeamOnline -> "Online now"
        else -> "We'll reply by email"
    }
}

@Composable
private fun ChatHeader(
    storeName: String,
    emoji: String,
    status: String,
    isOnline: Boolean,
    theme: HiveChatTheme,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(theme.brandColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Text(emoji) }

        Column {
            Text(
                storeName.ifEmpty { "Support" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF22C55E) else Color.Gray)
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    placeholder: String,
    theme: HiveChatTheme,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(placeholder) },
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )

        IconButton(
            onClick = onSend,
            enabled = draft.isNotBlank(),
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(theme.brandColor, theme.brandGradientEnd ?: theme.brandColor)
                    )
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send message",
                tint = theme.onBrandColor,
            )
        }
    }
}

@Composable
private fun TypingIndicator(theme: HiveChatTheme) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "phase",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(incomingBubbleColor(theme))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            /* Stagger three dots off one animated value rather than three
               timers — cheaper, and they stay in step across recomposition. */
            val scale = 0.7f + 0.3f * kotlin.math.abs(
                kotlin.math.sin((phase + index * 0.25f) * Math.PI).toFloat()
            )
            Box(
                Modifier
                    .size((6 * scale).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun SatisfactionPrompt(prompt: String, theme: HiveChatTheme, onRate: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.brandColor.copy(alpha = 0.06f))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(prompt, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (1..5).forEach { rating ->
                Icon(
                    Icons.Default.Star,
                    contentDescription = "$rating out of 5",
                    tint = theme.brandColor,
                    modifier = Modifier.size(28.dp).clickable { onRate(rating) },
                )
            }
        }
    }
}

@Composable
private fun OfflineEmailPrompt(message: String, onSubmit: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = { Text("you@example.com") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onSubmit(email) }, enabled = email.contains("@")) { Text("Send") }
        }
    }
}
