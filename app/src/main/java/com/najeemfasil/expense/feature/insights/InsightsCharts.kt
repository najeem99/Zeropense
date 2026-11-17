package com.najeemfasil.expense.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.dayAt
import com.najeemfasil.expense.core.formatDayLabel
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.feature.categories.rgb
import com.najeemfasil.expense.feature.categories.seriesColor
import com.najeemfasil.expense.ui.components.bottomHairline
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import kotlin.math.pow

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

/**
 * Vertical bars with a label under each. [selected] is drawn solid and the others faded;
 * [onTap] makes each bar a button.
 */
@Composable
fun Bars(
    values: List<Long>,
    labels: List<String>,
    describe: (Int) -> String,
    selected: Int? = null,
    height: Int = 120,
    onTap: ((Int) -> Unit)? = null
) {
    val c = colors
    val color = rgb(seriesColor(c.isDark))
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .bottomHairline(c.border, 1.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { i, minor ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clearAndSetSemantics {
                            contentDescription = describe(i)
                            this.selected = selected == i
                            if (onTap != null) {
                                role = Role.Button
                                onClick {
                                    onTap(i)
                                    true
                                }
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = onTap != null
                        ) { onTap?.invoke(i) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (minor > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(maxOf(2f, minor.toFloat() / max * height).dp)
                                .alpha(if (selected != null && selected != i) 0.4f else 1f)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(color)
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    Modifier.weight(1f),
                    color = if (selected == i) c.text else c.textMuted,
                    fontSize = 10.sp,
                    fontWeight = if (selected == i) FontWeight.SemiBold else null,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

/** Single-hue heat: low spending is a light tint, the busiest day the full colour. */
private fun heat(minor: Long, max: Long): Float = if (minor <= 0 || max <= 0) 0f else (0.15f + 0.85f * (minor.toFloat() / max).pow(0.7f))

/**
 * A month as a calendar, Monday first. Each day shows what was spent, shaded by how much.
 * Tap a day to pick it; tap it again to let go.
 */
@Composable
fun SpendingCalendar(
    monthStart: Long,
    days: LongArray,
    currency: Currency,
    today: Long,
    selectedDay: Long?,
    onSelect: (Long?) -> Unit
) {
    val c = colors
    val hue = rgb(seriesColor(c.isDark))
    val max = days.maxOrNull() ?: 0L
    val lead = weekdayIndex(monthStart)
    val cells = lead + days.size
    val weeks = (cells + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in WEEKDAYS) {
                Text(d.take(2), Modifier.weight(1f), color = c.textMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 7) {
                    val i = w * 7 + col - lead
                    if (i < 0 || i >= days.size) {
                        Spacer(Modifier.weight(1f).height(50.dp))
                        continue
                    }
                    val day = dayAt(monthStart, i)
                    val minor = days[i]
                    val level = heat(minor, max)
                    val isSelected = selectedDay == day
                    val future = day > today
                    val shape = RoundedCornerShape(8.dp)
                    val strong = level > 0.6f
                    Box(
                        Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(shape)
                            .background(if (level > 0f) hue.copy(alpha = level) else c.surfaceAlt)
                            .then(
                                when {
                                    isSelected -> Modifier.border(2.dp, c.text, shape)
                                    day == today -> Modifier.border(1.5.dp, c.primary, shape)
                                    else -> Modifier
                                }
                            )
                            .alpha(if (future) 0.45f else 1f)
                            .clearAndSetSemantics {
                                contentDescription = "${formatDayLabel(day)}: ${formatMoney(minor, currency)}"
                                selected = isSelected
                                role = Role.Button
                                onClick {
                                    onSelect(if (isSelected) null else day)
                                    true
                                }
                            }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                onSelect(if (isSelected) null else day)
                            }
                            .padding(4.dp)
                    ) {
                        val fg = if (strong) Color.White else c.text
                        Text("${i + 1}", Modifier.align(Alignment.TopStart), color = if (strong) fg else c.textMuted, fontSize = 10.sp)
                        if (minor > 0) {
                            Text(
                                formatCompact(minor, currency),
                                Modifier.align(Alignment.BottomCenter),
                                color = fg,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = TNUM,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Less", color = c.textMuted, fontSize = 11.sp)
            for (a in listOf(0.15f, 0.4f, 0.65f, 1f)) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .width(14.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(hue.copy(alpha = a))
                )
            }
            Text("More", color = c.textMuted, fontSize = 11.sp)
        }
    }
}

/** A row with a label, an amount and a thin bar showing its share of the largest row. */
@Composable
fun ShareRow(
    title: String,
    amount: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    leading: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val c = colors
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (leading != null) Text(leading, fontSize = 15.sp, color = c.text)
            Column(Modifier.weight(1f)) {
                Text(title, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = c.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(amount, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = TNUM)
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceAlt)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(rgb(seriesColor(c.isDark)))
            )
        }
    }
}
