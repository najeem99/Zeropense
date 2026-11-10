package com.najeemfasil.expense.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.minorToInput
import com.najeemfasil.expense.core.parseAmountToMinor
import com.najeemfasil.expense.core.parseSmsDate
import com.najeemfasil.expense.core.toDateInput
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.accounts.accountLabel
import com.najeemfasil.expense.feature.categories.CategoryKind
import com.najeemfasil.expense.feature.categories.REFUND_CATEGORY
import com.najeemfasil.expense.feature.categories.categoryLook
import com.najeemfasil.expense.ui.components.AlertButton
import com.najeemfasil.expense.ui.components.AlertStyle
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.ButtonVariant
import com.najeemfasil.expense.ui.components.Card
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.theme.Radius
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors
import kotlinx.coroutines.launch

private enum class Kind(val label: String) {
    EXPENSE("Expense"),
    INCOME("Income"),
    REFUND("Refund")
}

private data class Form(
    val direction: Direction,
    val amount: String,
    val currency: Currency,
    val merchant: String,
    val date: String,
    val accountId: String?,
    val categoryId: String?,
    val note: String
)

private fun initialForm(tx: Transaction?): Form = if (tx == null) {
    Form(Direction.DEBIT, "", Currency.AED, "", toDateInput(System.currentTimeMillis()), null, null, "")
} else {
    Form(
        direction = tx.direction,
        amount = minorToInput(tx.amountMinor),
        currency = tx.currency,
        merchant = tx.merchant.orEmpty(),
        date = toDateInput(tx.occurredAt),
        accountId = tx.accountId,
        categoryId = tx.categoryId,
        note = tx.note.orEmpty()
    )
}

/** Wraps the loaded row so "still loading" and "no such transaction" differ. */
private class Loaded(val tx: Transaction?)

/** Add or edit a transaction (FSD §12.5). [id] is a transaction id, or "new". */
@Composable
fun TransactionScreen(id: String) {
    val lookups = rememberLookups()
    val loaded by rememberDbQuery(id) { Loaded(if (id == "new") null else getTransaction(it, id)) }
    val current = loaded
    if (current == null || lookups == null) return Screen {}
    TransactionForm(current.tx, lookups)
}

@Composable
private fun TransactionForm(tx: Transaction?, lookups: Lookups) {
    val c = colors
    val db = LocalDb.current
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(initialForm(tx)) }

    val account = lookups.accounts.firstOrNull { it.id == form.accountId }
    val isRefund = form.direction == Direction.CREDIT && form.categoryId == REFUND_CATEGORY
    val kind = if (isRefund) {
        Kind.REFUND
    } else if (form.direction == Direction.CREDIT) {
        Kind.INCOME
    } else {
        Kind.EXPENSE
    }
    val categoryKind = if (kind == Kind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val categories = lookups.categories.filter { it.kind == categoryKind && it.id != REFUND_CATEGORY }
    val setKind = { next: Kind ->
        if (next != kind) {
            form = if (next == Kind.REFUND) {
                form.copy(direction = Direction.CREDIT, categoryId = REFUND_CATEGORY)
            } else {
                form.copy(direction = if (next == Kind.INCOME) Direction.CREDIT else Direction.DEBIT, categoryId = null)
            }
        }
    }

    val save: () -> Unit = save@{
        val f = form
        val amountMinor = parseAmountToMinor(f.amount)
        if (amountMinor == null) {
            alerts.show("Enter an amount", "Use a number like 45.50.")
            return@save
        }
        val day = parseSmsDate(f.date)
        if (day == null) {
            alerts.show("Check the date", "Use the format YYYY-MM-DD.")
            return@save
        }
        // Keep the original time of day when only the date was edited.
        val occurredAt = if (tx != null && toDateInput(tx.occurredAt) == f.date) tx.occurredAt else day + 12 * 60 * 60 * 1000L
        val input = TransactionInput(
            amountMinor = amountMinor,
            currency = account?.currency ?: f.currency,
            direction = f.direction,
            merchant = f.merchant.jsTrim().ifEmpty { null },
            note = f.note.jsTrim().ifEmpty { null },
            occurredAt = occurredAt,
            accountId = f.accountId,
            categoryId = f.categoryId
        )
        scope.launch {
            db.write { saveTransaction(it, tx?.id, input) }
            nav.back()
        }
    }

    Screen {
        ChipRow {
            for (k in Kind.entries) Chip(k.label, selected = kind == k) { setKind(k) }
        }

        Field(
            "Amount",
            form.amount,
            { form = form.copy(amount = it) },
            placeholder = "0.00",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Label("Card / account")
            ChipRow {
                Chip("None", selected = form.accountId == null) { form = form.copy(accountId = null) }
                for (a in lookups.accounts) {
                    Chip("${accountLabel(a)} (${a.currency.name})", selected = form.accountId == a.id) {
                        form = form.copy(accountId = a.id, currency = a.currency)
                    }
                }
            }
        }

        if (account == null) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Label("Currency")
                ChipRow {
                    for (cur in Currency.entries) {
                        Chip(cur.name, selected = form.currency == cur) { form = form.copy(currency = cur) }
                    }
                }
            }
        }

        if (isRefund) {
            Body(
                "A refund is money back from a shop. It lowers your spending and doesn’t count as income.",
                muted = true,
                fontSize = 13.sp
            )
        }

        Field(
            if (kind == Kind.INCOME) "From" else "Merchant",
            form.merchant,
            { form = form.copy(merchant = it) },
            placeholder = "e.g. Gazebo"
        )

        if (!isRefund) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Label("Category")
                ChipRow {
                    for (cat in categories) {
                        Chip("${categoryLook(cat.id).icon} ${cat.name}", selected = form.categoryId == cat.id) {
                            form = form.copy(categoryId = if (form.categoryId == cat.id) null else cat.id)
                        }
                    }
                }
                val merchant = form.merchant.jsTrim()
                if (merchant.isNotEmpty() && form.categoryId != null) {
                    Body("Future transactions from “$merchant” will get this category.", muted = true, fontSize = 13.sp)
                }
            }
        }

        Field(
            "Date",
            form.date,
            { form = form.copy(date = it) },
            placeholder = "YYYY-MM-DD",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )
        Field("Note", form.note, { form = form.copy(note = it) }, placeholder = "Optional")

        val smsBody = tx?.smsBody
        if (!smsBody.isNullOrEmpty()) {
            Card {
                Label("SMS from ${tx.smsSender}")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(c.surfaceAlt)
                        .padding(Spacing.sm)
                ) { Body(smsBody, fontSize = 14.sp) }
            }
        }

        Button(if (tx?.status == TransactionStatus.PENDING) "Save and accept" else "Save", save)
        if (tx?.status == TransactionStatus.PENDING) {
            Button(
                "Reject",
                {
                    scope.launch {
                        db.write { setStatus(it, listOf(tx.id), TransactionStatus.REJECTED) }
                        nav.back()
                    }
                },
                variant = ButtonVariant.Danger
            )
        } else if (tx != null) {
            Button(
                "Delete",
                {
                    alerts.show(
                        "Delete transaction?",
                        if (tx.source == TransactionSource.SMS) "The SMS will not be imported again." else null,
                        listOf(
                            AlertButton("Cancel", AlertStyle.Cancel),
                            AlertButton("Delete", AlertStyle.Destructive) {
                                scope.launch {
                                    db.write { removeTransaction(it, tx) }
                                    nav.back()
                                }
                            }
                        )
                    )
                },
                variant = ButtonVariant.Danger
            )
        }
    }
}
