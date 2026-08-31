package com.hivehd.chat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hivehd.chat.models.WidgetSettings

/**
 * Colours for the drop-in chat UI.
 *
 * Defaults come from the merchant's widget settings, so the chat in your app
 * matches the chat on their storefront with no configuration. Anything you
 * set explicitly wins — an app with its own design system usually wants its
 * own bubble colour.
 */
@Immutable
data class HiveChatTheme(
    val brandColor: Color = Color(0xFF6C3CE1),
    val brandGradientEnd: Color? = null,
    /**
     * Text on top of [brandColor]. Derived from its luminance by default,
     * because a merchant who picks pale yellow gets white-on-yellow otherwise.
     */
    val onBrandColor: Color = brandColor.readableForeground(),
    val incomingBubble: Color? = null,
    val cornerRadius: Dp = 18.dp,
) {
    companion object {
        fun from(settings: WidgetSettings): HiveChatTheme {
            val brand = parseHexColor(settings.brandColorHex) ?: Color(0xFF6C3CE1)
            return HiveChatTheme(
                brandColor = brand,
                brandGradientEnd = settings.gradientEndHex?.let(::parseHexColor),
                onBrandColor = brand.readableForeground(),
            )
        }
    }
}

val LocalHiveChatTheme: ProvidableCompositionLocal<HiveChatTheme> =
    compositionLocalOf { HiveChatTheme() }

/**
 * Parses `#RRGGBB`, `#RGB` and `#RRGGBBAA` — the shapes the dashboard's colour
 * picker produces. Returns null rather than a wrong colour, so the caller
 * falls back to its default.
 */
fun parseHexColor(hex: String): Color? {
    val value = hex.trim().removePrefix("#")
    val number = value.toLongOrNull(16) ?: return null
    return when (value.length) {
        3 -> Color(
            red = ((number shr 8) and 0xF) / 15f,
            green = ((number shr 4) and 0xF) / 15f,
            blue = (number and 0xF) / 15f,
        )
        6 -> Color(0xFF000000 or number)
        8 -> Color(
            red = ((number shr 24) and 0xFF) / 255f,
            green = ((number shr 16) and 0xFF) / 255f,
            blue = ((number shr 8) and 0xFF) / 255f,
            alpha = (number and 0xFF) / 255f,
        )
        else -> null
    }
}

/** Black or white, whichever stays legible on this colour. */
fun Color.readableForeground(): Color =
    /* The 0.6 cut sits deliberately above the usual 0.5: mid-brightness brand
       colours read better with white text than the strict threshold says. */
    if (luminance() > 0.6f) Color.Black else Color.White

@Composable
internal fun incomingBubbleColor(theme: HiveChatTheme): Color =
    theme.incomingBubble ?: if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
