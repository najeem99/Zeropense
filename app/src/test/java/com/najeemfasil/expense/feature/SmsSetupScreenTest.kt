package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.SettingKey
import com.najeemfasil.expense.data.db.getSetting
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.feature.sms.FakeSmsInbox
import com.najeemfasil.expense.feature.sms.InboxMessage
import com.najeemfasil.expense.feature.sms.RuleAction
import com.najeemfasil.expense.feature.sms.SmsSetupScreen
import com.najeemfasil.expense.feature.sms.listSmsRules
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.listPending
import com.najeemfasil.expense.feature.transactions.listSummaryInputs
import com.najeemfasil.expense.feature.transactions.saveTransaction
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

private val now = localTime(2026, 9, 20, 10)
private fun day(d: Int, h: Int = 12, m: Int = 0) = localTime(2026, 9, d, h, m)

private fun purchase(amount: String, digits: String, shop: String) = "Purchase of AED $amount with Credit Card ending $digits at $shop, DUBAI. Avl Cr. Limit is AED 9,000.00"

private val INBOX = listOf(
    InboxMessage("EmiratesNBD", purchase("45.50", "1234", "CARREFOUR"), day(1)),
    InboxMessage("EmiratesNBD", purchase("45.50", "1234", "CARREFOUR"), day(1, 12, 2)),
    InboxMessage("EmiratesNBD", purchase("12.00", "9876", "NOON"), day(3)),
    InboxMessage("EmiratesNBD", "482913 is your OTP for online purchase of AED 20.00", day(4)),
    InboxMessage("EmiratesNBD", "Salary Deposited\nAmount: AED 9,000.00\nDate: 05/10/2026", day(5)),
    InboxMessage("+971501234567", "Lunch? AED 20 each", day(6)),
    InboxMessage("NOON", "Your order has shipped", day(7)),
    // Older than the chosen month: never read.
    InboxMessage("EmiratesNBD", purchase("99.00", "1234", "LULU"), localTime(2026, 7, 1))
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp-xxhdpi")
class SmsSetupScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deniedPermissionStaysOnThePeriodStep() {
        rule.setScreen(testDb(), inbox = FakeSmsInbox(INBOX, granted = false)) { SmsSetupScreen(now = { now }) }
        rule.onNodeWithText("HOW FAR BACK SHOULD WE READ?").assertIsDisplayed()
        rule.onNodeWithText("1 month").performClick()
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("SMS permission is needed to read your bank messages.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SMS permission is needed to read your bank messages.").assertIsDisplayed()
        rule.onNodeWithText("6 months").assertIsDisplayed()
    }

    @Test
    fun fullSetupImportsIntoTotalsDuplicatesIntoReviewAndCreatesCards() {
        val db = testDb()
        runBlocking {
            db.write {
                saveTransaction(it, null, TransactionInput(700, Currency.AED, Direction.DEBIT, "Cash", null, day(2), null, null))
                it.run(
                    """INSERT INTO transactions (id, status, source, amount_minor, currency, direction, occurred_at, sms_hash, created_at, updated_at)
                       VALUES ('old', 'accepted', 'sms', 100, 'AED', 'debit', 1, 'h-old', 1, 1)"""
                )
            }
        }
        rule.setScreen(db, inbox = FakeSmsInbox(INBOX)) { SmsSetupScreen(now = { now }) }
        rule.snap("setup-period")
        rule.onNodeWithText("1 month").performClick()
        rule.waitForIdle()

        // Senders: personal number hidden, the bank pre-ticked, NOON under "without amounts".
        rule.waitUntil(5000) { rule.onAllNodesWithText("EMIRATESNBD").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("EMIRATESNBD").assertIsDisplayed()
        rule.onNodeWithText("5 messages · 5 with an amount").assertIsDisplayed()
        rule.onAllNodesWithText("+971501234567").assertCountEqualsZero()
        rule.onNodeWithText("Show 1 senders without amounts").performClick()
        rule.onNodeWithText("NOON").assertIsDisplayed()
        rule.snap("setup-senders")
        rule.onNodeWithText("Next: message types (1 selected)").performClick()
        rule.waitForIdle()

        // Kinds: purchases and salary ticked, OTP folded away.
        rule.waitUntil(5000) { rule.onAllNodesWithText("Transaction · 3 messages").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Show 1 other kinds (OTP, offers…)").assertIsDisplayed()
        rule.onNodeWithText("AED 9,000.00  (looks like a balance)").assertIsDisplayed()
        rule.snap("setup-kinds")
        rule.onNodeWithText("Next: cards (4 transactions)").performScrollTo().performClick()

        // Cards: one new credit card, one skipped; salary has no card number.
        rule.onNodeWithText("CARD ENDING ••1234").assertIsDisplayed()
        rule.onNodeWithText("MESSAGES WITHOUT A CARD NUMBER").assertIsDisplayed()
        rule.onNodeWithText("Next: review and import").assertIsNotEnabled()
        rule.onAllNodesWithText("+ New card")[0].performClick()
        rule.onNodeWithText("Choose one.").assertIsDisplayed()
        rule.onNodeWithText("Credit card").performClick()
        rule.onAllNodesWithText("Skip")[1].performClick()
        rule.snap("setup-cards")
        rule.onNodeWithText("Next: review and import").performScrollTo().performClick()

        rule.onNodeWithText("4 transaction messages from 1 bank will be added to your totals. Possible duplicates go to Review.")
            .assertIsDisplayed()
        rule.onNodeWithText("The 1 transaction imported from SMS earlier will be deleted first. Transactions you added by hand stay.")
            .assertIsDisplayed()
        rule.onNodeWithText("Import").performClick()
        rule.waitForIdle()

        rule.waitUntil(5000) { rule.onAllNodesWithText("Go to Overview").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("3 transactions added to your totals. 1 possible duplicate in Review.").assertIsDisplayed()
        rule.onNodeWithText("Go to Overview").assertIsDisplayed()
        rule.snap("setup-done")

        runBlocking {
            db.read { c ->
                val accounts = listAccounts(c)
                assertEquals(listOf(Triple("EMIRATESNBD", AccountKind.CREDIT_CARD, "1234")), accounts.map { Triple(it.name, it.kind, it.smsHint) })
                val totals = listSummaryInputs(c, 0, Long.MAX_VALUE).map { it.amountMinor }.sorted()
                assertEquals(listOf(700L, 1200L, 4550L, 900000L), totals)
                assertEquals(listOf(4550L), listPending(c).map { it.amountMinor })
                assertEquals(setOf(RuleAction.IMPORT, RuleAction.IGNORE), listSmsRules(c).map { it.action }.toSet())
                assertEquals(now.toString(), getSetting(c, SettingKey.LAST_SMS_SCAN_AT))
            }
        }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
    assertEquals(0, fetchSemanticsNodes().size)
}
