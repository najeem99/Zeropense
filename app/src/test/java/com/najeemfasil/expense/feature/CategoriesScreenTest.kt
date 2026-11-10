package com.najeemfasil.expense.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.najeemfasil.expense.feature.categories.CategoriesScreen
import com.najeemfasil.expense.feature.categories.learnRule
import com.najeemfasil.expense.feature.categories.listCategories
import com.najeemfasil.expense.feature.categories.listRules
import com.najeemfasil.expense.ui.setScreen
import com.najeemfasil.expense.ui.snap
import com.najeemfasil.expense.ui.testDb
import kotlin.test.assertEquals
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
@Config(qualifiers = "w411dp-h2400dp-xxhdpi")
class CategoriesScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun addsDeletesAndForgets() = runBlocking {
        val db = testDb()
        db.write { learnRule(it, "Carrefour", "groceries") }
        rule.setScreen(db) { CategoriesScreen() }
        rule.snap("categories")

        rule.onNodeWithText("CARREFOUR").assertIsDisplayed()
        rule.onNodeWithText("Add category").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Kids")
        rule.onNodeWithText("Add category").performClick()
        rule.waitForIdle()
        assertTrue(db.read { c -> listCategories(c).any { it.name == "Kids" } })
        rule.onNodeWithText("🏷️  Kids").assertIsDisplayed()

        rule.onAllNodesWithText("Forget")[1].performClick()
        rule.waitForIdle()
        assertEquals(listOf("ASPORA"), db.read { c -> listRules(c).map { it.merchantKey } })

        rule.onAllNodesWithText("Delete")[0].performClick()
        rule.onNodeWithText("Delete Groceries?").assertIsDisplayed()
        rule.onNodeWithText("Its transactions become uncategorised.").assertIsDisplayed()
        rule.onAllNodesWithText("Delete").apply { this[fetchSemanticsNodes().size - 1].performClick() }
        rule.waitForIdle()
        assertTrue(db.read { c -> listCategories(c).none { it.id == "groceries" } })
    }
}
