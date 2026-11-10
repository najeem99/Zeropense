package com.najeemfasil.expense.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.dayAt
import com.najeemfasil.expense.core.formatDayLabel
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.feature.categories.rgb
import com.najeemfasil.expense.feature.categories.seriesColor
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.bottomHairline
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import kotlin.math.roundToInt

private const val HEIGHT = 120

/** Money out per day as thin bars. Tap a bar for that day; tap it again to go back to the average. */
@Composable
fun DailyChart(data: DailySpending, from: Long, currency: Currency) {
    val c = colors
    val color = rgb(seriesColor(c.isDark))
    var selected by rememberSaveable(from, currency) { mutableStateOf<Int?>(null) }

    val peak = data.peak ?: return
    val max = peak.minor
    val count = data.days.size
    val sel = selected?.takeIf { it < count }
    // A few evenly spaced date labels under the bars.
    val ticks = listOf(0, ((count - 1) / 3.0).roundToInt(), ((count - 1) * 2 / 3.0).roundToInt(), count - 1)
    val dayLabel = { i: Int -> formatDayLabel(dayAt(from, i)) }

    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Label("Spent per day")
        Column {
            Text(
                formatMoney(if (sel == null) data.averageMinor else data.days[sel], currency),
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Text(
                if (sel == null) {
                    "Average per day · highest ${formatMoney(max, currency)} on ${dayLabel(peak.index)}"
                } else {
                    "Spent on ${dayLabel(sel)}"
                },
                color = c.textMuted,
                fontSize = 12.sp
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(HEIGHT.dp)
                .bottomHairline(c.border, 1.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.days.forEachIndexed { i, minor ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { contentDescription = "${dayLabel(i)}: ${formatMoney(minor, currency)}" }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            selected = if (sel == i) null else i
                        }
                        .padding(horizontal = 1.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (minor > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(maxOf(2f, minor.toFloat() / max * HEIGHT).dp)
                                .alpha(if (sel != null && sel != i) 0.35f else 1f)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(color)
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (t in ticks) Text(dayLabel(t), color = c.textMuted, fontSize = 11.sp)
        }
    }
}
