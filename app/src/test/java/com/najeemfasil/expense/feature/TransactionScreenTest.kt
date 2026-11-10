package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.core.toDateInput
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.createAccount
import com.najeemfasil.expense.feature.categories.listRules
import com.najeemfasil.expense.feature.transactions.Lookups
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.TransactionRow
import com.najeemfasil.expense.feature.transactions.TransactionScreen
import com.najeemfasil.expense.feature.transactions.TransactionSource
import com.najeemfasil.expense.feature.transactions.TransactionStatus
import com.najeemfasil.expense.feature.transactions.getTransaction
import com.najeemfasil.expense.feature.transactions.listAccepted
import com.najeemfasil.expense.feature.transactions.saveTransaction
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.snap
import com.najeemfasil.expense.ui.testDb
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

fun insertSms(db: Conn, id: String, at: Long, hint: String? = "1234", merchant: String? = "CARREFOUR") {
    db.run(
        """INSERT INTO transactions (id, status, source, amount_minor, currency, direction, merchant, occurred_at,
             account_hint, sms_hash, sms_sender, sms_body, created_at, updated_at)
           VALUES (?, 'pending', 'sms', 4550, 'AED', 'debit', ?, ?, ?, ?, 'EmiratesNBD',
             'Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR, DUBAI.', 1, 1)""",
        id,
        merchant,
        at,
        hint,
        "hash-$id"
    )
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp-xxhdpi")
class TransactionScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fields get() = rule.onAllNodes(hasSetTextAction())
    private val all = 0L to Long.MAX_VALUE

    @Test
    fun validatesThenAddsAManualExpenseAtNoonAndRemembersTheMerchant() = runBlocking {
        val db = testDb()
        db.write { createAccount(it, "HDFC", AccountKind.DEBIT_CARD, Currency.INR, "4321") }
        rule.setScreen(db) { TransactionScreen("new") }
        rule.onNodeWithText("Expense").assertIsSelected()
        rule.onNodeWithText("None").assertIsSelected()
        rule.onNodeWithText("AED").assertIsSelected()

        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Enter an amount").assertIsDisplayed()
        rule.onNodeWithText("Use a number like 45.50.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()

        fields[0].performTextInput("1,250.5")
        fields[2].performTextReplacement("soon")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Check the date").assertIsDisplayed()
        rule.onNodeWithText("Use the format YYYY-MM-DD.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()

        fields[2].performTextReplacement("2026-10-11")
        fields[1].performTextInput("  Gazebo ")
        rule.onNodeWithText("🍽️ Restaurant").performClick()
        rule.onNodeWithText("Future transactions from “Gazebo” will get this category.").assertIsDisplayed()
        rule.onNodeWithText("HDFC ••4321 (INR)").performClick()
        rule.onAllNodesWithText("AED").assertCountEquals(0)
        rule.snap("transaction-new")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        val tx = db.read { listAccepted(it, all.first, all.second).single() }
        assertEquals(
            listOf(125050L, Currency.INR, Direction.DEBIT, "Gazebo", null, "restaurant", TransactionSource.MANUAL),
            listOf(tx.amountMinor, tx.currency, tx.direction, tx.merchant, tx.note, tx.categoryId, tx.source)
        )
        assertEquals(localTime(2026, 9, 11, 12), tx.occurredAt)
        assertTrue(db.read { c -> listRules(c).any { it.merchantKey == "GAZEBO" && it.categoryId == "restaurant" } })
    }

    @Test
    fun kindChipsSwitchDirectionAndCategory() = runBlocking {
        val db = testDb()
        rule.setScreen(db) { TransactionScreen("new") }
        rule.onNodeWithText("🍽️ Restaurant").performClick()
        rule.onNodeWithText("Income").performClick()
        rule.onNodeWithText("FROM").assertIsDisplayed()
        rule.onNodeWithText("💼 Salary").assertIsDisplayed()
        rule.onAllNodesWithText("🍽️ Restaurant").assertCountEquals(0)
        rule.onNodeWithText("Refund").performClick()
        rule.onNodeWithText("A refund is money back from a shop. It lowers your spending and doesn’t count as income.")
            .assertIsDisplayed()
        rule.onAllNodesWithText("CATEGORY").assertCountEquals(0)
        rule.onNodeWithText("MERCHANT").assertIsDisplayed()
        fields[0].performTextInput("20")
        rule.snap("transaction-refund")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val tx = db.read { listAccepted(it, all.first, all.second).single() }
        assertEquals(listOf(Direction.CREDIT, "refund", 2000L), listOf(tx.direction, tx.categoryId, tx.amountMinor))
    }

    @Test
    fun editingAPendingSmsKeepsItsTimeAndAcceptsIt() = runBlocking {
        val db = testDb()
        val at = localTime(2026, 9, 11, 14, 5)
        db.write { insertSms(it, "t1", at) }
        rule.setScreen(db) { TransactionScreen("t1") }
        rule.onNodeWithText("SMS FROM EMIRATESNBD").assertIsDisplayed()
        rule.onNodeWithText("Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR, DUBAI.").assertIsDisplayed()
        rule.onNodeWithText("Reject").assertIsDisplayed()
        rule.snap("transaction-pending")
        fields[0].performTextReplacement("46")
        rule.onNodeWithText("🛒 Groceries").performClick()
        rule.onNodeWithText("Save and accept").performClick()
        rule.waitForIdle()
        val tx = db.read { getTransaction(it, "t1")!! }
        assertEquals(
            listOf(TransactionStatus.ACCEPTED, 4600L, at, "groceries"),
            listOf(tx.status, tx.amountMinor, tx.occurredAt, tx.categoryId)
        )
        // Now accepted: the buttons change.
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete transaction?").assertIsDisplayed()
        rule.onNodeWithText("The SMS will not be imported again.").assertIsDisplayed()
        rule.onAllNodesWithText("Delete").apply { this[fetchSemanticsNodes().size - 1].performClick() }
        rule.waitForIdle()
        assertEquals(TransactionStatus.REJECTED, db.read { getTransaction(it, "t1")!!.status })
    }

    @Test
    fun rejectsAPendingOneAndDeletesAManualOne() = runBlocking {
        val db = testDb()
        db.write { insertSms(it, "t1", 1000) }
        rule.setScreen(db) { TransactionScreen("t1") }
        rule.onNodeWithText("Reject").performClick()
        rule.waitForIdle()
        assertEquals(TransactionStatus.REJECTED, db.read { getTransaction(it, "t1")!!.status })
    }

    @Test
    fun deletesAManualOne() = runBlocking {
        val db = testDb()
        var id = ""
        db.write {
            id = saveTransaction(it, null, TransactionInput(500, Currency.AED, Direction.DEBIT, null, "lunch", 5000, null, null))
        }
        rule.setScreen(db) { TransactionScreen(id) }
        fields[3].assertIsDisplayed()
        rule.onNodeWithText("lunch").assertIsDisplayed()
        rule.onNodeWithText(toDateInput(5000)).assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.onAllNodesWithText("The SMS will not be imported again.").assertCountEquals(0)
        rule.onAllNodesWithText("Delete").apply { this[fetchSemanticsNodes().size - 1].performClick() }
        rule.waitForIdle()
        assertNull(db.read { getTransaction(it, id) })
    }

    @Test
    fun rows() {
        val db = testDb()
        runBlocking { seedRows(db) }
        val txs = runBlocking { db.read { c -> listOf("t1", "t2").map { getTransaction(c, it)!! } } }
        val lookups = runBlocking { db.read { Lookups(emptyList(), com.najeemfasil.expense.feature.categories.listCategories(it)) } }
        rule.setScreen(db) {
            Screen { Card { for (tx in txs) TransactionRow(tx, lookups) } }
        }
        rule.snap("transaction-rows")
        rule.onNodeWithText("CARREFOUR").assertIsDisplayed()
        rule.onNodeWithText("Card ••1234 · 11 Oct 2026").assertIsDisplayed()
        rule.onNodeWithText("Groceries").assertIsDisplayed()
        rule.onNodeWithText("Money in").assertIsDisplayed()
        rule.onNodeWithText("Uncategorised").assertIsDisplayed()
        rule.onNodeWithText("−AED 45.50").assertIsDisplayed()
        rule.onNodeWithText("+AED 45.50").assertIsDisplayed()
    }

    private suspend fun seedRows(db: com.najeemfasil.expense.data.db.Db) {
        db.write {
            insertSms(it, "t1", localTime(2026, 9, 11, 14, 5))
            insertSms(it, "t2", localTime(2026, 9, 12, 9, 0), hint = null, merchant = null)
            it.run("UPDATE transactions SET category_id = 'groceries' WHERE id = 't1'")
            it.run("UPDATE transactions SET direction = 'credit', sms_body = NULL WHERE id = 't2'")
        }
    }
}
