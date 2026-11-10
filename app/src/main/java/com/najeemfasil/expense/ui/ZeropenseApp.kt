package com.najeemfasil.expense.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.najeemfasil.expense.ui.navigation.AppNav
import com.najeemfasil.expense.ui.navigation.placeholderDestinations

@Composable
fun ZeropenseApp() {
    val destinations = remember { placeholderDestinations() }
    AppNav(destinations)
}
