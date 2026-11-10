package com.najeemfasil.expense.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.feature.sms.profileForSender
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import kotlinx.coroutines.launch

private val KINDS = listOf(AccountKind.CREDIT_CARD, AccountKind.DEBIT_CARD, AccountKind.BANK)

/**
 * Add card or account (FSD §12.10). [digits], [currency] and [sender] are filled in when the
 * card was found in SMS.
 */
@Composable
fun AccountNewScreen(digits: String = "", currency: String = "", sender: String = "") {
    val nav = LocalNavigator.current
    val db = LocalDb.current
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()
    val fromSms = digits.isNotEmpty()
    val bank = remember(sender) { if (sender.isNotEmpty()) profileForSender(sender)?.name ?: sender else "" }

    // No default: the user must say whether it's a credit or debit card.
    var kind by rememberSaveable { mutableStateOf<AccountKind?>(null) }
    var name by rememberSaveable { mutableStateOf(bank) }
    var cur by rememberSaveable { mutableStateOf(if (currency == "INR") Currency.INR else Currency.AED) }
    var last4 by rememberSaveable { mutableStateOf(digits) }
    var showKindError by rememberSaveable { mutableStateOf(false) }

    val save: () -> Unit = save@{
        val k = kind
        if (k == null) {
            showKindError = true
            return@save
        }
        val newName = name.jsTrim().ifEmpty { k.label }
        val newCurrency = cur
        val hint = last4
        scope.launch {
            var linked = 0
            db.write { linked = createAccount(it, newName, k, newCurrency, hint) }
            if (linked > 0) {
                alerts.show(
                    "Added",
                    "$linked earlier transaction${if (linked == 1) " was" else "s were"} linked to this ${k.label.lowercase()}."
                )
            }
            nav.back()
        }
    }

    Screen {
        if (fromSms) {
            Body(
                "Found in your SMS${if (bank.isNotEmpty()) " from $bank" else ""}: a card or account ending ••$digits.",
                muted = true
            )
        }

        Card {
            Label("Is this a credit card or a debit card?")
            ChipRow {
                for (k in KINDS) {
                    Chip(k.label, selected = kind == k) {
                        kind = k
                        showKindError = false
                    }
                }
            }
            if (showKindError) Body("Choose one to continue.", color = colors.expense, fontSize = 14.sp)
        }

        Card {
            Field("Name", name, { name = it }, placeholder = "e.g. ENBD Platinum")
            Field(
                "Last 4 digits",
                last4,
                { t -> last4 = t.filter { it in '0'..'9' }.takeLast(4) },
                placeholder = "1234",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Body(
                "SMS mentioning these digits (like “card ending 1234”) are linked to this ${kind?.label?.lowercase() ?: "card"}.",
                muted = true,
                fontSize = 13.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Label("Currency")
                ChipRow {
                    for (c in Currency.entries) Chip(c.name, selected = cur == c) { cur = c }
                }
            }
        }

        Button("Save", save)
    }
}
