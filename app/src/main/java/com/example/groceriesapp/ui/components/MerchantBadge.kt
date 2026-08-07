package com.example.groceriesapp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circular merchant badge tinted with the retailer's recognizable brand colour.
 *
 * Chain logos are trademarked artwork, so the badge pairs the brand colour with the
 * merchant initials instead of bundling logo images.
 */
@Composable
fun MerchantBadge(
    merchant: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) = InitialsBadge(label = merchant, modifier = modifier, size = size)

/** Generic circular initials badge, used for merchants and for grocery items alike. */
@Composable
fun InitialsBadge(
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val background = merchantColor(label)
    val content = if (background.luminance() > 0.45f) Color.Black else Color.White
    Surface(
        shape = CircleShape,
        color = background,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = merchantInitials(label),
                color = content,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.32f).sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Up to two letters: one per word for multi-word names, otherwise the first two. */
fun merchantInitials(merchant: String?): String {
    val words = merchant.orEmpty()
        .split(' ', '-', '.', '&')
        .filter { word -> word.any(Char::isLetterOrDigit) }
    if (words.isEmpty()) return "?"
    val letters = if (words.size >= 2) {
        words.take(2).map { word -> word.first() }.joinToString("")
    } else {
        words.first().take(2)
    }
    return letters.uppercase()
}

fun merchantColor(merchant: String?): Color {
    val name = merchant.orEmpty().uppercase()
    BRAND_COLORS.firstOrNull { (key, _) -> name.contains(key) }?.let { (_, color) -> return color }
    if (name.isBlank()) return FALLBACK_COLORS.first()
    val index = (name.hashCode().toLong() and 0xFFFFFFFFL) % FALLBACK_COLORS.size
    return FALLBACK_COLORS[index.toInt()]
}

private val BRAND_COLORS: List<Pair<String, Color>> = listOf(
    "BIO COMPANY" to Color(0xFF6FA82F),
    "BIOCOMPANY" to Color(0xFF6FA82F),
    "HELLWEG" to Color(0xFFE30613),
    "LIDL" to Color(0xFF0050AA),
    "REWE" to Color(0xFFCC071E),
    "EDEKA" to Color(0xFF1B4F9C),
    "ALDI" to Color(0xFF00549F),
    "NETTO" to Color(0xFFFFDD00),
    "PENNY" to Color(0xFFD81E05),
    "KAUFLAND" to Color(0xFFE10915),
    "ROSSMANN" to Color(0xFFC8102E),
    "DM" to Color(0xFF002878),
)

private val FALLBACK_COLORS: List<Color> = listOf(
    Color(0xFF4C7A34),
    Color(0xFF2F6F8F),
    Color(0xFF8A5A2B),
    Color(0xFF6A4C93),
    Color(0xFF9C5560),
    Color(0xFF3F7A6E),
)
