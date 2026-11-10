package com.najeemfasil.expense.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController

/** Navigation actions for screens, like `router` in the Expo app. */
class Navigator(private val nav: NavHostController, initialTab: Tab) {
    var tab by mutableStateOf(initialTab)
        private set

    fun push(route: String) = nav.navigate(route) { launchSingleTop = true }

    fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }

    /** Closes any stack screens and shows the given tab. */
    fun goToTab(target: Tab) {
        nav.popBackStack(Routes.TABS, inclusive = false)
        tab = target
    }

    fun selectTab(target: Tab) {
        tab = target
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }
