package com.najeemfasil.expense.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.najeemfasil.expense.ui.components.AlertHost
import com.najeemfasil.expense.ui.theme.colors

/** What each destination shows. Features fill these in one by one. */
class Destinations(
    val overview: @Composable () -> Unit,
    val review: @Composable () -> Unit,
    val transactions: @Composable () -> Unit,
    val more: @Composable () -> Unit,
    val transaction: @Composable (id: String) -> Unit,
    val importSms: @Composable () -> Unit,
    val smsSetup: @Composable () -> Unit,
    val accounts: @Composable () -> Unit,
    val accountNew: @Composable (digits: String, currency: String, sender: String) -> Unit,
    val categories: @Composable () -> Unit,
    val parsers: @Composable () -> Unit,
    val parserNew: @Composable (sample: String, sender: String) -> Unit
)

private const val ANIM_MS = 250

@Composable
fun AppNav(destinations: Destinations, pendingCount: Int = 0) {
    val nav = rememberNavController()
    val savedTab = rememberSaveable { intArrayOf(0) }
    val navigator = remember(nav) { Navigator(nav, Tab.entries[savedTab[0]]) }
    LaunchedEffect(navigator.tab) { savedTab[0] = navigator.tab.ordinal }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        AlertHost {
            NavHost(
                navController = nav,
                startDestination = Routes.TABS,
                modifier = Modifier.fillMaxSize().background(colors.background),
                enterTransition = { slideIntoContainer(SlideDirection.Start, tween(ANIM_MS)) },
                exitTransition = { fadeOut(tween(ANIM_MS)) },
                popEnterTransition = { fadeIn(tween(ANIM_MS)) },
                popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(ANIM_MS)) }
            ) {
                composable(Routes.TABS) { TabShell(navigator, destinations, pendingCount) }

                // Modal-style: slides up from the bottom.
                composable(
                    Routes.TRANSACTION,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    enterTransition = { slideInVertically(tween(ANIM_MS)) { it } },
                    popExitTransition = { slideOutVertically(tween(ANIM_MS)) { it } }
                ) { entry ->
                    StackScreen("Transaction", navigator) {
                        destinations.transaction(entry.arg("id").ifEmpty { "new" })
                    }
                }
                stack(Routes.IMPORT_SMS, "Paste an SMS", navigator) { destinations.importSms() }
                stack(Routes.SMS_SETUP, "Set up bank SMS", navigator) { destinations.smsSetup() }
                stack(Routes.ACCOUNTS, "Cards & accounts", navigator) { destinations.accounts() }
                stack(
                    Routes.ACCOUNT_NEW,
                    "Add card or account",
                    navigator,
                    args = listOf("digits", "currency", "sender")
                ) { e -> destinations.accountNew(e.arg("digits"), e.arg("currency"), e.arg("sender")) }
                stack(Routes.CATEGORIES, "Categories", navigator) { destinations.categories() }
                stack(Routes.PARSERS, "SMS configuration", navigator) { destinations.parsers() }
                stack(Routes.PARSER_NEW, "Set up SMS format", navigator, args = listOf("sample", "sender")) { e ->
                    destinations.parserNew(e.arg("sample"), e.arg("sender"))
                }
            }
        }
    }
}

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()

private fun NavGraphBuilder.stack(
    route: String,
    title: String,
    navigator: Navigator,
    args: List<String> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit
) {
    composable(
        route,
        arguments = args.map {
            navArgument(it) {
                type = NavType.StringType
                defaultValue = ""
            }
        }
    ) { entry -> StackScreen(title, navigator) { content(entry) } }
}

/** A screen with a header and a back button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StackScreen(title: String, navigator: Navigator, content: @Composable () -> Unit) {
    val c = colors
    Scaffold(
        containerColor = c.background,
        topBar = {
            TopAppBar(
                title = { Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

private val TAB_ICONS: Map<Tab, ImageVector>
    get() = mapOf(
        Tab.Overview to Icons.Outlined.DonutLarge,
        Tab.Review to Icons.Outlined.Inbox,
        Tab.Transactions to Icons.AutoMirrored.Outlined.ReceiptLong,
        Tab.More to Icons.Outlined.MoreHoriz
    )

@Composable
private fun TabShell(navigator: Navigator, destinations: Destinations, pendingCount: Int) {
    val c = colors
    val holder = rememberSaveableStateHolder()
    val icons = remember { TAB_ICONS }
    Scaffold(
        containerColor = c.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = c.surface) {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = navigator.tab == tab,
                        onClick = { navigator.selectTab(tab) },
                        icon = {
                            BadgedBox(badge = {
                                if (tab == Tab.Review && pendingCount > 0) {
                                    Badge(
                                        containerColor = c.expense,
                                        contentColor = androidx.compose.ui.graphics.Color.White
                                    ) {
                                        Text(pendingCount.toString())
                                    }
                                }
                            }) { Icon(icons.getValue(tab), contentDescription = null) }
                        },
                        label = { Text(tab.label, fontSize = 12.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.primary,
                            selectedTextColor = c.primary,
                            unselectedIconColor = c.textMuted,
                            unselectedTextColor = c.textMuted,
                            indicatorColor = c.surfaceAlt
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Each tab keeps its own state (selected month, scroll) while another one is shown.
            holder.SaveableStateProvider(navigator.tab.name) {
                when (navigator.tab) {
                    Tab.Overview -> destinations.overview()
                    Tab.Review -> destinations.review()
                    Tab.Transactions -> destinations.transactions()
                    Tab.More -> destinations.more()
                }
            }
        }
    }
}
