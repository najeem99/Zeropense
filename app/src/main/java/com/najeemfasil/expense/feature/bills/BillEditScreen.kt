package com.najeemfasil.expense.feature.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.formatMonth
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.minorToInput
import com.najeemfasil.expense.core.parseAmountToMinor
import com.najeemfasil.expense.core.startOfMonth
import com.najeemfasil.expense.data.LocalDb
import com.najeemfasil.expense.data.rememberDbQuery
import com.najeemfasil.expense.feature.categories.CategoryKind
import com.najeemfasil.expense.feature.categories.REFUND_CATEGORY
import com.najeemfasil.expense.feature.categories.categoryLook
import com.najeemfasil.expense.feature.categories.listCategories
import com.najeemfasil.expense.ui.components.Body
import com.najeemfasil.expense.ui.components.Button
import com.najeemfasil.expense.ui.components.Chip
import com.najeemfasil.expense.ui.components.ChipRow
import com.najeemfasil.expense.ui.components.Field
import com.najeemfasil.expense.ui.components.Label
import com.najeemfasil.expense.ui.components.LocalAlerts
import com.najeemfasil.expense.ui.components.Screen
import com.najeemfasil.expense.ui.navigation.LocalNavigator
import com.najeemfasil.expense.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Values to start a new bill with (e.g. from a repeat payment in Insights). */
data class BillPrefill(
    val name: String = "",
    val amount: String = "",
    val currency: String = "",
    val day: String = "",
    val category: String = "",
    val merchant: String = ""
)

private data class BillForm(
    val kind: BillKind,
    val name: String,
    val amount: String,
    val currency: Currency,
    val day: String,
    val categoryId: String?,
    val merchant: String,
    val installments: String,
    val paidBefore: String,
    val startMonth: Long
)

private class LoadedBill(val bill: Bill?)

/** Add or edit a bill or EMI. [id] empty = new. */
@Composable
fun BillEditScreen(id: String, prefill: BillPrefill = BillPrefill()) {
    val loaded by rememberDbQuery(id) { LoadedBill(if (id.isEmpty()) null else getBill(it, id)) }
    val categories by rememberDbQuery { listCategories(it) }
    val l = loaded
    val cats = categories
    if (l == null || cats == null) return Screen {}
    BillForm(l.bill, prefill, cats.filter { it.kind == CategoryKind.EXPENSE && it.id != REFUND_CATEGORY })
}

@Composable
private fun BillForm(bill: Bill?, prefill: BillPrefill, categories: List<com.najeemfasil.expense.feature.categories.Category>) {
    val db = LocalDb.current
    val nav = LocalNavigator.current
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()
    var form by remember {
        mutableStateOf(
            if (bill != null) {
                BillForm(
                    bill.kind, bill.name, minorToInput(bill.amountMinor), bill.currency, bill.dueDay.toString(), bill.categoryId,
                    bill.merchantKey.orEmpty(), bill.installments?.toString().orEmpty(), bill.paidBefore.toString(), bill.startMonth
                )
            } else {
                BillForm(
                    BillKind.BILL, prefill.name, prefill.amount, Currency.from(prefill.currency) ?: Currency.AED, prefill.day,
                    prefill.category.ifEmpty { null }, prefill.merchant, "", "0", startOfMonth(System.currentTimeMillis())
                )
            }
        )
    }
    val isEmi = form.kind == BillKind.EMI

    val save: () -> Unit = save@{
        val f = form
        val name = f.name.jsTrim()
        if (name.isEmpty()) return@save alerts.show("Give it a name", "e.g. House rent or Car EMI.")
        val minor = parseAmountToMinor(f.amount) ?: return@save alerts.show("Enter the monthly amount", "Use a number like 1500.")
        val day = f.day.trim().toIntOrNull()?.takeIf { it in 1..31 } ?: return@save alerts.show("Due day", "Enter a day of the month from 1 to 31.")
        val total = f.installments.trim().toIntOrNull()
        val before = f.paidBefore.trim().ifEmpty { "0" }.toIntOrNull()
        if (isEmi && (total == null || total < 1)) return@save alerts.show("Number of instalments", "How many monthly instalments the loan has in total.")
        if (isEmi && (before == null || before < 0 || before > total!!)) return@save alerts.show("Instalments already paid", "Enter a number from 0 to $total.")
        val input = BillInput(name, f.kind, minor, f.currency, day, f.categoryId, f.merchant.jsTrim().ifEmpty { null }, total, before ?: 0, f.startMonth)
        scope.launch {
            db.write { saveBill(it, bill?.id, input) }
            nav.back()
        }
    }

    Screen {
        ChipRow {
            for (k in BillKind.entries) Chip(k.label, selected = form.kind == k) { form = form.copy(kind = k) }
        }
        Field("Name", form.name, { form = form.copy(name = it) }, placeholder = if (isEmi) "e.g. Car loan" else "e.g. House rent")
        Field(
            if (isEmi) "Monthly instalment" else "Monthly amount",
            form.amount,
            { form = form.copy(amount = it) },
            placeholder = "0.00",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Label("Currency")
            ChipRow {
                for (cur in Currency.entries) Chip(cur.name, selected = form.currency == cur) { form = form.copy(currency = cur) }
            }
        }
        Field(
            "Due day of the month",
            form.day,
            { form = form.copy(day = it.filter(Char::isDigit).take(2)) },
            placeholder = "1 to 31",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        if (isEmi) {
            Field(
                "Total instalments",
                form.installments,
                { form = form.copy(installments = it.filter(Char::isDigit).take(4)) },
                placeholder = "e.g. 48",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Field(
                "Instalments paid before ${formatMonth(form.startMonth)}",
                form.paidBefore,
                { form = form.copy(paidBefore = it.filter(Char::isDigit).take(4)) },
                placeholder = "0",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Field("Merchant on the SMS (optional)", form.merchant, { form = form.copy(merchant = it) }, placeholder = "e.g. Etisalat")
        Body(
            "When a payment to this merchant comes in, the month is marked paid. Leave it empty to mark months yourself.",
            muted = true,
            fontSize = 13.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Label("Category")
            ChipRow {
                for (cat in categories) {
                    Chip("${categoryLook(cat.id).icon} ${cat.name}", selected = form.categoryId == cat.id) {
                        form = form.copy(categoryId = if (form.categoryId == cat.id) null else cat.id)
                    }
                }
            }
        }
        Button(if (bill == null) "Add" else "Save", save)
    }
}
