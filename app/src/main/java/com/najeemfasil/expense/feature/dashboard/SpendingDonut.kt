package com.najeemfasil.expense.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.feature.categories.rgb
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

private const val SIZE = 168
private const val STROKE = 22

/**
 * Where the money went, as a donut with a legend. Tap a slice or a legend row to see its
 * amount and share in the middle; tap again to go back to the total.
 */
@Composable
fun SpendingDonut(spending: List<CategorySpending>, currency: Currency, nameOf: (String?) -> String) {
    val c = colors
    var selected by rememberSaveable(currency) { mutableStateOf<String?>(null) }

    val slices = buildSlices(spending, nameOf, c.isDark)
    val total = slices.sumOf { it.minor }
    if (total == 0L) return

    val r = (SIZE - STROKE) / 2.0
    val circumference = 2 * PI * r
    val active = slices.firstOrNull { it.key == selected }
    val toggle = { key: String -> selected = if (selected == key) null else key }
    val arcs = layoutArcs(slices, circumference)
    val currentArcs by rememberUpdatedState(arcs)
    val currentToggle by rememberUpdatedState(toggle)

    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Label("Spending by category")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(SIZE.dp)
                    .semantics { contentDescription = "Spending by category, total ${formatMoney(total, currency)}" }
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            val dx = p.x - size.width / 2f
                            val dy = p.y - size.height / 2f
                            val dist = hypot(dx, dy)
                            val ring = r.dp.toPx()
                            val half = STROKE.dp.toPx() / 2
                            if (dist < ring - half || dist > ring + half) return@detectTapGestures
                            // Clockwise from 12 o'clock, as a distance along the ring.
                            var turn = atan2(dx, -dy) / (2 * PI)
                            if (turn < 0) turn += 1.0
                            val along = turn * circumference
                            currentArcs.lastOrNull { along >= it.offset }?.let { currentToggle(it.slice.key) }
                        }
                    }
            ) {
                val stroke = STROKE.dp.toPx()
                val inset = stroke / 2
                for (arc in arcs) {
                    drawArc(
                        color = rgb(arc.slice.color),
                        startAngle = (-90 + arc.offset / circumference * 360).toFloat(),
                        sweepAngle = (arc.dash / circumference * 360).toFloat(),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        alpha = if (active != null && active.key != arc.slice.key) 0.3f else 1f,
                        style = Stroke(stroke)
                    )
                }
            }
            Column(Modifier.width((SIZE - STROKE * 2 - 8).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (active != null) "${active.icon} ${active.label}" else "Spent",
                    color = c.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    formatMoney(active?.minor ?: total, currency),
                    style = TextStyle(color = c.text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 17.sp, stepSize = 0.5.sp)
                )
                if (active != null) Text("${percentOf(active.minor, total)} of spending", color = c.textMuted, fontSize = 12.sp)
            }
        }

        // Legend doubles as the table view: every slice with its amount and share.
        Column {
            for (s in slices) {
                val amount = formatMoney(s.minor, currency)
                val pct = percentOf(s.minor, total)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (active != null && active.key != s.key) 0.5f else 1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            toggle(s.key)
                        }
                        .clearAndSetSemantics {
                            contentDescription = "${s.label}, $amount, $pct"
                            role = Role.Button
                            onClick {
                                toggle(s.key)
                                true
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(rgb(s.color)))
                    Text(s.icon, fontSize = 14.sp, color = c.text)
                    Text(
                        s.label,
                        Modifier.weight(1f),
                        color = c.text,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(amount, color = c.text, fontSize = 14.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                    Text(
                        pct,
                        Modifier.width(40.dp),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.End,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
        }
    }
}
