package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.feature.sms.ImportSmsScreen
import com.najeemfasil.expense.feature.sms.RuleAction
import com.najeemfasil.expense.feature.sms.SmsRule
import com.najeemfasil.expense.feature.sms.replaceSmsRules
import com.najeemfasil.expense.feature.sms.templateKey
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

const val ENBD_EXAMPLE = "Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR MOE, DUBAI. Avl Cr. Limit is AED 12,000.00"

/** Configures ENBD card purchases as SMS setup would. */
fun configureEnbd(db: Db) = runBlocking {
    db.write {
        replaceSmsRules(
            it,
            listOf("EMIRATESNBD"),
            listOf(SmsRule("", "EMIRATESNBD", templateKey(ENBD_EXAMPLE), RuleAction.IMPORT, 0, Direction.DEBIT, Currency.AED, null, ENBD_EXAMPLE))
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class ImportSmsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = localTime(2026, 9, 11, 13)

    @Test
    fun aConfiguredKindLandsInReviewAndTheSecondTimeIsAlreadyImported() {
        val db = testDb()
        configureEnbd(db)
        rule.setScreen(db) { ImportSmsScreen(now = { now }) }
        rule.onNodeWithText("Read this SMS").assertIsNotEnabled()
        rule.onNodeWithText("ENBD card").performClick()
        rule.onNodeWithText("Read this SMS").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("ADDED TO REVIEW").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("−AED 45.50").assertIsDisplayed()
        rule.onNodeWithText("CARREFOUR MOE").assertIsDisplayed()
        rule.onNodeWithText("Go to review").assertIsDisplayed()
        rule.snap("import-sms-added")
        val pending = runBlocking { db.read { listPending(it) } }
        assertEquals(listOf(4550L to "EmiratesNBD"), pending.map { it.amountMinor to it.smsSender })

        rule.onNodeWithText("Read this SMS").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("ALREADY IMPORTED").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("This exact SMS was imported before.").assertIsDisplayed()
    }

    @Test
    fun otherSendersAreIgnoredAndUnknownKindsCantBeRead() {
        val db = testDb()
        configureEnbd(db)
        rule.setScreen(db) { ImportSmsScreen(now = { now }) }
        rule.onNodeWithText("HDFC UPI").performClick()
        rule.onNodeWithText("Read this SMS").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("IGNORED").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Not a configured bank SMS. Only the kinds of bank SMS you set up are imported.").assertIsDisplayed()
        rule.onNodeWithText("Set up this SMS format").assertIsDisplayed()
        rule.snap("import-sms-ignored")

        rule.onNodeWithText("OTP").performScrollTo().performClick()
        rule.onAllNodesWithText("IGNORED").assertCountEquals(0)

        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("EmiratesNBD")
        fields[1].performTextReplacement("Card 4455 used. Amt: 87.25 AED. Shop: LULU.")
        rule.onNodeWithText("Read this SMS").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("COULDN'T READ THIS SMS").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("It's from one of your banks but doesn't match a kind you set up. You can teach the app this format.")
            .assertIsDisplayed()
    }
}
