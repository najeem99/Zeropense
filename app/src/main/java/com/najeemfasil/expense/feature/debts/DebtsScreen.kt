package com.najeemfasil.expense.feature.debts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.core.jsTrim
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

/** "AED 60.00 · ₹1,500.00", each amount positive. */
internal fun amountsText(amounts: Map<Currency, Long>): String = amounts.entries.joinToString(" · ") { (c, v) -> formatMoney(if (v < 0) -v else v, c) }

/** Balance with one person: "owes you" amounts in green, "you owe" amounts in red, stacked. */
@Composable
internal fun BalanceLines(balances: Map<Currency, Long>, fontSize: Int = 16, alignEnd: Boolean = true) {
    val c = colors
    val owesMe = balances.filterValues { it > 0 }
    val iOwe = balances.filterValues { it < 0 }
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        if (balances.isEmpty()) Body("Settled", muted = true, fontSize = 14.sp)
        if (owesMe.isNotEmpty()) {
            MoneyText(amountsText(owesMe), c.income, fontSize)
            Body("owes you", muted = true, fontSize = 12.sp)
        }
        if (iOwe.isNotEmpty()) {
            MoneyText(amountsText(iOwe), c.expense, fontSize)
            Body("you owe", muted = true, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun MoneyText(text: String, color: androidx.compose.ui.graphics.Color, fontSize: Int = 16) {
    Text(
        text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.SemiBold,
        style = TextStyle(fontFeatureSettings = "tnum")
    )
}

/** Debts list: everyone with what they owe or are owed, and adding a person. */
@Composable
fun DebtsScreen() {
    val c = colors
    val db = LocalDb.current
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people by rememberDbQuery { listPeopleWithBalances(it) }
    var manual by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    val add = { n: String, p: String?, key: String? ->
        scope.launch {
            val id = db.write { addPerson(it, n, p, key) }
            manual = false
            name = ""
            phone = ""
            nav.push(Routes.debtPerson(id))
        }
    }
    val picker = rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        if (uri != null) {
            val picked = readPickedContact(context, uri)
            if (picked == null) {
                alerts.show("Couldn’t read that contact", "Try again, or add the person without a contact.")
            } else {
                add(picked.name, picked.phone, picked.lookupKey)
            }
        }
    }
    val openPicker = {
        try {
            picker.launch(Unit)
        } catch (_: android.content.ActivityNotFoundException) {
            alerts.show("No contacts app", "Add the person without a contact instead.")
        }
    }

    val list = people ?: return Screen {}
    Screen {
        val t = totals(list)
        if (list.isNotEmpty()) {
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Label("You’ll get")
                        if (t.toGet.isEmpty()) Body("Nothing", muted = true)
                        for ((cur, v) in t.toGet) MoneyText(formatMoney(v, cur), c.income, 18)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Label("You’ll give")
                        if (t.toGive.isEmpty()) Body("Nothing", muted = true)
                        for ((cur, v) in t.toGive) MoneyText(formatMoney(v, cur), c.expense, 18)
                    }
                }
            }
        }

        Button("Add from contacts", openPicker)
        if (!manual) {
            Button("Add without a contact", { manual = true }, variant = ButtonVariant.Secondary)
        } else {
            Card {
                Field("Name", name, { name = it }, placeholder = "e.g. Ali", autoFocus = true)
                Field(
                    "Phone (for WhatsApp)",
                    phone,
                    { phone = it },
                    placeholder = "Optional, e.g. +971 50 123 4567",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Button(
                    "Add person",
                    {
                        if (name.jsTrim().isEmpty()) {
                            alerts.show("Enter a name")
                        } else {
                            add(name, phone, null)
                        }
                    }
                )
                Button("Cancel", { manual = false }, variant = ButtonVariant.Ghost)
            }
        }

        if (list.isEmpty()) {
            Empty(
                "No debts yet",
                "Add a person, then record money you gave them or got from them. Each person keeps a running balance."
            )
        } else {
            Card {
                for (p in list) {
                    ListRow(
                        p.person.name,
                        subtitle = p.lastAt?.let { "Last entry ${formatDate(it)}" } ?: p.person.phone,
                        onClick = { nav.push(Routes.debtPerson(p.person.id)) },
                        right = { BalanceLines(p.balances) }
                    )
                }
            }
        }
        Body(
            "Debts are kept apart from your spending and don’t change the Overview.",
            Modifier.fillMaxWidth(),
            muted = true,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
