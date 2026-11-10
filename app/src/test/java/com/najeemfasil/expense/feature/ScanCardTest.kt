package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.najeemfasil.expense.feature.sms.FakeSmsInbox
import com.najeemfasil.expense.feature.sms.ImportSummary
import com.najeemfasil.expense.feature.sms.InboxMessage
import com.najeemfasil.expense.feature.sms.ScanCard
import com.najeemfasil.expense.feature.sms.SmsScanner
import com.najeemfasil.expense.feature.sms.describeScan
import com.najeemfasil.expense.feature.transactions.listPending
import com.najeemfasil.expense.ui.components.Screen
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
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class ScanCardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_789_000_000_000L

    @Test
    fun describesScanResults() {
        assertEquals("Checked 1 new SMS · 1 transaction to review", describeScan(ImportSummary(read = 1, added = 1)))
        assertEquals(
            "Checked 5 new SMS · 3 transactions to review · 2 possible duplicates · 1 not understood",
            describeScan(ImportSummary(read = 5, added = 3, duplicates = 2, unread = 1))
        )
    }

    @Test
    fun asksForSetupUntilSomethingIsConfigured() {
        val db = testDb()
        rule.setScreen(db) { Screen(title = "More") { ScanCard() } }
        rule.waitUntil { rule.onAllNodesWithText("SET UP YOUR BANK SMS").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Set up bank SMS").assertIsDisplayed()
        rule.snap("scan-card-setup")
    }

    @Test
    fun checkNowImportsNewSmsOnce() {
        val db = testDb()
        configureEnbd(db)
        val inbox = FakeSmsInbox(listOf(InboxMessage("EmiratesNBD", ENBD_EXAMPLE, now - 60_000), InboxMessage("Mom", "hi", now - 30_000)))
        val scanner = SmsScanner(db, inbox, { now })
        rule.setScreen(db, inbox = inbox, scanner = scanner) { Screen(title = "More") { ScanCard() } }
        rule.waitUntil { rule.onAllNodesWithText("Check now").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("New bank SMS of the kinds you set up go to Review.").assertIsDisplayed()
        rule.onNodeWithText("Check now").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Checked 2 new SMS · 1 transaction to review").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Edit SMS setup").assertIsDisplayed()
        rule.snap("scan-card-checked")
        rule.onNodeWithText("Check now").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Checked 0 new SMS · 0 transactions to review").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, runBlocking { db.read { listPending(it) } }.size)
    }
}
