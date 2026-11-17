package com.najeemfasil.expense.feature.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatDayLabel
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.core.formatMonth
import com.najeemfasil.expense.core.startOfDay
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.categories.categoryLook
import com.najeemfasil.expense.ui.components.AlertButton
import com.najeemfasil.expense.ui.components.AlertStyle
import com.najeemfasil.expense.ui.components.Amount
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Row
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.colors
import kotlinx.coroutines.launch

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** "Paid 4 Nov", "Due 20 Nov", "Overdue since 5 Nov". */
fun monthLabel(m: BillMonth): String = when (m.state) {
    MonthState.PAID -> "Paid" + (m.paidAt?.let { " ${formatDayLabel(it)}" } ?: "")
    MonthState.DUE -> "Due ${formatDayLabel(m.dueAt)}"
    MonthState.OVERDUE -> "Overdue since ${formatDayLabel(m.dueAt)}"
}

@Composable
private fun stateColor(state: MonthState?): Color {
    val c = colors
    return when (state) {
        MonthState.PAID -> c.income
        MonthState.OVERDUE -> c.expense
        else -> c.textMuted
    }
}

/** Monthly bills and EMIs: what is due this month and how much of each loan is left. */
@Composable
fun BillsScreen(now: () -> Long = System::currentTimeMillis) {
    val nav = LocalNavigator.current
    val time = now()
    val snap by rememberDbQuery { loadBills(it, time) }
    val s = snap ?: return Screen {}

    Screen {
        Button("Add a bill or EMI", { nav.push(Routes.billEdit()) })
        if (s.bills.isEmpty()) {
            Empty(
                "No bills yet",
                "Add rent, a landline, school fees or a car EMI. A payment to the same merchant marks the month paid, " +
                    "or you mark it yourself. Insights › Repeat payments can add them for you too."
            )
            return@Screen
        }

        ThisMonthCard(s, time)

        val (emis, bills) = s.bills.partition { it.kind == BillKind.EMI }
        if (bills.isNotEmpty()) {
            Card {
                Label("Monthly bills")
                for (b in bills) BillRow(b, s, time)
            }
        }
        if (emis.isNotEmpty()) {
            Card {
                Label("EMIs and loans")
                for (b in emis) BillRow(b, s, time)
            }
        }
    }
}

@Composable
private fun ThisMonthCard(s: BillsSnapshot, now: Long) {
    val current = s.bills.mapNotNull { b -> s.current(b, now)?.let { b to it } }
    if (current.isEmpty()) return
    Card {
        Label(formatMonth(now))
        for (cur in Currency.entries) {
            val mine = current.filter { it.first.currency == cur }
            if (mine.isEmpty()) continue
            val total = mine.sumOf { it.first.amountMinor }
            val paid = mine.filter { it.second.state == MonthState.PAID }.sumOf { it.second.paidMinor ?: it.first.amountMinor }
            val left = mine.filter { it.second.state != MonthState.PAID }.sumOf { it.first.amountMinor }
            Body("${formatMoney(left, cur)} still to pay", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Body("${formatMoney(paid, cur)} paid of about ${formatMoney(total, cur)} due this month.", muted = true, fontSize = 13.sp)
        }
        val overdue = current.count { it.second.state == MonthState.OVERDUE }
        if (overdue > 0) Body("${plural(overdue, "bill")} overdue.", color = colors.expense, fontSize = 14.sp)
    }
}

@Composable
private fun BillRow(b: Bill, s: BillsSnapshot, now: Long) {
    val nav = LocalNavigator.current
    val cur = s.current(b, now)
    val p = s.progress(b)
    val status = when {
        p.done -> "Completed"
        cur != null -> monthLabel(cur)
        else -> "Starts ${formatMonth(b.startMonth)}"
    }
    val extra = if (b.kind == BillKind.EMI && p.totalCount != null && !p.done) {
        "${p.paidCount} of ${p.totalCount} paid · ${formatMoney(p.remainingMinor ?: 0, b.currency)} left"
    } else {
        null
    }
    Row(
        "${categoryLook(b.categoryId).icon} ${b.name}",
        subtitle = listOfNotNull(status, extra).joinToString(" · "),
        onClick = { nav.push(Routes.bill(b.id)) },
        right = { Amount(b.amountMinor, b.currency) }
    )
    val total = p.totalCount
    if (b.kind == BillKind.EMI && total != null && total > 0) ProgressBar(p.paidCount.toFloat() / total)
}

@Composable
fun ProgressBar(fraction: Float) {
    val c = colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(c.surfaceAlt)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(c.primary)
        )
    }
}

