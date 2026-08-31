package com.hivehd.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hivehd.chat.models.ArticleCard
import com.hivehd.chat.models.Attachment
import com.hivehd.chat.models.ChatForm
import com.hivehd.chat.models.FormResponse
import com.hivehd.chat.models.ProductCard

@Composable
internal fun ProductCardView(
    card: ProductCard,
    theme: HiveChatTheme,
    onOpenUrl: (String) -> Unit,
    onProductClick: ((ProductCard) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(theme.cornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            /* Tappable whenever the host can do something with it: either it
               handles products itself, or we have a URL to fall back on. */
            .clickable(enabled = onProductClick != null || card.buyUrl != null) {
                if (onProductClick != null) onProductClick(card) else card.buyUrl?.let(onOpenUrl)
            },
    ) {
        card.imageUrl?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = card.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall)
            card.price?.let {
                Text("£$it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            card.message?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ArticleCardView(card: ArticleCard, theme: HiveChatTheme, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(theme.cornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpen() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(theme.brandColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Article, contentDescription = null, tint = theme.brandColor, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "HELP ARTICLE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = theme.brandColor,
            )
            Text(card.title, style = MaterialTheme.typography.titleSmall)
            card.excerpt?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
internal fun AttachmentView(attachment: Attachment, onOpenUrl: (String) -> Unit) {
    when (attachment.kind) {
        Attachment.Kind.IMAGE -> {
            if (attachment.url != null) {
                AsyncImage(
                    model = attachment.url,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                /* Still uploading and there is no local URL to show, so hold
                   the space rather than collapsing the row and jolting the
                   thread when the real image lands. */
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        }

        else -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable(enabled = attachment.url != null) {
                attachment.url?.let(onOpenUrl)
            },
        ) {
            Icon(
                imageVector = when (attachment.kind) {
                    Attachment.Kind.VIDEO -> Icons.Default.PlayCircle
                    Attachment.Kind.AUDIO -> Icons.Default.AudioFile
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(attachment.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (attachment.isUploading) CircularProgressIndicator(Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun FormCardView(
    form: ChatForm,
    theme: HiveChatTheme,
    onSubmit: (Map<String, String>) -> Unit,
) {
    val values = remember(form) {
        mutableStateMapOf<String, String>().apply {
            form.fields.forEach { field ->
                put(
                    field.key,
                    when (field.type) {
                        ChatForm.FieldType.CHECKBOX -> "No"
                        ChatForm.FieldType.SELECT -> field.options.firstOrNull().orEmpty()
                        else -> ""
                    },
                )
            }
        }
    }
    var submitted by remember(form) { mutableStateOf(false) }

    val isValid = form.fields.all { field ->
        if (!field.required) return@all true
        /* A required checkbox means "must be ticked" — the dashboard uses them
           for consent — so an unticked one is not merely empty. */
        if (field.type == ChatForm.FieldType.CHECKBOX) values[field.key] == "Yes"
        else !values[field.key].isNullOrBlank()
    }

    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(theme.cornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(form.name, style = MaterialTheme.typography.titleSmall)
        form.description?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (submitted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Thanks — that's been sent.", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        form.fields.forEach { field ->
            FormField(field, values[field.key].orEmpty()) { values[field.key] = it }
        }

        Button(
            onClick = { submitted = true; onSubmit(values.toMap()) },
            enabled = isValid,
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.brandColor,
                contentColor = theme.onBrandColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Submit") }
    }
}

@Composable
private fun FormField(field: ChatForm.Field, value: String, onChange: (String) -> Unit) {
    when (field.type) {
        ChatForm.FieldType.CHECKBOX -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(
                checked = value == "Yes",
                onCheckedChange = { onChange(if (it) "Yes" else "No") },
            )
            Text(field.label, style = MaterialTheme.typography.bodySmall)
        }

        ChatForm.FieldType.SELECT -> {
            var expanded by remember { mutableStateOf(false) }
            Column {
                Text(field.label, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = value.ifEmpty { "Choose…" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { expanded = true }
                        .padding(10.dp),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { onChange(option); expanded = false },
                        )
                    }
                }
            }
        }

        else -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(field.label + if (field.required) " *" else "") },
            placeholder = field.placeholder?.let { { Text(it) } },
            singleLine = field.type != ChatForm.FieldType.TEXTAREA,
            minLines = if (field.type == ChatForm.FieldType.TEXTAREA) 3 else 1,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (field.type) {
                    ChatForm.FieldType.EMAIL -> KeyboardType.Email
                    ChatForm.FieldType.NUMBER -> KeyboardType.Number
                    else -> KeyboardType.Text
                }
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun FormResponseView(response: FormResponse, theme: HiveChatTheme) {
    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(theme.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            response.formName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        response.entries.forEach { entry ->
            Column {
                Text(entry.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.value.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
