package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.createAccount
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.feature.bills.BillInput
import com.najeemfasil.expense.feature.bills.BillKind
import com.najeemfasil.expense.feature.bills.BillsScreen
import com.najeemfasil.expense.feature.bills.saveBill
import com.najeemfasil.expense.feature.cards.CardPaymentsScreen
import com.najeemfasil.expense.feature.cards.recordCardPayment
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.TransactionsScreen
import com.najeemfasil.expense.feature.transactions.saveTransaction
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.testDb
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class TrackersScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = localTime(2026, 10, 17, 9)

    private fun waitFor(text: String) = rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun billsShowWhatIsPaidAndWhatIsLeftOnAnEmi() {
        val db = testDb()
        runBlocking {
            db.write {
                saveBill(it, null, BillInput("Landline", BillKind.BILL, 25_000, Currency.AED, 20, null, "Etisalat", null, 0, now))
                saveBill(it, null, BillInput("Car EMI", BillKind.EMI, 150_000, Currency.AED, 5, null, "Car Loan", 48, 10, now))
                saveTransaction(it, null, TransactionInput(150_000, Currency.AED, Direction.DEBIT, "CAR LOAN", null, localTime(2026, 10, 5, 10), null, null))
            }
        }
        rule.setScreen(db) { BillsScreen(now = { now }) }
        waitFor("Landline")
        rule.onNodeWithText("Due 20 Nov", substring = true).assertExists()
        rule.onNodeWithText("11 of 48 paid", substring = true).assertExists()
        rule.onNodeWithText("still to pay", substring = true).assertExists()
    }

    @Test
    fun cardPaymentsShowTheTotalPerCard() {
        val db = testDb()
        runBlocking {
            db.write {
                createAccount(it, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234")
                recordCardPayment(it, listAccounts(it)[0], 120_000, now, null, null)
            }
        }
        rule.setScreen(db) { CardPaymentsScreen() }
        waitFor("Paid so far")
        rule.onNodeWithText("ENBD ••1234").assertExists()
        rule.onNodeWithText("1 payment", substring = true).assertExists()
    }

    @Test
    fun ignoredTransactionsAreTuckedAwayAtTheBottom() {
        val db = testDb()
        runBlocking {
            db.write {
                saveTransaction(it, null, TransactionInput(5_000, Currency.AED, Direction.DEBIT, "Friend's order", null, now, null, null, excluded = true))
                saveTransaction(it, null, TransactionInput(2_000, Currency.AED, Direction.DEBIT, "Coffee", null, now, null, null))
            }
        }
        rule.setScreen(db) { TransactionsScreen(now = { now }) }
        waitFor("Coffee")
        rule.onAllNodesWithText("Friend's order").assertCountEquals(0)
        rule.onNodeWithText("Ignored (1)").performClick()
        waitFor("Friend's order")
    }
}
