package com.najeemfasil.expense.ui.navigation

import androidx.compose.runtime.Composable
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Screen

@Composable
fun PlaceholderScreen(title: String? = null, text: String = "Coming in a later step of the rebuild.") {
    Screen(title = title) { Body(text, muted = true) }
}

/** Every destination as a placeholder; features replace them one by one. */
fun placeholderDestinations() = Destinations(
    overview = { PlaceholderScreen("Overview") },
    review = { PlaceholderScreen("Review") },
    transactions = { PlaceholderScreen("Transactions") },
    more = { PlaceholderScreen("More") },
    transaction = { PlaceholderScreen() },
    importSms = { PlaceholderScreen() },
    smsSetup = { PlaceholderScreen() },
    accounts = { PlaceholderScreen() },
    accountNew = { _, _, _ -> PlaceholderScreen() },
    categories = { PlaceholderScreen() },
    parsers = { PlaceholderScreen() },
    parserNew = { _, _ -> PlaceholderScreen() }
)
