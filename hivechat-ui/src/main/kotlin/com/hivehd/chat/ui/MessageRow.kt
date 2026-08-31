package com.hivehd.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hivehd.chat.models.ChatMessage
import com.hivehd.chat.models.LinkPreview
import com.hivehd.chat.models.MessageContent
import com.hivehd.chat.models.ProductCard
import com.hivehd.chat.models.Reaction
import java.text.SimpleDateFormat
import java.util.Locale

/** One row in the thread: a bubble, a card, or a system line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
    message: ChatMessage,
    previews: List<LinkPreview>,
    theme: HiveChatTheme,
    onReact: (String) -> Unit,
    onOpenArticle: (String) -> Unit,
    onSubmitForm: (Map<String, String>) -> Unit,
    onOpenUrl: (String) -> Unit,
    onProductClick: ((ProductCard) -> Unit)? = null,
) {
    when (val content = message.content) {
        is MessageContent.Product -> CardRow(Alignment.Start) {
            ProductCardView(content.card, theme, onOpenUrl, onProductClick)
        }

        is MessageContent.Article -> CardRow(Alignment.Start) {
            ArticleCardView(content.card, theme) { onOpenArticle(content.card.id) }
        }

        is MessageContent.Form -> CardRow(Alignment.Start) {
            FormCardView(content.form, theme, onSubmitForm)
        }

        is MessageContent.SubmittedForm -> CardRow(Alignment.End) {
            FormResponseView(content.response, theme)
        }

        /* A sentinel this version does not know. Showing the raw marker would
           put machine noise in front of the customer, so we show nothing —
           the agent still sees whatever they sent. */
        is MessageContent.Unsupported -> Unit

        else -> BubbleRow(message, previews, theme, onReact, onOpenUrl)
    }
}

@Composable
private fun CardRow(alignment: Alignment.Horizontal, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleRow(
    message: ChatMessage,
    previews: List<LinkPreview>,
    theme: HiveChatTheme,
    onReact: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    if (message.sender == ChatMessage.Sender.SYSTEM) {
        Text(
            text = message.content.previewText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        return
    }

    val isOutgoing = message.sender == ChatMessage.Sender.VISITOR
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        if (!isOutgoing && !message.senderName.isNullOrEmpty()) {
            Text(
                text = message.senderName!!,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            )
        }

        val shape = RoundedCornerShape(
            topStart = theme.cornerRadius,
            topEnd = theme.cornerRadius,
            bottomStart = if (isOutgoing) theme.cornerRadius else 4.dp,
            bottomEnd = if (isOutgoing) 4.dp else theme.cornerRadius,
        )

        var pickerOpen by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                /* Long-press to react, matching the web widget's picker and
                   the platform gesture people already expect from a chat. */
                .combinedClickable(
                    onClick = {},
                    onLongClick = { pickerOpen = true },
                )
                .then(
                    if (isOutgoing) {
                        Modifier.background(
                            Brush.linearGradient(
                                listOf(theme.brandColor, theme.brandGradientEnd ?: theme.brandColor)
                            )
                        )
                    } else {
                        Modifier.background(incomingBubbleColor(theme))
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            when (val content = message.content) {
                is MessageContent.File -> AttachmentView(content.attachment, onOpenUrl)
                else -> Text(
                    text = message.content.previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOutgoing) theme.onBrandColor else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (pickerOpen) {
            ReactionPicker(
                onPick = { emoji -> onReact(emoji); pickerOpen = false },
                onDismiss = { pickerOpen = false },
            )
        }

        previews.forEach { LinkPreviewView(it, onOpenUrl) }

        if (message.reactions.isNotEmpty()) {
            ReactionRow(message.reactions, theme, onReact)
        }

        MessageFooter(message, isOutgoing, theme)
    }
}

@Composable
private fun MessageFooter(message: ChatMessage, isOutgoing: Boolean, theme: HiveChatTheme) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = TIME_FORMAT.format(message.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isOutgoing) return@Row

        when (message.delivery) {
            ChatMessage.Delivery.SENDING -> DeliveryIcon(Icons.Default.Schedule, "Sending", MaterialTheme.colorScheme.onSurfaceVariant)
            ChatMessage.Delivery.FAILED -> DeliveryIcon(Icons.Default.ErrorOutline, "Failed to send", MaterialTheme.colorScheme.error)
            ChatMessage.Delivery.SENT -> if (message.isRead) {
                DeliveryIcon(Icons.Default.DoneAll, "Read", theme.brandColor)
            } else {
                DeliveryIcon(Icons.Default.Check, "Sent", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeliveryIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(13.dp))
}

@Composable
private fun ReactionRow(reactions: List<Reaction>, theme: HiveChatTheme, onTap: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        reactions.forEach { reaction ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (reaction.isMine) theme.brandColor.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (reaction.isMine) {
                            Modifier.border(1.dp, theme.brandColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .clickable { onTap(reaction.emoji) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(reaction.emoji, style = MaterialTheme.typography.labelMedium)
                if (reaction.count > 1) {
                    Text("${reaction.count}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun LinkPreviewView(preview: LinkPreview, onOpenUrl: (String) -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpenUrl(preview.url) }
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        preview.imageUrl?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column {
            preview.siteName?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = preview.title ?: preview.url,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The six reactions the web widget offers, so both clients agree. */
private val REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")

@Composable
private fun ReactionPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            REACTION_EMOJIS.forEach { emoji ->
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPick(emoji) }
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
        }
    }
}
