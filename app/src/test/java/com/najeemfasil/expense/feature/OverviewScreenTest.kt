package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.feature.dashboard.OverviewScreen
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.TransactionsScreen
import com.najeemfasil.expense.feature.transactions.saveTransaction
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.snap
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
@Config(qualifiers = "w411dp-h2600dp-xxhdpi")
class OverviewScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = localTime(2026, 9, 15, 10)

    private fun seed(db: Db) = runBlocking {
        fun tx(day: Int, minor: Long, merchant: String, cat: String?, dir: Direction = Direction.DEBIT, cur: Currency = Currency.AED, month: Int = 9) = TransactionInput(minor, cur, dir, merchant, null, localTime(2026, month, day, 13), null, cat)
        db.write {
            for (
            t in listOf(
                tx(1, 450000, "Landlord", "rent"),
                tx(2, 32050, "Carrefour", "groceries"),
                tx(3, 8900, "Gazebo", "restaurant"),
                tx(5, 15000, "ENOC", "fuel"),
                tx(7, 22000, "Noon", "shopping"),
                tx(9, 4500, "Careem", "transport"),
                tx(11, 9900, "Netflix", "subscriptions"),
                tx(12, 6000, "Mystery", null),
                tx(10, 1500000, "Employer", "salary", Direction.CREDIT),
                tx(13, 5000, "Noon", "refund", Direction.CREDIT),
                tx(4, 250000, "Swiggy", "restaurant", cur = Currency.INR),
                tx(20, 7700, "Lulu", "groceries", month = 8)
            )
            ) {
                saveTransaction(it, null, t)
            }
            insertSms(it, "p1", localTime(2026, 9, 14, 9))
        }
    }

    @Test
    fun emptyMonth() {
        rule.setScreen(testDb()) { OverviewScreen(now = { now }) }
        rule.onNodeWithText("October 2026").assertIsDisplayed()
        rule.onNodeWithText("Nothing this month").assertIsDisplayed()
        rule.onNodeWithText("Import a bank SMS or add a transaction to get started.").assertIsDisplayed()
        rule.onNodeWithText("Import SMS").assertIsDisplayed()
        rule.onNodeWithText("Add manually").assertIsDisplayed()
        rule.snap("overview-empty")
        rule.onNodeWithText("All time").performClick()
        rule.onNodeWithText("No transactions yet").assertIsDisplayed()
        rule.onAllNodesWithText("October 2026").assertCountEquals(0)
    }

    @Test
    fun monthSummaryChartsAndRecent() {
        val db = testDb()
        seed(db)
        rule.setScreen(db) { OverviewScreen(now = { now }) }
        rule.snap("overview")
        rule.onNodeWithText("1 transaction waiting for review").assertIsDisplayed()
        rule.onAllNodesWithText("+AED 15,000.00")[0].assertIsDisplayed()
        // 5,483.50 spent minus the 50.00 refund.
        rule.onNodeWithText("−AED 5,433.50").assertIsDisplayed()
        rule.onNodeWithText("+AED 9,566.50").assertIsDisplayed()
        rule.onNodeWithText("Money out is after AED 50.00 of refunds.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Other (4), AED 293.00, 5%").assertIsDisplayed()
        rule.onAllNodesWithText("−₹2,500.00").assertCountEquals(2)
        rule.onNodeWithContentDescription("Spending by category, total AED 5,483.50").assertIsDisplayed()
        // 5,483.50 over the 15 days so far.
        rule.onNodeWithText("AED 365.57").assertIsDisplayed()
        rule.onNodeWithText("Average per day · highest AED 4,500.00 on 1 Oct").assertIsDisplayed()

        rule.onNodeWithContentDescription("Rent, AED 4,500.00, 82%").performClick()
        rule.onNodeWithText("82% of spending").assertIsDisplayed()
        rule.onNodeWithText("🏠 Rent").assertIsDisplayed()
        rule.onNodeWithContentDescription("2 Oct: AED 320.50").performClick()
        rule.onNodeWithText("Spent on 2 Oct").assertIsDisplayed()
        rule.snap("overview-selected")
        rule.onNodeWithContentDescription("2 Oct: AED 320.50").performClick()
        rule.onAllNodesWithText("Spent on 2 Oct").assertCountEquals(0)

        rule.onNodeWithText("RECENT").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("See all").assertIsDisplayed()

        rule.onNodeWithText("‹").performScrollTo().performClick()
        rule.onNodeWithText("September 2026").assertIsDisplayed()
        rule.onAllNodesWithText("−AED 77.00").assertCountEquals(3)
        rule.onNodeWithText("All time").performClick()
        rule.onAllNodesWithText("−AED 5,510.50")[0].assertIsDisplayed()
        rule.snap("overview-all-time")
    }

    @Test
    fun darkTheme() {
        val db = testDb()
        seed(db)
        rule.setScreen(db, dark = true) { OverviewScreen(now = { now }) }
        rule.snap("overview-dark")
    }

    @Test
    fun transactionsTabGroupsByDay() {
        val db = testDb()
        seed(db)
        rule.setScreen(db) { TransactionsScreen(now = { now }) }
        rule.snap("transactions")
        rule.onNodeWithText("Add transaction").assertIsDisplayed()
        rule.onNodeWithText("13 OCT 2026").assertIsDisplayed()
        rule.onNodeWithText("Landlord").assertIsDisplayed()
        rule.onAllNodesWithText("Lulu").assertCountEquals(0)
        rule.onNodeWithText("›").performClick()
        rule.onNodeWithText("November 2026").assertIsDisplayed()
        rule.onNodeWithText("No transactions").assertIsDisplayed()
        rule.onNodeWithText("Accepted transactions for this month appear here.").assertIsDisplayed()
    }
}
