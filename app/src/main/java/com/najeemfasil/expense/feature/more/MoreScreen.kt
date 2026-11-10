package com.najeemfasil.expense.feature.more

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.db.resetAllData
import com.najeemfasil.expense.feature.sms.LocalSmsScanner
import com.najeemfasil.expense.feature.sms.ScanCard
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Row
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import kotlinx.coroutines.launch

private val LINKS = listOf(
    Triple("SMS configuration", "Choose which amount to take from a bank SMS", Routes.PARSERS),
    Triple("Cards & accounts", "Credit and debit cards, found from SMS by last 4 digits", Routes.ACCOUNTS),
    Triple("Paste an SMS", "Test how a single message is read", Routes.IMPORT_SMS),
    Triple("Categories", "Categories and remembered merchants", Routes.CATEGORIES)
)

/** More tab (FSD §12.4). */
@Composable
fun MoreScreen() {
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val db = LocalDb.current
    val scanner = LocalSmsScanner.current
    val scope = rememberCoroutineScope()
    val confirmReset = {
        alerts.confirm(
            "Delete all data and start over?",
            "All transactions, cards, categories, SMS setup and learned merchants are deleted, like a new install. " +
                "Your SMS stay on the phone, so you can import them again. This can’t be undone.",
            action = "Delete everything",
            destructive = true
        ) {
            scope.launch {
                scanner.waitForScan()
                db.write { resetAllData(it) }
                nav.push(Routes.SMS_SETUP)
            }
        }
    }
    Screen(title = "More") {
        ScanCard()
        Card {
            for ((title, subtitle, route) in LINKS) {
                Row(title, subtitle = subtitle, onClick = { nav.push(route) }, right = { Body("›", muted = true) })
            }
        }
        Button("Delete all data and start over", confirmReset, variant = ButtonVariant.Danger)
        Body(
            "Your data stays on this device. SMS are read on the phone and never uploaded.",
            Modifier.fillMaxWidth(),
            muted = true,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
