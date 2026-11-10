package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.feature.categories.listRules
import com.najeemfasil.expense.feature.review.ReviewScreen
import com.najeemfasil.expense.feature.sms.RawSms
import com.najeemfasil.expense.feature.sms.importBatch
import com.najeemfasil.expense.feature.transactions.TransactionStatus
import com.najeemfasil.expense.feature.transactions.countPending
import com.najeemfasil.expense.feature.transactions.listPending
import com.najeemfasil.expense.ui.ZeropenseApp
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.snap
import com.najeemfasil.expense.ui.testDb
import com.najeemfasil.expense.ui.theme.ZeropenseTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val t0 = localTime(2026, 9, 11, 13, 0)

private fun enbd(amount: String, digits: String, minutes: Int) = RawSms(
    "EmiratesNBD",
    "Purchase of AED $amount with Credit Card ending $digits at CARREFOUR MOE, DUBAI. Avl Cr. Limit is AED 12,000.00",
    t0 + minutes * 60_000L
)

private fun seed(db: Db) {
    configureEnbd(db)
    runBlocking { db.write { importBatch(it, listOf(enbd("45.50", "1234", 0), enbd("45.50", "1234", 2), enbd("12.00", "9876", 300))) } }
}

private fun pending(db: Db) = runBlocking { db.read { listPending(it) } }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp-xxhdpi")
class ReviewScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyState() {
        rule.setScreen(testDb()) { ReviewScreen() }
        rule.onNodeWithText("All caught up").assertIsDisplayed()
        rule.onNodeWithText("New transactions from your bank SMS show up here for you to check.").assertIsDisplayed()
        rule.onNodeWithText("Paste an SMS").assertIsDisplayed()
    }

    @Test
    fun cardsShowUnknownCardsDuplicatesAndTheSms() {
        val db = testDb()
        seed(db)
        rule.setScreen(db) { ReviewScreen() }
        rule.onNodeWithText("Approve all (3)").assertIsDisplayed()
        rule.onAllNodesWithText("Card ••1234 isn't in your cards yet.").assertCountEquals(2)
        rule.onAllNodesWithText("Add card").assertCountEquals(3)
        rule.onNodeWithText(
            "Possible duplicate: same amount as the entry from 11 Oct, 13:00 within a few minutes. Reject it if this is the same payment."
        ).assertIsDisplayed()
        rule.onNodeWithText("11 Oct, 13:02 · Card ••1234").assertIsDisplayed()
        rule.onAllNodesWithText("Show SMS from EmiratesNBD")[0].performClick()
        rule.onNodeWithText("Hide SMS").assertIsDisplayed()
        rule.snap("review")
    }

    @Test
    fun acceptRejectAndApproveAllWithTheTwoConfirmations() {
        val db = testDb()
        seed(db)
        rule.setScreen(db) { ReviewScreen() }
        val newest = pending(db)[0]
        rule.onAllNodesWithText("Accept")[0].performClick()
        rule.waitForIdle()
        assertEquals(2, pending(db).size)
        assertTrue(newest.id !in pending(db).map { it.id })

        rule.onNodeWithText("Approve all (2)").performClick()
        rule.onNodeWithText("Approve 2 transactions?").assertIsDisplayed()
        rule.onNodeWithText("1 of them may be duplicates of another entry. Approving those counts the payment twice.").assertIsDisplayed()
        rule.onNodeWithText("All 2").assertIsDisplayed()
        rule.snap("review-approve-all")
        rule.onNodeWithText("All except 1 duplicate").performClick()
        rule.waitForIdle()
        val left = pending(db).single()
        assertTrue(left.duplicateOfId != null)

        rule.onNodeWithText("Reject").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("All caught up").assertIsDisplayed()
        assertEquals(TransactionStatus.REJECTED, runBlocking { db.read { c -> com.najeemfasil.expense.feature.transactions.getTransaction(c, left.id)!!.status } })
    }

    @Test
    fun approveAllWithoutDuplicates() {
        val db = testDb()
        configureEnbd(db)
        runBlocking { db.write { importBatch(it, listOf(enbd("45.50", "1234", 0))) } }
        rule.setScreen(db) { ReviewScreen() }
        rule.onNodeWithText("Approve all (1)").performClick()
        rule.onNodeWithText("Approve 1 transaction?").assertIsDisplayed()
        rule.onNodeWithText("They will be added to your totals.").assertIsDisplayed()
        rule.onNodeWithText("Approve").performClick()
        rule.waitForIdle()
        assertEquals(0, runBlocking { db.read { countPending(it) } })
    }

    @Test
    fun editThenAcceptLearnsTheCategoryAndTheBadgeFollowsThePendingCount() {
        val db = testDb()
        seed(db)
        rule.setContent {
            ZeropenseTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalDb provides db) { ZeropenseApp() }
            }
        }
        rule.onNode(hasText("3"), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Review").performClick()
        rule.onAllNodesWithText("Edit")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("🛒 Groceries").performClick()
        rule.onNodeWithText("Save and accept").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("2"), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(runBlocking { db.read { c -> listRules(c).any { it.merchantKey == "CARREFOUR MOE" && it.categoryId == "groceries" } } })
        // The learned category now shows on the other cards from the same shop only after the next import,
        // but the accepted one has left the queue.
        rule.onNodeWithText("Approve all (2)").assertIsDisplayed()
        rule.snap("review-after-edit")
    }
}
