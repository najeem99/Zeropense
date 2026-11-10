package com.najeemfasil.expense.feature.dashboard

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.feature.categories.REFUND_CATEGORY

/** The amount fields of one accepted transaction, for totals and charts. */
data class SummaryInput(
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val categoryId: String?,
    val occurredAt: Long = 0
)

data class CategorySpending(val categoryId: String?, val minor: Long)

data class CurrencySummary(
    val currency: Currency,
    /** Income, without refunds. */
    val inMinor: Long,
    /** Spending minus refunds. */
    val outMinor: Long,
    val refundMinor: Long,
    val netMinor: Long,
    /** Spending by category, largest first. null = uncategorised. */
    val topSpending: List<CategorySpending>
)

/** Totals per currency. AED and INR are never added together. */
fun summarize(txs: List<SummaryInput>): List<CurrencySummary> {
    class Acc {
        var income = 0L
        var out = 0L
        var refund = 0L
        val cats = LinkedHashMap<String?, Long>()
    }
    val by = LinkedHashMap<Currency, Acc>()
    for (t in txs) {
        val s = by.getOrPut(t.currency) { Acc() }
        if (t.direction == Direction.CREDIT && t.categoryId == REFUND_CATEGORY) {
            s.refund += t.amountMinor
        } else if (t.direction == Direction.CREDIT) {
            s.income += t.amountMinor
        } else {
            s.out += t.amountMinor
            s.cats[t.categoryId] = (s.cats[t.categoryId] ?: 0L) + t.amountMinor
        }
    }
    return by.entries
        .map { (currency, s) ->
            CurrencySummary(
                currency = currency,
                inMinor = s.income,
                outMinor = s.out - s.refund,
                refundMinor = s.refund,
                netMinor = s.income - (s.out - s.refund),
                topSpending = s.cats.entries.map { CategorySpending(it.key, it.value) }.sortedByDescending { it.minor }
            )
        }
        .sortedBy { it.currency.name }
}
