package com.najeemfasil.expense.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.accounts.AccountNewScreen
import com.najeemfasil.expense.feature.accounts.AccountsScreen
import com.najeemfasil.expense.feature.categories.CategoriesScreen
import com.najeemfasil.expense.feature.dashboard.OverviewScreen
import com.najeemfasil.expense.feature.review.ReviewScreen
import com.najeemfasil.expense.feature.sms.ImportSmsScreen
import com.najeemfasil.expense.feature.transactions.TransactionScreen
import com.najeemfasil.expense.feature.transactions.TransactionsScreen
import com.najeemfasil.expense.feature.transactions.countPending
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.AppNav
import com.najeemfasil.expense.ui.navigation.Destinations
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.PlaceholderScreen
import com.najeemfasil.expense.ui.navigation.Routes

/** The real screens. Destinations still to be built show a placeholder. */
fun appDestinations() = Destinations(
    overview = { OverviewScreen() },
    review = { ReviewScreen() },
    transactions = { TransactionsScreen() },
    more = {
        // Temporary links until the More tab is built (F19).
        val nav = LocalNavigator.current
        Screen(title = "More") {
            Button("Paste an SMS", { nav.push(Routes.IMPORT_SMS) })
            Button("Cards & accounts", { nav.push(Routes.ACCOUNTS) })
            Button("Categories", { nav.push(Routes.CATEGORIES) })
        }
    },
    transaction = { id -> TransactionScreen(id) },
    importSms = { ImportSmsScreen() },
    smsSetup = { PlaceholderScreen() },
    accounts = { AccountsScreen() },
    accountNew = { digits, currency, sender -> AccountNewScreen(digits, currency, sender) },
    categories = { CategoriesScreen() },
    parsers = { PlaceholderScreen() },
    parserNew = { _, _ -> PlaceholderScreen() }
)

@Composable
fun ZeropenseApp() {
    val destinations = remember { appDestinations() }
    val pending by rememberDbQuery { countPending(it) }
    AppNav(destinations, pendingCount = pending ?: 0)
}
