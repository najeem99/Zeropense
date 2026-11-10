package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.najeemfasil.expense.feature.sms.ParserNewScreen
import com.najeemfasil.expense.feature.sms.ParsersScreen
import com.najeemfasil.expense.feature.sms.RawSms
import com.najeemfasil.expense.feature.sms.importBatch
import com.najeemfasil.expense.feature.sms.listCustomParsers
import com.najeemfasil.expense.feature.sms.listUnread
import com.najeemfasil.expense.feature.transactions.listPending
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

private const val LULU = "Card 4455 used. Amt: 87.25 AED. Shop: LULU."
private const val SPAR = "Card 4455 used. Amt: 12.00 AED. Shop: SPAR."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2400dp-xxhdpi")
class ParsersScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun withUnread() = testDb().also { db ->
        configureEnbd(db)
        runBlocking { db.write { importBatch(it, listOf(RawSms("EmiratesNBD", LULU, 1000), RawSms("EmiratesNBD", SPAR, 2000))) } }
    }

    @Test
    fun listsUnreadMessagesFormatsAndBuiltIns() {
        val db = withUnread()
        rule.setScreen(db) { ParsersScreen() }
        rule.waitUntil { rule.onAllNodesWithText("MESSAGES WE COULDN'T READ (2)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("None yet. Yours are tried before the built-in ones.").assertExists()
        rule.onNodeWithText("Generic").assertExists()
        rule.snap("parsers")
        rule.onAllNodesWithText("Ignore")[0].performClick()
        rule.waitUntil { rule.onAllNodesWithText("MESSAGES WE COULDN'T READ (1)").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aFormatBuiltFromAnUnreadMessageReadsTheSimilarOnes() {
        val db = withUnread()
        rule.setScreen(db) { ParserNewScreen(LULU, "EmiratesNBD") }
        rule.onNodeWithText("AED 87.25").performClick()
        rule.onNodeWithText("✓ This one").assertExists()
        rule.onNodeWithText("Save and use for future SMS").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("LULU").performScrollTo().performClick()
        rule.onNodeWithText("Merchant ✓").assertExists()
        rule.onNodeWithText("Merchant: LULU").performScrollTo().assertExists()
        rule.onNodeWithText("Spent AED 87.25").assertExists()
        rule.snap("parser-new")
        rule.onNodeWithText("Save and use for future SMS").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("2 more messages could be read and went to Review.").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertEquals(listOf("EmiratesNBD"), db.read { listCustomParsers(it) }.map { it.parser.name })
            assertEquals(0, db.read { listUnread(it) }.size)
            assertEquals(listOf("LULU", "SPAR"), db.read { listPending(it) }.mapNotNull { it.merchant }.sorted())
        }
    }

    @Test
    fun saveNeedsAnAmountAndEditingResetsPicks() {
        val db = testDb()
        rule.setScreen(db) { ParserNewScreen("no numbers here", "") }
        rule.onNodeWithText("No amounts found in this message.").assertExists()
        rule.onAllNodesWithText("Save and use for future SMS").assertCountEquals(0)
    }
}
