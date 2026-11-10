package com.najeemfasil.expense.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.AppNav
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.navigation.placeholderDestinations
import com.najeemfasil.expense.ui.theme.ZeropenseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val routes = listOf(
        Routes.transaction("new") to "Transaction",
        Routes.IMPORT_SMS to "Paste an SMS",
        Routes.SMS_SETUP to "Set up bank SMS",
        Routes.ACCOUNTS to "Cards & accounts",
        Routes.accountNew("1234", "AED", "ENBD") to "Add card or account",
        Routes.CATEGORIES to "Categories",
        Routes.PARSERS to "SMS configuration",
        Routes.parserNew("Spent AED 1.00 at X & Y?", "ENBD") to "Set up SMS format"
    )

    @Test
    fun tabsSwitchAndEveryRouteOpensWithItsTitleAndGoesBack() {
        val destinations = placeholderDestinations().let { d ->
            com.najeemfasil.expense.ui.navigation.Destinations(
                overview = {
                    val nav = LocalNavigator.current
                    Screen(title = "Overview") {
                        for ((route, title) in routes) Button("Open $title", { nav.push(route) })
                    }
                },
                review = d.review, transactions = d.transactions, more = d.more, transaction = d.transaction,
                importSms = d.importSms, smsSetup = d.smsSetup, accounts = d.accounts,
                accountNew = { digits, currency, sender -> Screen { Body("$digits $currency $sender") } },
                categories = d.categories, parsers = d.parsers,
                parserNew = { sample, sender -> Screen { Body("$sample|$sender") } }
            )
        }
        rule.setContent { ZeropenseTheme { AppNav(destinations, pendingCount = 3) } }
        rule.snap("shell-light")
        rule.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()

        for ((_, title) in routes) {
            rule.onNodeWithText("Open $title").performClick()
            rule.onNodeWithText(title).assertIsDisplayed()
            if (title == "Add card or account") rule.onNodeWithText("1234 AED ENBD").assertIsDisplayed()
            if (title == "Set up SMS format") rule.onNodeWithText("Spent AED 1.00 at X & Y?|ENBD").assertIsDisplayed()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("Open $title").assertIsDisplayed()
        }
        for (tab in listOf("Review", "Transactions", "More")) {
            rule.onNodeWithText(tab).performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun darkTheme() {
        rule.shoot("shell-dark", dark = true) { AppNav(placeholderDestinations()) }
    }
}
