package com.najeemfasil.expense.feature.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.ui.theme.Radius
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors

/** A 0xRRGGBB colour from the category palette as a Compose colour. */
fun rgb(value: Int, alpha: Int = 0xFF): Color = Color((alpha shl 24) or value)

/** The accent colour at low opacity, for icon backgrounds and pills. */
fun tint(value: Int): Color = rgb(value, TINT_ALPHA)

/** Round icon in the category's colour, e.g. 🛒 on green for Groceries. */
@Composable
fun CategoryIcon(categoryId: String?, size: Dp = 36.dp) {
    val look = categoryLook(categoryId, colors.isDark)
    val fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() }
    Box(Modifier.size(size).clip(CircleShape).background(tint(look.color)), contentAlignment = Alignment.Center) {
        Text(look.icon, fontSize = fontSize)
    }
}

/** Category name as a small coloured pill. */
@Composable
fun CategoryPill(categoryId: String?, name: String, modifier: Modifier = Modifier) {
    val look = categoryLook(categoryId, colors.isDark)
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(tint(look.color))
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(look.icon, fontSize = 11.sp)
        Text(
            name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
