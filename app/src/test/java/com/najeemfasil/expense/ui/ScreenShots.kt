package com.najeemfasil.expense.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.najeemfasil.expense.ui.theme.ZeropenseTheme

typealias ComposeRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** Renders [content] in the app theme and saves it as build/outputs/roborazzi/<name>.png. */
fun ComposeRule.shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
    setContent { ZeropenseTheme(dark = dark, content = content) }
    snap(name)
}

fun ComposeRule.snap(name: String) {
    waitForIdle()
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}

/** A fresh in-memory database whose queries run inline, so screens are settled when the rule is idle. */
fun testDb(): com.najeemfasil.expense.data.db.Db = com.najeemfasil.expense.data.db.Db.open(com.najeemfasil.expense.data.db.Db.IN_MEMORY, kotlinx.coroutines.Dispatchers.Unconfined)

/** Renders a screen with a database, alerts and navigation, as inside the app. */
fun ComposeRule.setScreen(
    db: com.najeemfasil.expense.data.db.Db,
    dark: Boolean = false,
    inbox: com.najeemfasil.expense.feature.sms.SmsInbox = com.najeemfasil.expense.feature.sms.FakeSmsInbox(),
    scanner: com.najeemfasil.expense.feature.sms.SmsScanner = com.najeemfasil.expense.feature.sms.SmsScanner(db, inbox),
    content: @Composable () -> Unit
) {
    setContent {
        ZeropenseTheme(dark = dark) {
            androidx.compose.runtime.CompositionLocalProvider(com.najeemfasil.expense.data.LocalDb provides db, com.najeemfasil.expense.data.LocalSmsInbox provides inbox, com.najeemfasil.expense.feature.sms.LocalSmsScanner provides scanner) {
                val d = com.najeemfasil.expense.ui.navigation.placeholderDestinations()
                com.najeemfasil.expense.ui.navigation.AppNav(
                    com.najeemfasil.expense.ui.navigation.Destinations(
                        overview = content, review = d.review, transactions = d.transactions, more = d.more,
                        transaction = d.transaction, importSms = d.importSms, smsSetup = d.smsSetup,
                        accounts = d.accounts, accountNew = d.accountNew, categories = d.categories,
                        parsers = d.parsers, parserNew = d.parserNew
                    )
                )
            }
        }
    }
}
