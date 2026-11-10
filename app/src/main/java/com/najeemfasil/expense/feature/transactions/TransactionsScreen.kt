package com.najeemfasil.expense.feature.transactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.najeemfasil.expense.core.addMonths
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatMonth
import com.najeemfasil.expense.core.startOfMonth
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.MonthSwitcher
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes

/** Consecutive transactions of the same day, in list order. */
fun groupByDay(txs: List<Transaction>): List<Pair<String, List<Transaction>>> {
    val groups = ArrayList<Pair<String, MutableList<Transaction>>>()
    for (tx in txs) {
        val day = formatDate(tx.occurredAt)
        val last = groups.lastOrNull()
        if (last?.first == day) last.second.add(tx) else groups.add(day to mutableListOf(tx))
    }
    return groups
}

/** Transactions tab (FSD §12.3). */
@Composable
fun TransactionsScreen(now: () -> Long = System::currentTimeMillis) {
    val nav = LocalNavigator.current
    var month by rememberSaveable { mutableStateOf(startOfMonth(now())) }
    val lookups = rememberLookups()
    val txs by rememberDbQuery(month) { listAccepted(it, month, addMonths(month, 1)) }

    Screen(title = "Transactions") {
        Button("Add transaction", { nav.push(Routes.transaction("new")) })
        MonthSwitcher(formatMonth(month), { month = addMonths(month, -1) }, { month = addMonths(month, 1) })
        val list = txs
        if (list != null && lookups != null) {
            if (list.isEmpty()) {
                Empty("No transactions", "Accepted transactions for this month appear here.")
            } else {
                for ((day, items) in groupByDay(list)) {
                    Card {
                        Label(day)
                        for (tx in items) TransactionRow(tx, lookups)
                    }
                }
            }
        }
    }
}
