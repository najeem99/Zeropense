package com.najeemfasil.expense.feature.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.parseAmountToMinor
import com.najeemfasil.expense.core.parseSmsDate
import com.najeemfasil.expense.core.toDateInput
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.accounts.Account
import com.najeemfasil.expense.feature.accounts.accountLabel
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.ui.components.Amount
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Empty
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Row
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.navigation.Routes
import com.najeemfasil.expense.ui.theme.Spacing
import kotlinx.coroutines.launch

private class CardData(val accounts: List<Account>, val payments: List<CardPayment>)

/** Credit card bill payments, per card. */
@Composable
fun CardPaymentsScreen() {
    val nav = LocalNavigator.current
    val data by rememberDbQuery {
        val accounts = listAccounts(it)
        CardData(accounts, cardPayments(listCardPaymentTxs(it), creditCards(accounts)))
    }
    val d = data ?: return Screen {}
    val cards = creditCards(d.accounts)
    val names = d.accounts.associate { it.id to accountLabel(it) }

    Screen {
        Body(
            "Payments to your credit cards, such as a bill paid from your bank account. They don’t count as spending, " +
                "because the card purchases already do. Payments in the “Credit card payment” category show up here.",
            muted = true,
            fontSize = 14.sp
        )
        Button("Record a payment", { nav.push(Routes.CARD_PAYMENT_NEW) }, enabled = cards.isNotEmpty())

        if (cards.isEmpty()) {
            Empty("No credit cards yet", "Add your credit cards to see what you’ve paid to each one.") {
                Button("Cards & accounts", { nav.push(Routes.ACCOUNTS) }, fullWidth = false)
            }
        }

        for (card in cards) {
            val mine = d.payments.filter { it.cardId == card.id }
            Card {
                Label(accountLabel(card), uppercase = false)
                val totals = cardTotals(d.payments, card.id)
                if (totals.isEmpty()) {
                    Body("No payments yet.", muted = true, fontSize = 14.sp)
                } else {
                    for (t in totals) {
                        Body("Paid so far: ${formatMoney(t.totalMinor, t.currency)}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Body(
                            "${t.count} payment${if (t.count == 1) "" else "s"}" + (t.lastAt?.let { " · last on ${formatDate(it)}" } ?: ""),
                            muted = true,
                            fontSize = 13.sp
                        )
                    }
                }
                for (p in mine) PaymentRow(p, names)
            }
        }

        val unmatched = d.payments.filter { it.cardId == null }
        if (unmatched.isNotEmpty() && cards.isNotEmpty()) {
            Card {
                Label("Card not known")
                Body("Open a payment to choose the card it paid.", muted = true, fontSize = 13.sp)
                for (p in unmatched) PaymentRow(p, names)
            }
        }
    }
}

@Composable
private fun PaymentRow(p: CardPayment, names: Map<String, String>) {
    val nav = LocalNavigator.current
    val from = p.fromAccountId?.let { names[it] }?.let { "from $it" }
    val confirmed = if (p.confirmedById != null) "confirmed by the card’s SMS" else null
    Row(
        formatDate(p.occurredAt),
        subtitle = listOfNotNull(from, confirmed).joinToString(" · ").ifEmpty { null },
        onClick = { nav.push(Routes.transaction(p.txId)) },
        right = { Amount(p.amountMinor, p.currency) }
    )
}

/** Records a card payment by hand. */
@Composable
fun CardPaymentNewScreen() {
    val nav = LocalNavigator.current
    val db = LocalDb.current
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()
    val accounts by rememberDbQuery { listAccounts(it) }
    var cardId by rememberSaveable { mutableStateOf<String?>(null) }
    var fromId by rememberSaveable { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(toDateInput(System.currentTimeMillis())) }
    var note by rememberSaveable { mutableStateOf("") }
    val all = accounts ?: return Screen {}
    val cards = creditCards(all)
    val card = cards.firstOrNull { it.id == cardId } ?: cards.singleOrNull()

    val save: () -> Unit = save@{
        val c = card
        if (c == null) {
            alerts.show("Choose a card", null)
            return@save
        }
        val minor = parseAmountToMinor(amount)
        if (minor == null) {
            alerts.show("Enter an amount", "Use a number like 1500.")
            return@save
        }
        val day = parseSmsDate(date)
        if (day == null) {
            alerts.show("Check the date", "Use the format YYYY-MM-DD.")
            return@save
        }
        val text = note.jsTrim().ifEmpty { null }
        val from = fromId
        scope.launch {
            db.write { recordCardPayment(it, c, minor, day + 12 * 60 * 60 * 1000L, from, text) }
            nav.back()
        }
    }

    Screen {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Label("Card")
            ChipRow {
                for (c in cards) Chip("${accountLabel(c)} (${c.currency.name})", selected = card?.id == c.id) { cardId = c.id }
            }
        }
        Field(
            "Amount${card?.let { " (${it.currency.name})" } ?: ""}",
            amount,
            { amount = it },
            placeholder = "0.00",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        val banks = all.filter { a -> cards.none { it.id == a.id } }
        if (banks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Label("Paid from")
                ChipRow {
                    Chip("Not set", selected = fromId == null) { fromId = null }
                    for (a in banks) Chip(accountLabel(a), selected = fromId == a.id) { fromId = a.id }
                }
            }
        }
        Field(
            "Date",
            date,
            { date = it },
            placeholder = "YYYY-MM-DD",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )
        Field("Note", note, { note = it }, placeholder = "Optional")
        Body(
            "If the payment SMS was already imported, open it in Transactions and set its category to “Credit card payment” instead, so it isn’t counted twice.",
            Modifier.fillMaxWidth(),
            muted = true,
            fontSize = 13.sp
        )
        Button("Save payment", save)
        Button("Cancel", { nav.back() }, variant = ButtonVariant.Ghost)
    }
}
