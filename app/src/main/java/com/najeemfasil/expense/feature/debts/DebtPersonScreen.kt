package com.najeemfasil.expense.feature.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Row as ListRow
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import kotlinx.coroutines.launch

private class PersonData(val person: DebtPerson?, val entries: List<DebtEntry>)

internal fun noWhatsApp(alerts: com.najeemfasil.expense.ui.components.Alerts) = alerts.show("WhatsApp isn’t installed", "Use “Share another way” to send it by SMS or another app.")

/** One person: balance, entries (newest first), adding entries and sharing on WhatsApp. */
@Composable
fun DebtPersonScreen(id: String) {
    val c = colors
    val db = LocalDb.current
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by rememberDbQuery(id) { PersonData(getPerson(it, id), listEntries(it, id)) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    val loaded = data ?: return Screen {}
    val person = loaded.person ?: return Screen { Empty("This person was deleted") }
    val entries = loaded.entries
    val balances = balancesOf(entries)

    Screen {
        Card {
            Text(person.name, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            person.phone?.takeIf { it.isNotEmpty() }?.let { Body(it, muted = true, fontSize = 14.sp) }
            BalanceLines(balances, fontSize = 20, alignEnd = false)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                "You gave",
                { nav.push(Routes.debtEntry(person.id, kind = DebtKind.GAVE.db)) },
                Modifier.weight(1f),
                variant = ButtonVariant.Secondary
            )
            Button(
                "You got",
                { nav.push(Routes.debtEntry(person.id, kind = DebtKind.GOT.db)) },
                Modifier.weight(1f),
                variant = ButtonVariant.Secondary
            )
        }
        Body(
            "“You gave” is money you lent or paid back to them. “You got” is money they lent you or paid you back.",
            muted = true,
            fontSize = 13.sp
        )

        if (entries.isEmpty()) {
            Empty("No entries yet", "Record money you gave ${person.name} or got from them.")
        } else {
            Card {
                Label("Entries")
                for (e in entries) {
                    ListRow(
                        e.note ?: e.kind.label,
                        subtitle = if (e.note != null) "${formatDate(e.occurredAt)} · ${e.kind.label}" else formatDate(e.occurredAt),
                        onClick = { nav.push(Routes.debtEntry(person.id, id = e.id)) },
                        right = {
                            MoneyText(
                                formatMoney(e.amountMinor, e.currency),
                                if (e.kind == DebtKind.GAVE) c.expense else c.income
                            )
                        }
                    )
                }
            }
            Button("Share on WhatsApp", {
                if (!shareOnWhatsApp(context, personSummaryText(person, entries), person.phone)) noWhatsApp(alerts)
            })
            Button(
                "Share another way",
                { shareText(context, personSummaryText(person, entries)) },
                variant = ButtonVariant.Ghost
            )
        }

        if (editing) {
            Card {
                Field("Name", name, { name = it })
                Field(
                    "Phone (for WhatsApp)",
                    phone,
                    { phone = it },
                    placeholder = "e.g. +971 50 123 4567",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Button("Save", {
                    scope.launch {
                        db.write { updatePerson(it, person.id, name, phone) }
                        editing = false
                    }
                })
                Button("Cancel", { editing = false }, variant = ButtonVariant.Ghost)
            }
        } else {
            Button(
                "Edit name or phone",
                {
                    name = person.name
                    phone = person.phone.orEmpty()
                    editing = true
                },
                variant = ButtonVariant.Secondary
            )
        }
        Button(
            "Delete person",
            {
                alerts.confirm(
                    "Delete ${person.name}?",
                    "Their ${entries.size} entr${if (entries.size == 1) "y is" else "ies are"} deleted too. This can’t be undone.",
                    action = "Delete",
                    destructive = true
                ) {
                    scope.launch {
                        db.write { deletePerson(it, person.id) }
                        nav.back()
                    }
                }
            },
            variant = ButtonVariant.Danger
        )
    }
}
