package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.najeemfasil.expense.feature.more.MoreScreen
import com.najeemfasil.expense.feature.sms.isSmsConfigured
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.snap
import com.najeemfasil.expense.ui.testDb
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
class MoreScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startOverLeavesAFreshDatabase() {
        val db = testDb()
        configureEnbd(db)
        rule.setScreen(db) { MoreScreen() }
        rule.waitUntil { rule.onAllNodesWithText("Check now").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Choose which amount to take from a bank SMS").assertExists()
        rule.onNodeWithText("Your data stays on this device. SMS are read on the phone and never uploaded.").assertExists()
        rule.snap("more")
        rule.onNodeWithText("Delete all data and start over").performScrollTo().performClick()
        rule.onNodeWithText("Delete all data and start over?").assertExists()
        rule.onNodeWithText("Delete everything").performClick()
        rule.waitUntil(5000) { runBlocking { !db.read { isSmsConfigured(it) } } }
        assertFalse(runBlocking { db.read { isSmsConfigured(it) } })
        assertTrue(runBlocking { db.read { it.first("SELECT COUNT(*) FROM categories") { r -> r.int(0) } } }!! > 0)
    }
}
