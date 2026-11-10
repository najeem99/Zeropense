package com.najeemfasil.expense.feature.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.formatDateTime
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.data.rememberDbWriter
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Row as ListRow
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.Spacing

private data class ParsersData(val parsers: List<StoredParser>, val unread: List<UnreadSms>)

/** SMS configuration: unread messages, your formats and the built-in list (FSD §12.11). */
@Composable
fun ParsersScreen() {
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val write = rememberDbWriter()
    val data by rememberDbQuery { ParsersData(listCustomParsers(it), listUnread(it)) }
    val d = data ?: return Screen {}
    Screen {
        Button("Set up a new SMS format", { nav.push(Routes.parserNew()) })

        if (d.unread.isNotEmpty()) {
            Card {
                Label("Messages we couldn't read (${d.unread.size})")
                Body(
                    "These have an amount but no format matched. Set one up and similar messages are read from then on.",
                    muted = true,
                    fontSize = 14.sp
                )
                for (u in d.unread.take(20)) {
                    Column(Modifier.padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Body("${u.sender} · ${formatDateTime(u.receivedAt)}", muted = true, fontSize = 13.sp)
                        Body(u.body, fontSize = 14.sp, maxLines = 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Button(
                                "Set up this format",
                                { nav.push(Routes.parserNew(u.body, u.sender)) },
                                variant = ButtonVariant.Secondary,
                                fullWidth = false
                            )
                            Button("Ignore", { write { dismissUnread(it, u.smsHash) } }, variant = ButtonVariant.Ghost, fullWidth = false)
                        }
                    }
                }
            }
        }

        Card {
            Label("Your formats")
            if (d.parsers.isEmpty()) Body("None yet. Yours are tried before the built-in ones.", muted = true)
            for (sp in d.parsers) {
                val p = sp.parser
                ListRow(
                    p.name,
                    subtitle = listOf(
                        p.senderContains?.let { "Sender contains “$it”" } ?: "Any sender",
                        p.currency.name,
                        if (p.direction == Direction.DEBIT) "Money out" else "Money in"
                    ).joinToString(" · "),
                    right = {
                        Button(
                            "Delete",
                            { alerts.confirm("Delete ${p.name}?", action = "Delete", destructive = true) { write { deleteCustomParser(it, p.id) } } },
                            variant = ButtonVariant.Ghost,
                            fullWidth = false
                        )
                    }
                )
            }
        }

        Card {
            Label("Built in")
            for (b in BANK_PROFILES) ListRow(b.name, subtitle = "${b.defaultCurrency?.name ?: ""} · matched by sender")
            ListRow("Generic", subtitle = "Any bank, AED or INR, using common wording")
        }
    }
}
