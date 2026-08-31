package com.hivehd.chat.ui

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hivehd.chat.ConnectionState
import com.hivehd.chat.HiveChat
import com.hivehd.chat.models.ChatMessage
import com.hivehd.chat.models.KnowledgeBaseArticle
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
    /**
     * Whether the chat handles the keyboard and navigation-bar insets itself.
     *
     * Leave it on unless your own layout already pads for them — a host that
     * applies `imePadding()` around this screen and leaves this true will see
     * the composer sit a keyboard's height too high.
     */
    applyWindowInsets: Boolean = true,
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

    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<KnowledgeBaseArticle?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    /* Photos and documents go through the system picker, so the SDK needs no
       storage permission of its own — the host app declares none either. We
       read the bytes the picker granted us and hand them to the uploader. */
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val resolver = context.contentResolver
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("That file could not be read.")
                chat.send(
                    fileBytes = bytes,
                    filename = uri.displayName(context) ?: "upload",
                    contentType = resolver.getType(uri) ?: "application/octet-stream",
                )
            } catch (e: Throwable) {
                errorMessage = e.message ?: "That file could not be sent."
            }
        }
    }

    val openUrl: (String) -> Unit = { url ->
        /* The host app gets first refusal on every link. Only if it declines
           does the customer leave for a browser. */
        if (onOpenUrl?.invoke(url) != true) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        val focusManager = LocalFocusManager.current
        Column(Modifier.fillMaxSize()) {
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    /*
                     * Tapping the transcript puts the keyboard away.
                     *
                     * Every messaging app does this, so its absence reads as
                     * the screen being stuck: there was no way back to the
                     * conversation except the system back gesture, which on
                     * this screen looks like it should leave the chat.
                     *
                     * onTap fires only for taps no child claimed, so tapping a
                     * reaction, a link or a product card still does its own
                     * thing rather than merely closing the keyboard.
                     */
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    },
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
                        onOpenArticle = { id ->
                            if (onOpenArticle != null) {
                                onOpenArticle(id)
                            } else {
                                /* No host handler, so read it in place — the
                                   card is useless otherwise, and the website
                                   widget opens it inline too. Fetched first:
                                   the card carries a title and excerpt but
                                   not the body. */
                                scope.launch {
                                    runCatching { chat.article(id) }
                                        .onSuccess { article = it }
                                        .onFailure { errorMessage = "That article could not be loaded." }
                                }
                            }
                        },
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
                applyWindowInsets = applyWindowInsets,
                onAttach = { pickFile.launch(arrayOf("image/*", "application/pdf")) },
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

    article?.let { ArticleReader(it) { article = null } }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

/** Reads a display name from a content Uri, falling back to its last segment. */
private fun Uri.displayName(context: android.content.Context): String? =
    runCatching {
        context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: lastPathSegment

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
    applyWindowInsets: Boolean,
    onAttach: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            /* One inset, applied once, in one place.
               This used to be imePadding() on the root column AND
               navigationBarsPadding() here, which stacks: with the keyboard
               up you got the keyboard's height plus the nav bar's, leaving
               the composer floating well above the keys. `union` takes the
               larger of the two — the keyboard when it is open (it already
               covers the nav bar), the nav bar when it is not. */
            .then(if (applyWindowInsets) Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onAttach, modifier = Modifier.size(46.dp)) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = "Attach a photo or file",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
