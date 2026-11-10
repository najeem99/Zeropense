package com.najeemfasil.expense.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.DAY_MS
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.addMonths
import com.najeemfasil.expense.core.dayAt
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.core.formatMonth
import com.najeemfasil.expense.core.startOfDay
import com.najeemfasil.expense.core.startOfMonth
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.transactions.Lookups
import com.najeemfasil.expense.feature.transactions.Transaction
import com.najeemfasil.expense.feature.transactions.TransactionRow
import com.najeemfasil.expense.feature.transactions.countPending
import com.najeemfasil.expense.feature.transactions.listAccepted
import com.najeemfasil.expense.feature.transactions.listSummaryInputs
import com.najeemfasil.expense.feature.transactions.rememberLookups
import com.najeemfasil.expense.ui.components.Amount
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.MonthSwitcher
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.navigation.Tab
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import kotlin.math.roundToInt

private class OverviewData(
    val recent: List<Transaction>,
    val pending: Int,
    val summaries: List<Pair<CurrencySummary, DailySpending>>,
    val dayFrom: Long
)

/**
 * Overview tab (FSD §12.1). [scanCard] is the SMS scan status card, shown under the month
 * switcher; [now] is injectable for tests.
 */
@Composable
fun OverviewScreen(now: () -> Long = System::currentTimeMillis, scanCard: @Composable () -> Unit = {}) {
    val nav = LocalNavigator.current
    var month by rememberSaveable { mutableStateOf(startOfMonth(now())) }
    var allTime by rememberSaveable { mutableStateOf(false) }
    val lookups = rememberLookups()
    val data by rememberDbQuery(if (allTime) "all" else month) { db ->
        val from = if (allTime) 0L else month
        val to = if (allTime) Long.MAX_VALUE else addMonths(month, 1)
        val inputs = listSummaryInputs(db, from, to)
        // Month: every day of it. All time: the last 30 days.
        val today = now()
        val dayFrom = if (allTime) dayAt(startOfDay(today), -29) else month
        val dayCount = if (allTime) 30 else ((addMonths(month, 1) - month).toDouble() / DAY_MS).roundToInt()
        OverviewData(
            recent = listAccepted(db, from, to, 5),
            pending = countPending(db),
            summaries = summarize(inputs).map { it to dailySpending(inputs, it.currency, dayFrom, dayCount, today) },
            dayFrom = dayFrom
        )
    }

    Screen(title = "Overview") {
        ChipRow {
            Chip("Month", selected = !allTime) { allTime = false }
            Chip("All time", selected = allTime) { allTime = true }
        }
        if (!allTime) {
            MonthSwitcher(formatMonth(month), { month = addMonths(month, -1) }, { month = addMonths(month, 1) })
        }

        scanCard()

        val d = data
        if (d != null && d.pending > 0) {
            Card {
                Body("${d.pending} transaction${if (d.pending == 1) "" else "s"} waiting for review", fontWeight = FontWeight.SemiBold)
                Button("Review now", { nav.goToTab(Tab.Review) })
            }
        }

        if (d != null && lookups != null) {
            if (d.summaries.isEmpty()) {
                Empty(
                    if (allTime) "No transactions yet" else "Nothing this month",
                    "Import a bank SMS or add a transaction to get started."
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button("Import SMS", { nav.push(Routes.IMPORT_SMS) }, variant = ButtonVariant.Secondary, fullWidth = false)
                        Button("Add manually", { nav.push(Routes.transaction("new")) }, fullWidth = false)
                    }
                }
            } else {
                for ((summary, daily) in d.summaries) SummaryCard(summary, daily, d.dayFrom, lookups)
                Card {
                    Label("Recent")
                    for (tx in d.recent) TransactionRow(tx, lookups)
                    Button("See all", { nav.goToTab(Tab.Transactions) }, variant = ButtonVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(s: CurrencySummary, daily: DailySpending, dayFrom: Long, lookups: Lookups) {
    val c = colors
    Card {
        Label(s.currency.name)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Money in") { Amount(s.inMinor, s.currency, direction = Direction.CREDIT) }
            Stat("Money out") { Amount(s.outMinor, s.currency, direction = Direction.DEBIT) }
            Stat("Net") {
                Body(
                    formatMoney(s.netMinor, s.currency, signed = true),
                    fontWeight = FontWeight.SemiBold,
                    color = if (s.netMinor < 0) c.expense else c.income
                )
            }
        }
        if (s.refundMinor > 0) {
            Body("Money out is after ${formatMoney(s.refundMinor, s.currency)} of refunds.", muted = true, fontSize = 13.sp)
        }
        SpendingDonut(s.topSpending, s.currency) { lookups.categoryName(it) ?: "Uncategorised" }
        DailyChart(daily, dayFrom, s.currency)
    }
}

@Composable
private fun Stat(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Body(label, muted = true, fontSize = 13.sp)
        value()
    }
}
