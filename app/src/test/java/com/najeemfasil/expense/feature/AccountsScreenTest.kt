package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.AccountNewScreen
import com.najeemfasil.expense.feature.accounts.AccountsScreen
import com.najeemfasil.expense.feature.accounts.createAccount
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.snap
import com.najeemfasil.expense.ui.testDb
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-xxhdpi")
class AccountsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun smsTx(db: com.najeemfasil.expense.data.db.Conn, id: String, hint: String, at: Long) {
        db.run(
            """INSERT INTO transactions (id, status, source, amount_minor, currency, direction, occurred_at, account_hint,
                 sms_hash, sms_sender, sms_body, created_at, updated_at)
               VALUES (?, 'pending', 'sms', 100, 'AED', 'debit', ?, ?, ?, 'EmiratesNBD', 'body', 1, 1)""",
            id,
            at,
            hint,
            "hash-$id"
        )
    }

    @Test
    fun emptyState() {
        rule.setScreen(testDb()) { AccountsScreen() }
        rule.onNodeWithText("No cards or accounts yet. Add one, or scan your SMS and the cards will be found for you.")
            .assertIsDisplayed()
        rule.onNodeWithText("Add card or account").assertIsDisplayed()
    }

    @Test
    fun listsDetectedCardsRenamesAndDeletes() = runBlocking {
        val db = testDb()
        db.write {
            smsTx(it, "t1", "1234", localTime(2026, 9, 11, 14, 5))
            smsTx(it, "t2", "1234", localTime(2026, 9, 12, 9, 0))
            createAccount(it, "ENBD Platinum", AccountKind.CREDIT_CARD, Currency.AED, "9876")
            createAccount(it, "Savings", AccountKind.BANK, Currency.INR, null)
        }
        rule.setScreen(db) { AccountsScreen() }
        rule.snap("accounts")

        rule.onNodeWithText("Ending ••1234").assertIsDisplayed()
        rule.onNodeWithText("2 transactions · AED · EmiratesNBD · last 12 Oct 2026").assertIsDisplayed()
        rule.onNodeWithText("CREDIT CARDS").assertIsDisplayed()
        rule.onNodeWithText("BANK ACCOUNTS").assertIsDisplayed()
        rule.onNodeWithText("ENBD Platinum ••9876").assertIsDisplayed()

        rule.onAllNodesWithText("Rename")[0].performClick()
        rule.onNodeWithText("NAME FOR CARD ••9876").assertIsDisplayed()
        rule.snap("accounts-rename")
        rule.onNode(hasSetTextAction()).performTextReplacement(" Infinite ")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Infinite ••9876").assertIsDisplayed()

        rule.onAllNodesWithText("Delete")[1].performClick()
        rule.onNodeWithText("Delete Savings?").assertIsDisplayed()
        rule.onNodeWithText("Its transactions are kept but no longer linked to it.").assertIsDisplayed()
        rule.onAllNodesWithText("Delete").apply { this[fetchSemanticsNodes().size - 1].performClick() }
        rule.waitForIdle()
        assertEquals(listOf("Infinite"), db.read { c -> listAccounts(c).map { it.name } })
    }

    @Test
    fun addsACardFoundInSmsAndLinksEarlierTransactions() = runBlocking {
        val db = testDb()
        db.write { smsTx(it, "t1", "XX1234", 10) }
        rule.setScreen(db) { AccountNewScreen("1234", "AED", "VM-HDFCBK") }
        rule.onNodeWithText("Found in your SMS from HDFC Bank: a card or account ending ••1234.").assertIsDisplayed()

        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Choose one to continue.").assertIsDisplayed()
        rule.snap("account-new")
        assertEquals(0, db.read { listAccounts(it).size })

        rule.onNodeWithText("Debit card").performClick()
        rule.onNodeWithText("SMS mentioning these digits (like “card ending 1234”) are linked to this debit card.")
            .assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("1 earlier transaction was linked to this debit card.").assertIsDisplayed()
        val a = db.read { listAccounts(it).single() }
        assertEquals(listOf("HDFC Bank", "1234", "debit_card"), listOf(a.name, a.smsHint, a.kind.db))
    }

    @Test
    fun manualCardDefaultsItsNameToTheKindAndKeepsFourDigits() = runBlocking {
        val db = testDb()
        rule.setScreen(db) { AccountNewScreen() }
        rule.onNodeWithText("Bank account").performClick()
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("12a345678")
        rule.onNodeWithText("INR").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val a = db.read { listAccounts(it).single() }
        assertEquals(listOf("Bank account", "5678", "INR"), listOf(a.name, a.smsHint, a.currency.name))
    }
}
