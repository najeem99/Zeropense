package com.najeemfasil.expense.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.LocalSmsInbox
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.accounts.AccountNewScreen
import com.najeemfasil.expense.feature.accounts.AccountsScreen
import com.najeemfasil.expense.feature.backup.BackupScreen
import com.najeemfasil.expense.feature.bills.BillEditScreen
import com.najeemfasil.expense.feature.bills.BillPrefill
import com.najeemfasil.expense.feature.bills.BillScreen
import com.najeemfasil.expense.feature.bills.BillsDueCard
import com.najeemfasil.expense.feature.bills.BillsScreen
import com.najeemfasil.expense.feature.cards.CardPaymentNewScreen
import com.najeemfasil.expense.feature.cards.CardPaymentsScreen
import com.najeemfasil.expense.feature.categories.CategoriesScreen
import com.najeemfasil.expense.feature.dashboard.OverviewScreen
import com.najeemfasil.expense.feature.debts.DebtEntryScreen
import com.najeemfasil.expense.feature.debts.DebtPersonScreen
import com.najeemfasil.expense.feature.debts.DebtsScreen
import com.najeemfasil.expense.feature.insights.InsightsScreen
import com.najeemfasil.expense.feature.more.MoreScreen
import com.najeemfasil.expense.feature.review.ReviewScreen
import com.najeemfasil.expense.feature.sms.AutoInboxScan
import com.najeemfasil.expense.feature.sms.ImportSmsScreen
import com.najeemfasil.expense.feature.sms.LocalSmsScanner
import com.najeemfasil.expense.feature.sms.ParserNewScreen
import com.najeemfasil.expense.feature.sms.ParsersScreen
import com.najeemfasil.expense.feature.sms.ScanCard
import com.najeemfasil.expense.feature.sms.SmsScanner
import com.najeemfasil.expense.feature.sms.SmsSetupScreen
import com.najeemfasil.expense.feature.transactions.TransactionScreen
import com.najeemfasil.expense.feature.transactions.TransactionsScreen
import com.najeemfasil.expense.feature.transactions.countPending
import com.najeemfasil.expense.ui.navigation.AppNav
import com.najeemfasil.expense.ui.navigation.Destinations

/** The real screens. Destinations still to be built show a placeholder. */
fun appDestinations() = Destinations(
    overview = { OverviewScreen(scanCard = { ScanCard() }, billsCard = { BillsDueCard() }) },
    insights = { InsightsScreen() },
    review = { ReviewScreen() },
    transactions = { TransactionsScreen() },
    more = { MoreScreen() },
    transaction = { id -> TransactionScreen(id) },
    importSms = { ImportSmsScreen() },
    smsSetup = {
        val scanner = LocalSmsScanner.current
        SmsSetupScreen(waitForScan = scanner::waitForScan)
    },
    accounts = { AccountsScreen() },
    accountNew = { digits, currency, sender -> AccountNewScreen(digits, currency, sender) },
    categories = { CategoriesScreen() },
    parsers = { ParsersScreen() },
    parserNew = { sample, sender -> ParserNewScreen(sample, sender) },
    debts = { DebtsScreen() },
    debtPerson = { id -> DebtPersonScreen(id) },
    debtEntry = { person, id, kind -> DebtEntryScreen(person, id, kind) },
    backup = { BackupScreen() },
    cardPayments = { CardPaymentsScreen() },
    cardPaymentNew = { CardPaymentNewScreen() },
    bills = { BillsScreen() },
    bill = { id -> BillScreen(id) },
    billEdit = { a ->
        BillEditScreen(
            a["id"].orEmpty(),
            BillPrefill(a["name"].orEmpty(), a["amount"].orEmpty(), a["currency"].orEmpty(), a["day"].orEmpty(), a["category"].orEmpty(), a["merchant"].orEmpty())
        )
    }
)

@Composable
fun ZeropenseApp() {
    val db = LocalDb.current
    val inbox = LocalSmsInbox.current
    val scanner = remember(db, inbox) { SmsScanner(db, inbox) }
    val destinations = remember { appDestinations() }
    val pending by rememberDbQuery { countPending(it) }
    CompositionLocalProvider(LocalSmsScanner provides scanner) {
        AutoInboxScan()
        AppNav(destinations, pendingCount = pending ?: 0)
    }
}
