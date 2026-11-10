package com.najeemfasil.expense.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.data.rememberDbWriter
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Row
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.Spacing

private val KIND_ORDER = listOf(AccountKind.CREDIT_CARD, AccountKind.DEBIT_CARD, AccountKind.BANK)

/** Cards & accounts (FSD §12.9). */
@Composable
fun AccountsScreen() {
    val nav = LocalNavigator.current
    val write = rememberDbWriter()
    val alerts = LocalAlerts.current
    val data by rememberDbQuery { listAccounts(it) to listDetectedCards(it) }
    val (accounts, detected) = data ?: return Screen {}

    Screen {
        if (detected.isNotEmpty()) {
            Card {
                Label("Found in your SMS")
                Body(
                    "These card or account numbers appear in your bank messages. Add them so you can see which card each payment was made with.",
                    muted = true,
                    fontSize = 14.sp
                )
                for (d in detected) {
                    Row(
                        title = "Ending ••${d.digits}",
                        subtitle = listOfNotNull(
                            "${d.count} transaction${if (d.count == 1) "" else "s"}",
                            d.currency.name,
                            d.lastSender?.ifEmpty { null },
                            "last ${formatDate(d.lastAt)}"
                        ).joinToString(" · "),
                        right = {
                            Button(
                                "Add",
                                { nav.push(Routes.accountNew(d.digits, d.currency.name, d.lastSender.orEmpty())) },
                                variant = ButtonVariant.Secondary,
                                fullWidth = false
                            )
                        }
                    )
                }
            }
        }

        for (kind in KIND_ORDER) {
            val items = accounts.filter { it.kind == kind }
            if (items.isEmpty()) continue
            Card {
                Label("${kind.label}s")
                for (a in items) {
                    AccountRow(a) {
                        alerts.confirm(
                            "Delete ${accountLabel(a)}?",
                            "Its transactions are kept but no longer linked to it.",
                            action = "Delete",
                            destructive = true
                        ) { write { deleteAccount(it, a.id) } }
                    }
                }
            }
        }

        if (accounts.isEmpty() && detected.isEmpty()) {
            Body("No cards or accounts yet. Add one, or scan your SMS and the cards will be found for you.", muted = true)
        }

        Button("Add card or account", { nav.push(Routes.accountNew()) })
    }
}

/** A saved card; "Rename" swaps the row for a name field in place. */
@Composable
private fun AccountRow(a: Account, onDelete: () -> Unit) {
    val write = rememberDbWriter()
    var draft by rememberSaveable(a.id) { mutableStateOf<String?>(null) }
    val current = draft

    if (current == null) {
        Row(
            title = accountLabel(a),
            subtitle = a.currency.name,
            right = {
                Row {
                    Button("Rename", { draft = a.name }, variant = ButtonVariant.Ghost, fullWidth = false)
                    Button("Delete", onDelete, variant = ButtonVariant.Ghost, fullWidth = false)
                }
            }
        )
        return
    }

    val save = {
        if (current.jsTrim().isNotEmpty()) write { renameAccount(it, a.id, current) }
        draft = null
    }
    Column(Modifier.padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Field(
            label = if (!a.smsHint.isNullOrEmpty()) "Name for card ••${a.smsHint}" else "Name",
            value = current,
            onValueChange = { draft = it },
            placeholder = "e.g. ENBD Platinum",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            autoFocus = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button("Save", save, enabled = current.jsTrim().isNotEmpty(), fullWidth = false)
            Button("Cancel", { draft = null }, variant = ButtonVariant.Ghost, fullWidth = false)
        }
    }
}
