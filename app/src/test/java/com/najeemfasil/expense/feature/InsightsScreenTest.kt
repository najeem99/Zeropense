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
import com.najeemfasil.expense.feature.insights.InsightsScreen
import com.najeemfasil.expense.feature.transactions.TransactionInput
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
@Config(qualifiers = "w411dp-h4200dp-xxhdpi")
class InsightsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // Tuesday 17 November 2026
    private val now = localTime(2026, 10, 17, 18)

    private fun seed(db: Db) = runBlocking {
        fun tx(m: Int, d: Int, minor: Long, merchant: String, cat: String?, dir: Direction = Direction.DEBIT, cur: Currency = Currency.AED) = TransactionInput(minor, cur, dir, merchant, null, localTime(2026, m, d, 13), null, cat)
        val list = ArrayList<TransactionInput>()
        for (m in 7..10) {
            list += tx(m, 1, 450000, "Landlord", "rent")
            list += tx(m, 11, 5500, "Netflix", "subscriptions")
        }
        list += listOf(
            tx(9, 4, 12000, "ENOC", "fuel"),
            tx(9, 18, 11000, "ADNOC", "fuel"),
            tx(9, 9, 30000, "Carrefour", "groceries"),
            tx(10, 3, 15000, "ENOC", "fuel"),
            tx(10, 6, 9000, "Gazebo", "restaurant"),
            tx(10, 13, 9500, "Gazebo", "restaurant"),
            tx(10, 16, 14000, "ENOC", "fuel"),
            tx(10, 17, 4200, "Carrefour", "groceries"),
            tx(10, 17, 3000, "Noon", "refund", Direction.CREDIT),
            tx(10, 10, 1500000, "Employer", "salary", Direction.CREDIT),
            tx(10, 12, 250000, "Swiggy", "restaurant", cur = Currency.INR)
        )
        db.write { for (t in list) saveTransaction(it, null, t) }
    }

    @Test
    fun empty() {
        rule.setScreen(testDb()) { InsightsScreen(now = { now }) }
        rule.onNodeWithText("No spending yet").assertIsDisplayed()
    }

    @Test
    fun monthCalendarDayAndCategoryDrillDown() {
        val db = testDb()
        seed(db)
        rule.setScreen(db) { InsightsScreen(now = { now }) }
        rule.snap("insights-month")
        rule.onNodeWithText("November 2026").assertIsDisplayed()
        // 4,500 + 55 + 150 + 90 + 95 + 140 + 42 = 5,072.00
        rule.onAllNodesWithText("AED 5,072.00")[0].assertIsDisplayed()
        rule.onNodeWithText("AED 30.00 came back as refunds in this month.").assertIsDisplayed()
        rule.onNodeWithText("REPEAT PAYMENTS").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Landlord").assertIsDisplayed()

        // Calendar: tap a day to see its expenses.
        rule.onNodeWithContentDescription("17 Nov: AED 42.00").performScrollTo().performClick()
        rule.onNodeWithText("17 Nov 2026").assertIsDisplayed()
        rule.onAllNodesWithText("Carrefour")[0].assertIsDisplayed()
        rule.snap("insights-day-picked")

        // Only fuel, by month.
        rule.onNodeWithText("⛽ Fuel").performScrollTo().performClick()
        rule.onAllNodesWithText("AED 290.00")[0].assertIsDisplayed()
        rule.onNodeWithText("▲ 142% vs same point in October (AED 120.00)").assertIsDisplayed()
        rule.onNodeWithText("TOP PLACES").performScrollTo().assertIsDisplayed()
        rule.snap("insights-fuel-month")

        rule.onNodeWithText("Year").performScrollTo().performClick()
        rule.onAllNodesWithText("2026")[0].assertIsDisplayed()
        rule.onAllNodesWithText("AED 520.00")[0].assertIsDisplayed()
        rule.onNodeWithText("Week").performClick()
        rule.onNodeWithText("16 – 22 Nov 2026").assertIsDisplayed()
        rule.onAllNodesWithText("AED 140.00")[0].assertIsDisplayed()
        rule.onNodeWithText("Day").performClick()
        rule.onNodeWithText("Tue, 17 Nov 2026").assertIsDisplayed()
        rule.onNodeWithText("‹").performClick()
        rule.onNodeWithText("Mon, 16 Nov 2026").assertIsDisplayed()
        rule.onAllNodesWithText("ENOC")[0].assertIsDisplayed()
        rule.snap("insights-fuel-day")

        rule.onNodeWithText("INR").performClick()
        rule.onAllNodesWithText("⛽ Fuel").assertCountEquals(0)
    }

    @Test
    fun darkTheme() {
        val db = testDb()
        seed(db)
        rule.setScreen(db, dark = true) { InsightsScreen(now = { now }) }
        rule.snap("insights-dark")
    }
}
