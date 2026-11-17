package com.najeemfasil.expense.feature.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.dayAt
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatDayLabel
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.core.formatMonth
import com.najeemfasil.expense.core.minorToInput
import com.najeemfasil.expense.core.startOfDay
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.categories.categoryLook
import com.najeemfasil.expense.feature.categories.countsInTotals
import com.najeemfasil.expense.feature.transactions.Lookups
import com.najeemfasil.expense.feature.transactions.TransactionRow
import com.najeemfasil.expense.feature.transactions.listAccepted
import com.najeemfasil.expense.feature.transactions.rememberLookups
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.MonthSwitcher
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors

private const val ALL = ""

/** What Insights is looking at; every card below follows it. */
private class Focus(
    val txs: List<InsightTx>,
    val currency: Currency,
    val filter: String?,
    val period: Period,
    val start: Long,
    val now: Long
) {
    val end = shiftPeriod(start, period, 1)
    val spends = spending(txs, currency, filter)
}

/**
 * Insights tab: spending by day, week, month or year, for everything or one category, with a
 * trend, a calendar, highlights, category and merchant breakdowns, habits and repeat payments.
 * [now] is injectable for tests.
 */
@Composable
fun InsightsScreen(now: () -> Long = System::currentTimeMillis) {
    val today = startOfDay(now())
    var periodName by rememberSaveable { mutableStateOf(Period.MONTH.name) }
    val period = Period.valueOf(periodName)
    var start by rememberSaveable { mutableStateOf(periodStart(now(), Period.MONTH)) }
    var currencyName by rememberSaveable { mutableStateOf<String?>(null) }
    var filterKey by rememberSaveable { mutableStateOf(ALL) }
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val lookups = rememberLookups()
    val txs by rememberDbQuery { listInsightTxs(it) }

    val goTo = { p: Period, s: Long ->
        periodName = p.name
        start = periodStart(s, p)
        selectedDay = null
    }
    val choosePeriod = { p: Period ->
        val end = shiftPeriod(start, period, 1)
        // Keep the place in time: the picked day, else today when it is in view, else the start.
        val base = selectedDay ?: if (today >= start && today < end) today else start
        goTo(p, base)
    }

    Screen(title = "Insights") {
        val all = txs
        if (all == null || lookups == null) return@Screen
        val currencies = spendingCurrencies(all)
        if (currencies.isEmpty()) {
            Empty("No spending yet", "Insights appear here once you have accepted transactions.")
            return@Screen
        }
        val currency = currencies.firstOrNull { it.name == currencyName } ?: currencies.first()
        val categories = spendingCategories(all, currency)
        val filter = filterKey.takeIf { it != ALL }?.takeIf { key -> categories.any { (it ?: UNCATEGORISED) == key } }
        val nameOf = { id: String? -> categoryName(id, lookups) }
        val f = remember(all, currency, filter, period, start, today) { Focus(all, currency, filter, period, start, now()) }

        if (currencies.size > 1) {
            ChipRow {
                for (cur in currencies) Chip(cur.name, selected = cur == currency) { currencyName = cur.name }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Chip("All categories", selected = filter == null) { filterKey = ALL }
            for (id in categories) {
                val key = id ?: UNCATEGORISED
                Chip("${categoryLook(id).icon} ${nameOf(id)}", selected = filter == key) { filterKey = key }
            }
        }
        ChipRow {
            for (p in Period.entries) Chip(p.label, selected = p == period) { choosePeriod(p) }
        }
        MonthSwitcher(
            periodLabel(start, period),
            { goTo(period, shiftPeriod(start, period, -1)) },
            { goTo(period, shiftPeriod(start, period, 1)) }
        )

        HeroCard(f, nameOf)
        TrendCard(f) { goTo(period, it) }
        when (period) {
            Period.DAY -> DayCard(f, f.start, lookups, title = "Expenses")
            Period.WEEK -> {
                WeekCard(f, selectedDay) { selectedDay = it }
                selectedDay?.takeIf { it >= f.start && it < f.end }?.let { DayCard(f, it, lookups) }
            }
            Period.MONTH -> {
                CalendarCard(f, today, selectedDay) { selectedDay = it }
                selectedDay?.takeIf { it >= f.start && it < f.end }?.let { DayCard(f, it, lookups) }
                DayByDayCard(f) { goTo(Period.DAY, it) }
            }
            Period.YEAR -> YearCard(f) { goTo(Period.MONTH, it) }
        }
        HighlightsCard(f, nameOf)
        if (filter == null) {
            CategoriesCard(f, nameOf) { filterKey = it ?: UNCATEGORISED }
        } else {
            MerchantsCard(f)
        }
        WeekdayCard(f)
        if (filter == null) RecurringCard(f, nameOf)
    }
}

private fun categoryName(id: String?, lookups: Lookups): String = when (id) {
    null -> "Uncategorised"
    else -> lookups.categoryName(id) ?: id.replaceFirstChar { it.uppercase() }
}

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun HeroCard(f: Focus, nameOf: (String?) -> String) {
    val c = colors
    val s = remember(f) { periodStats(f.txs, f.currency, f.filter, f.period, f.start, f.now) }
    val money = { m: Long -> formatMoney(m, f.currency) }
    Card {
        Label(if (f.filter == null) "All spending" else "${categoryLook(f.filter.takeIf { it != UNCATEGORISED }).icon} ${nameOf(f.filter.takeIf { it != UNCATEGORISED })}", uppercase = f.filter == null)
        Text(money(s.totalMinor), color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, style = TNUM)
        val prev = previousLabel(f.start, f.period, f.now)
        val change = percentChange(s.totalMinor, s.previousMinor)
        val against = if (s.running) "same point ${if (f.period == Period.MONTH || f.period == Period.YEAR) "in " else ""}$prev" else prev
        when {
            change != null && change > 0 -> Body("▲ $change% vs $against (${money(s.previousMinor)})", color = c.expense, fontSize = 13.sp)
            change != null && change < 0 -> Body("▼ ${-change}% vs $against (${money(s.previousMinor)})", color = c.income, fontSize = 13.sp)
            change == 0 -> Body("Same as $against", muted = true, fontSize = 13.sp)
            s.totalMinor > 0 -> {
                val on = when {
                    f.period == Period.WEEK || prev == "yesterday" -> ""
                    f.period == Period.DAY -> "on "
                    else -> "in "
                }
                Body("Nothing spent $on$prev", muted = true, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
            if (f.period != Period.DAY) Stat("Per day", money(s.averagePerDayMinor))
            Stat("Payments", s.count.toString())
            Stat("Largest", s.biggest?.let { money(it.amountMinor) } ?: "–")
        }
        if (s.refundMinor > 0) Body("${money(s.refundMinor)} came back as refunds in this ${f.period.noun}.", muted = true, fontSize = 13.sp)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Body(label, muted = true, fontSize = 12.sp)
        Text(value, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = TNUM)
    }
}

@Composable
private fun TrendCard(f: Focus, onPick: (Long) -> Unit) {
    val buckets = remember(f) { trend(f.spends, f.period, f.start) }
    val withData = buckets.filter { it.minor > 0 }
    Card {
        Label("Last ${f.period.trendCount} ${f.period.noun}s")
        if (withData.isNotEmpty()) {
            val avg = withData.sumOf { it.minor } / withData.size
            Body("Average ${formatMoney(avg, f.currency)} per ${f.period.noun} with spending", muted = true, fontSize = 13.sp)
        }
        Bars(
            values = buckets.map { it.minor },
            labels = buckets.map { periodShortLabel(it.start, f.period) },
            describe = { "${periodLabel(buckets[it].start, f.period)}: ${formatMoney(buckets[it].minor, f.currency)}" },
            selected = buckets.lastIndex,
            onTap = { onPick(buckets[it].start) }
        )
    }
}

/** Money-out transactions of one day that match the filters. */
@Composable
private fun DayCard(f: Focus, day: Long, lookups: Lookups, title: String? = null) {
    val txs by rememberDbQuery(listOf(day, f.currency, f.filter)) { db ->
        listAccepted(db, day, dayAt(day, 1)).filter {
            it.direction == Direction.DEBIT &&
                it.currency == f.currency &&
                matchesCategory(f.filter, it.categoryId) &&
                (f.filter != null || countsInTotals(it.categoryId))
        }
    }
    val list = txs ?: return
    Card {
        Label(title ?: formatDate(day))
        if (list.isEmpty()) {
            Body("No spending on this day.", muted = true)
        } else {
            Body("${list.size} payment${if (list.size == 1) "" else "s"} · ${formatMoney(list.sumOf { it.amountMinor }, f.currency)}", muted = true, fontSize = 13.sp)
            for (tx in list) TransactionRow(tx, lookups)
        }
    }
}

@Composable
private fun WeekCard(f: Focus, selectedDay: Long?, onSelect: (Long?) -> Unit) {
    val days = remember(f) { dayTotals(f.spends, f.start, 7) }
    val sel = selectedDay?.let { daysBetween(f.start, it) }?.takeIf { it in 0..6 }
    Card {
        Label("Day by day")
        Body("Tap a day to see what you spent.", muted = true, fontSize = 13.sp)
        Bars(
            values = days.toList(),
            labels = WEEKDAYS,
            describe = { "${formatDayLabel(dayAt(f.start, it))}: ${formatMoney(days[it], f.currency)}" },
            selected = sel,
            onTap = { i -> onSelect(if (sel == i) null else dayAt(f.start, i)) }
        )
    }
}

@Composable
private fun CalendarCard(f: Focus, today: Long, selectedDay: Long?, onSelect: (Long?) -> Unit) {
    val days = remember(f) { dayTotals(f.spends, f.start, daysBetween(f.start, f.end)) }
    Card {
        Label("Calendar")
        Body("Tap a day to see its expenses.", muted = true, fontSize = 13.sp)
        SpendingCalendar(f.start, days, f.currency, today, selectedDay, onSelect)
    }
}

@Composable
private fun DayByDayCard(f: Focus, onPick: (Long) -> Unit) {
    val days = remember(f) { dayTotals(f.spends, f.start, daysBetween(f.start, f.end)) }
    val counts = remember(f) {
        f.spends.filter { it.occurredAt >= f.start && it.occurredAt < f.end }.groupingBy { daysBetween(f.start, startOfDay(it.occurredAt)) }.eachCount()
    }
    val max = days.maxOrNull() ?: 0L
    if (max == 0L) return
    Card {
        Label("Daily expenses")
        for (i in days.indices.reversed()) {
            if (days[i] == 0L) continue
            val day = dayAt(f.start, i)
            val n = counts[i] ?: 0
            ShareRow(
                title = periodLabel(day, Period.DAY),
                subtitle = "$n payment${if (n == 1) "" else "s"}",
                amount = formatMoney(days[i], f.currency),
                fraction = days[i].toFloat() / max,
                onClick = { onPick(day) }
            )
        }
    }
}

@Composable
private fun YearCard(f: Focus, onPick: (Long) -> Unit) {
    val months = remember(f) { monthsOfYear(f.spends, f.start) }
    val max = months.maxOf { it.minor }
    Card {
        Label("Month by month")
        if (max == 0L) {
            Body("No spending this year.", muted = true)
            return@Card
        }
        for (m in months) {
            if (m.start > f.now && m.minor == 0L) continue
            ShareRow(
                title = formatMonth(m.start),
                amount = formatMoney(m.minor, f.currency),
                fraction = m.minor.toFloat() / max,
                onClick = { onPick(m.start) }
            )
        }
    }
}

@Composable
private fun HighlightsCard(f: Focus, nameOf: (String?) -> String) {
    val items = remember(f) { highlights(f.txs, f.currency, f.filter, f.period, f.start, f.now, nameOf) }
    if (items.isEmpty()) return
    val c = colors
    Card {
        Label("Highlights")
        for (h in items) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.Top
            ) {
                Text(h.icon, fontSize = 20.sp, color = c.text)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(h.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(h.detail, color = c.textMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CategoriesCard(f: Focus, nameOf: (String?) -> String, onPick: (String?) -> Unit) {
    val c = colors
    val rows = remember(f) { categoryBreakdown(f.txs, f.currency, f.period, f.start) }
    if (rows.isEmpty()) return
    val max = rows.first().minor
    Card {
        Label("By category")
        Body("Compared with ${previousLabel(f.start, f.period, f.now)}. Tap one to focus on it.", muted = true, fontSize = 13.sp)
        for (r in rows) {
            val pct = percentChange(r.minor, r.previousMinor)
            ShareRow(
                leading = categoryLook(r.categoryId).icon,
                title = nameOf(r.categoryId),
                subtitle = "${r.count} payment${if (r.count == 1) "" else "s"}",
                amount = formatMoney(r.minor, f.currency),
                fraction = r.minor.toFloat() / max,
                trailing = {
                    Text(
                        when {
                            pct == null -> "new"
                            pct > 0 -> "▲$pct%"
                            pct < 0 -> "▼${-pct}%"
                            else -> "="
                        },
                        Modifier.padding(start = 2.dp),
                        color = when {
                            pct == null -> c.textMuted
                            pct > 0 -> c.expense
                            pct < 0 -> c.income
                            else -> c.textMuted
                        },
                        fontSize = 12.sp,
                        style = TNUM
                    )
                },
                onClick = { onPick(r.categoryId) }
            )
        }
    }
}

@Composable
private fun MerchantsCard(f: Focus) {
    val rows = remember(f) { topMerchants(f.spends, f.start, f.end, 8) }
    if (rows.isEmpty()) return
    val max = rows.maxOf { it.minor }
    Card {
        Label("Top places")
        for (m in rows) {
            ShareRow(
                title = m.name,
                subtitle = "${m.count} payment${if (m.count == 1) "" else "s"} · ${formatMoney(m.minor / m.count, f.currency)} each on average",
                amount = formatMoney(m.minor, f.currency),
                fraction = m.minor.toFloat() / max
            )
        }
    }
}

@Composable
private fun WeekdayCard(f: Focus) {
    val avg = remember(f) { weekdayAverages(f.spends, f.now) }
    if (avg.all { it == 0L }) return
    val top = avg.indices.maxBy { avg[it] }
    Card {
        Label("Your week")
        Body("Average spent on each weekday over the last 13 weeks.", muted = true, fontSize = 13.sp)
        Bars(
            values = avg,
            labels = WEEKDAYS,
            describe = { "${WEEKDAYS_PLURAL[it]}: ${formatMoney(avg[it], f.currency)} on average" },
            selected = top,
            height = 90
        )
    }
}

@Composable
private fun RecurringCard(f: Focus, nameOf: (String?) -> String) {
    val rows = remember(f) { findRecurring(spending(f.txs, f.currency, null), f.now) }
    if (rows.isEmpty()) return
    val c = colors
    val nav = LocalNavigator.current
    Card {
        Label("Repeat payments")
        Body("About ${formatMoney(rows.sumOf { it.typicalMinor }, f.currency)} a month goes to payments that repeat.", muted = true, fontSize = 13.sp)
        Body("Tap one to track it in Bills & EMIs.", muted = true, fontSize = 13.sp)
        for (r in rows) {
            val track = {
                val day = java.util.Calendar.getInstance().apply { timeInMillis = r.lastAt }.get(java.util.Calendar.DAY_OF_MONTH)
                nav.push(
                    Routes.billEdit(
                        name = r.name,
                        amount = minorToInput(r.typicalMinor),
                        currency = f.currency.name,
                        day = day.toString(),
                        category = r.categoryId.orEmpty(),
                        merchant = r.name
                    )
                )
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = track).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(categoryLook(r.categoryId).icon, fontSize = 15.sp, color = c.text)
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = c.text, fontSize = 15.sp, maxLines = 1)
                    val next = if (r.nextAt >= startOfDay(f.now)) "next around ${formatDayLabel(r.nextAt)}" else "was due around ${formatDayLabel(r.nextAt)}"
                    Text("${nameOf(r.categoryId)} · ${r.months} months · $next", color = c.textMuted, fontSize = 12.sp, maxLines = 1)
                }
                Text(formatMoney(r.typicalMinor, f.currency), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = TNUM)
            }
        }
    }
}
