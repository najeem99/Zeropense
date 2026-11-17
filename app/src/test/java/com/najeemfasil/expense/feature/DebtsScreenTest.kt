package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.feature.backup.BackupScreen
import com.najeemfasil.expense.feature.debts.DebtEntryScreen
import com.najeemfasil.expense.feature.debts.DebtInput
import com.najeemfasil.expense.feature.debts.DebtKind
import com.najeemfasil.expense.feature.debts.DebtPersonScreen
import com.najeemfasil.expense.feature.debts.DebtsScreen
import com.najeemfasil.expense.feature.debts.addPerson
import com.najeemfasil.expense.feature.debts.fromPickerMillis
import com.najeemfasil.expense.feature.debts.listEntries
import com.najeemfasil.expense.feature.debts.listPeopleWithBalances
import com.najeemfasil.expense.feature.debts.saveEntry
import com.najeemfasil.expense.feature.debts.toPickerMillis
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
class DebtsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun seed(db: com.najeemfasil.expense.data.db.Db): String = runBlocking {
        db.write {
            val ali = addPerson(it, "Ali Hassan", "+971 50 123 4567", null)
            saveEntry(it, ali, null, DebtInput(DebtKind.GAVE, 10_000, Currency.AED, "Dinner", localTime(2026, 10, 2, 12)))
            saveEntry(it, ali, null, DebtInput(DebtKind.GOT, 4_000, Currency.AED, null, localTime(2026, 10, 5, 12)))
            val ravi = addPerson(it, "Ravi", "98765 43210", null)
            saveEntry(it, ravi, null, DebtInput(DebtKind.GOT, 150_000, Currency.INR, "Rent share", localTime(2026, 10, 4, 12)))
            ali
        }
    }

    @Test
    fun listShowsTotalsAndBalances() {
        val db = testDb()
        seed(db)
        rule.setScreen(db) { DebtsScreen() }
        rule.onNodeWithText("Ali Hassan").assertExists()
        rule.onAllNodesWithText("AED 60.00").assertCountEquals(2)
        rule.onAllNodesWithText("₹1,500.00").assertCountEquals(2)
        rule.onNodeWithText("owes you").assertExists()
        rule.onNodeWithText("you owe").assertExists()
        rule.snap("debts")
    }

    @Test
    fun emptyListAddsAPersonWithoutAContact() {
        val db = testDb()
        rule.setScreen(db) { DebtsScreen() }
        rule.onNodeWithText("No debts yet").assertExists()
        rule.snap("debts-empty")
        rule.onNodeWithText("Add without a contact").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Sam")
        rule.onNodeWithText("Add person").performClick()
        rule.waitUntil(5000) { runBlocking { db.read { listPeopleWithBalances(it).size } } == 1 }
        assertEquals("Sam", runBlocking { db.read { listPeopleWithBalances(it).single().person.name } })
    }

    @Test
    fun personScreenShowsEntriesAndSharing() {
        val db = testDb()
        val ali = seed(db)
        rule.setScreen(db) { DebtPersonScreen(ali) }
        rule.onNodeWithText("Dinner").assertExists()
        rule.onNodeWithText("Share on WhatsApp").assertExists()
        rule.snap("debt-person")
    }

    @Test
    fun personScreenDark() {
        val db = testDb()
        val ali = seed(db)
        rule.setScreen(db, dark = true) { DebtPersonScreen(ali) }
        rule.onNodeWithText("Ali Hassan").assertExists()
        rule.snap("debt-person-dark")
    }

    @Test
    fun addsAnEntryWithNote() {
        val db = testDb()
        val ali = seed(db)
        rule.setScreen(db) { DebtEntryScreen(ali, "", "got") }
        rule.onNodeWithText("You got").assertIsSelected()
        rule.snap("debt-entry")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Enter an amount").assertExists()
        rule.onNodeWithText("OK").performClick()
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("25")
        fields[1].performTextInput("Taxi")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5000) { runBlocking { db.read { listEntries(it, ali).size } } == 3 }
        val e = runBlocking { db.read { listEntries(it, ali) } }.first { it.note == "Taxi" }
        assertEquals(DebtKind.GOT, e.kind)
        assertEquals(2_500, e.amountMinor)
        assertEquals(Currency.AED, e.currency)
    }

    @Test
    fun datePickerRoundTripsTheLocalDay() {
        val at = localTime(2026, 10, 17, 9, 30)
        val utc = toPickerMillis(at)
        assertEquals(at, fromPickerMillis(utc, at))
        val other = localTime(2026, 0, 31, 0, 0)
        assertEquals(localTime(2026, 0, 31, 9, 30), fromPickerMillis(toPickerMillis(other), at))
    }

    @Test
    fun backupScreenWithoutBackupsAvailable() {
        val db = testDb()
        rule.setScreen(db) { BackupScreen() }
        rule.onNodeWithText("Backups aren’t available here.").assertExists()
    }

    @Test
    fun backupScreen() {
        val db = testDb()
        val backups = com.najeemfasil.expense.data.Backups(rule.activity, db)
        rule.setScreen(db) {
            androidx.compose.runtime.CompositionLocalProvider(com.najeemfasil.expense.data.LocalBackups provides backups) {
                BackupScreen()
            }
        }
        rule.onNodeWithText("Choose a folder").assertExists()
        rule.onNodeWithText("Restore from a backup file").assertExists()
        rule.snap("backup")
    }
}
