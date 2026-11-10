package com.najeemfasil.expense.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.najeemfasil.expense.feature.categories.CategoriesScreen
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.AppNav
import com.najeemfasil.expense.ui.navigation.Destinations
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.PlaceholderScreen
import com.najeemfasil.expense.ui.navigation.Routes

/** The real screens. Destinations still to be built show a placeholder. */
fun appDestinations() = Destinations(
    overview = { PlaceholderScreen("Overview") },
    review = { PlaceholderScreen("Review") },
    transactions = { PlaceholderScreen("Transactions") },
    more = {
        // Temporary links until the More tab is built (F19).
        val nav = LocalNavigator.current
        Screen(title = "More") {
            Button("Categories", { nav.push(Routes.CATEGORIES) })
        }
    },
    transaction = { PlaceholderScreen() },
    importSms = { PlaceholderScreen() },
    smsSetup = { PlaceholderScreen() },
    accounts = { PlaceholderScreen() },
    accountNew = { _, _, _ -> PlaceholderScreen() },
    categories = { CategoriesScreen() },
    parsers = { PlaceholderScreen() },
    parserNew = { _, _ -> PlaceholderScreen() }
)

@Composable
fun ZeropenseApp() {
    val destinations = remember { appDestinations() }
    AppNav(destinations)
}