/** One bill: progress, this month's payment, and past months. */
@Composable
fun BillScreen(id: String, now: () -> Long = System::currentTimeMillis) {
    val nav = LocalNavigator.current
    val db = LocalDb.current
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()
    val time = now()
    val snap by rememberDbQuery(id) { loadBills(it, time) }
    val s = snap ?: return Screen {}
    val b = s.bills.firstOrNull { it.id == id } ?: return Screen { Empty("This bill was deleted") }
    val months = s.months[b.id].orEmpty()
    val p = s.progress(b)
    val write = { block: (com.najeemfasil.expense.data.db.Conn) -> Unit -> scope.launch { db.write(block) } }

    Screen {
        Card {
            Label(b.kind.label)
            Body("${categoryLook(b.categoryId).icon} ${b.name}", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Body("${formatMoney(b.amountMinor, b.currency)} a month, due on day ${b.dueDay}", muted = true, fontSize = 14.sp)
            if (b.merchantKey != null) {
                Body("Payments to “${b.merchantKey}” mark the month paid.", muted = true, fontSize = 13.sp)
            }
            val total = p.totalCount
            if (b.kind == BillKind.EMI && total != null) {
                ProgressBar(if (total == 0) 0f else p.paidCount.toFloat() / total)
                Body("${p.paidCount} of ${p.totalCount} instalments paid", fontWeight = FontWeight.SemiBold)
                Body("Paid so far: ${formatMoney(p.paidMinor, b.currency)}", fontSize = 14.sp)
                Body("Left: ${formatMoney(p.remainingMinor ?: 0, b.currency)} (${plural(p.remainingCount ?: 0, "instalment")})", fontSize = 14.sp)
                p.lastMonth?.let { Body(if (p.done) "Completed." else "Last instalment: ${formatMonth(it)}", muted = true, fontSize = 13.sp) }
            } else if (p.paidCount > 0) {
                Body("Paid ${formatMoney(p.paidMinor, b.currency)} over ${plural(p.paidCount, "month")} since ${formatMonth(b.startMonth)}.", muted = true, fontSize = 13.sp)
            }
        }

        val cur = s.current(b, time)
        if (cur != null && cur.state != MonthState.PAID) {
            Card {
                Label("${formatMonth(cur.month)}: ${monthLabel(cur)}")
                val candidates = paymentCandidates(b, cur.month, s.txs, s.takenTxIds).take(5)
                if (candidates.isNotEmpty()) {
                    Body("Was it one of these? Tap to use it as this month’s payment.", muted = true, fontSize = 13.sp)
                    for (t in candidates) {
                        Row(
                            t.merchant ?: "Payment",
                            subtitle = formatDate(t.occurredAt),
                            onClick = { write { markBillPaid(it, b.id, cur.month, t.id) } },
                            right = { Amount(t.amountMinor, t.currency) }
                        )
                    }
                }
                Button(
                    "Paid, not in the app",
                    { write { markBillPaid(it, b.id, cur.month, null, startOfDay(time)) } },
                    variant = ButtonVariant.Secondary
                )
            }
        }

        if (months.isNotEmpty()) {
            Card {
                Label("Months")
                for (m in months) {
                    val manual = m.state == MonthState.PAID && !m.auto
                    val txId = m.transactionId
                    Row(
                        formatMonth(m.month),
                        subtitle = monthLabel(m) + if (manual) " · marked by you" else "",
                        onClick = when {
                            manual -> {
                                {
                                    alerts.show(
                                        "Mark ${formatMonth(m.month)} as not paid?",
                                        null,
                                        listOf(
                                            AlertButton("Cancel", AlertStyle.Cancel),
                                            AlertButton("Not paid") { write { unmarkBillPaid(it, b.id, m.month) } }
                                        )
                                    )
                                }
                            }
                            txId != null -> {
                                { nav.push(Routes.transaction(txId)) }
                            }
                            else -> null
                        },
                        right = {
                            Body(
                                m.paidMinor?.let { formatMoney(it, b.currency) } ?: "—",
                                color = stateColor(m.state),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Edit", { nav.push(Routes.billEdit(id = b.id)) }, variant = ButtonVariant.Secondary)
            Button(
                "Delete",
                {
                    alerts.confirm("Delete “${b.name}”?", "Its transactions stay; only the tracking goes.", action = "Delete", destructive = true) {
                        scope.launch {
                            db.write { deleteBill(it, b.id) }
                            nav.back()
                        }
                    }
                },
                variant = ButtonVariant.Danger
            )
        }
    }
}

/** Bills still unpaid this month, for the Overview. Shows nothing when all is paid. */
@Composable
fun BillsDueCard(now: () -> Long = System::currentTimeMillis) {
    val nav = LocalNavigator.current
    val time = now()
    val snap by rememberDbQuery { loadBills(it, time) }
    val s = snap ?: return
    val due = s.bills.mapNotNull { b -> s.current(b, time)?.takeIf { it.state != MonthState.PAID }?.let { b to it } }
        .sortedBy { it.second.dueAt }
    if (due.isEmpty()) return
    Card {
        Label("Bills to pay")
        for ((b, m) in due.take(4)) {
            Row(
                b.name,
                subtitle = monthLabel(m),
                onClick = { nav.push(Routes.bill(b.id)) },
                right = { Amount(b.amountMinor, b.currency) }
            )
        }
        Button("All bills and EMIs", { nav.push(Routes.BILLS) }, variant = ButtonVariant.Ghost)
    }
}
